package org.jaagruk.safety.data.repo

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.assessment.AssessmentResult
import org.jaagruk.core.assessment.AssessmentSession
import org.jaagruk.core.assessment.Completion
import org.jaagruk.core.assessment.ScenarioSpec
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.db.AssessmentRunEntity
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.sync.SyncKind
import org.jaagruk.safety.sync.api.StepResultUpload
import java.util.UUID

/**
 * Runs, persists and certifies assessments.
 *
 * The ordering here is the point. A run row is written **before** the first step is presented, as
 * `INCOMPLETE`, so process death mid-drill leaves a resumable record instead of nothing. A finished
 * run is then stored, folded into retention, and only then does certificate issuance happen — and if
 * issuance cannot happen (no site key on this handset), the pass is still stored and the certificate
 * is minted later. The worker did the work; losing the proof because a supervisor had not enrolled a
 * key yet would be the wrong trade.
 */
class AssessmentRepository(
    private val database: JaagrukDatabase,
    private val certificates: CertificateRepository,
    private val retention: RetentionRepository,
    private val clock: WallClock,
    private val monotonic: MonotonicTimeSource,
) {

    private val runs = database.assessmentRunDao()
    private val queue = database.syncQueueDao()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun observeRecent(workerId: String, limit: Int = 20): Flow<List<AssessmentRunEntity>> =
        runs.observeRecent(workerId, limit)

    suspend fun find(runId: String): AssessmentRunEntity? = runs.find(runId)

    suspend fun findResumable(workerId: String): AssessmentRunEntity? =
        runs.findResumable(workerId)

    suspend fun discard(runId: String) = runs.delete(runId)

    suspend fun pendingUpload(limit: Int): List<AssessmentRunEntity> = runs.pendingUpload(limit)

    suspend fun markUploaded(runIds: List<String>) = runs.markUploaded(runIds)

    /** A session plus the row that already represents it on disk. */
    data class StartedRun(val session: AssessmentSession, val runId: String)

    /**
     * Creates a session and its placeholder row.
     *
     * [presentation] is decided by the AR layer from what it actually achieved, not requested by the
     * UI, because it ends up signed into the certificate. A run that fell back to sensor-only
     * tracking must not be able to claim it happened in a site-scanned scene.
     */
    suspend fun startRun(
        workerId: String,
        siteId: String,
        scenario: ScenarioSpec,
        moduleCode: Int,
        mode: AssessmentMode,
        presentation: ArPresentation,
        buddyPeerDeviceId: String? = null,
    ): StartedRun {
        val runId = UUID.randomUUID().toString()
        val nowSec = clock.epochSeconds()

        runs.insert(
            AssessmentRunEntity(
                runId = runId,
                workerId = workerId,
                siteId = siteId,
                moduleId = scenario.moduleId,
                moduleCode = moduleCode,
                scenarioId = scenario.scenarioId,
                catalogVersion = ModuleCatalog.CATALOG_VERSION,
                mode = mode.name,
                presentation = presentation.name,
                completion = Completion.INCOMPLETE.name,
                scorePermille = 0,
                passed = false,
                hesitationFlag = false,
                hesitationRatio = 0.0,
                medianLatencyMs = 0L,
                startedAtSec = nowSec,
                finishedAtSec = 0L,
                totalDurationMs = 0L,
                stepsJson = "[]",
                failedCriticalStepsJson = "[]",
                buddyPeerDeviceId = buddyPeerDeviceId,
                uploaded = false,
            ),
        )

        val session = AssessmentSession(
            runId = runId,
            scenario = scenario,
            moduleCode = moduleCode,
            mode = mode,
            presentation = presentation,
            monotonic = monotonic,
            wallClock = clock,
            buddyPeerDeviceId = buddyPeerDeviceId,
        )
        return StartedRun(session, runId)
    }

    /** What happened after a run was sealed, so the result screen can be honest about all of it. */
    data class SavedRun(
        val result: AssessmentResult,
        val certificate: CertificateRepository.IssueResult?,
        /** True when the run passed but no certificate could be issued yet. */
        val certificatePending: Boolean,
    )

    /**
     * Persists a sealed result, updates retention, and issues a certificate when the run earns one.
     *
     * The run row and the queue entry commit together. Certificate issuance runs in its own
     * transaction inside [CertificateRepository] because it has its own atomicity requirement — the
     * chain append, the head advance and the certificate insert must be one unit, and nesting that
     * inside this one would widen the window in which the chain head is held under write.
     */
    suspend fun saveResult(
        result: AssessmentResult,
        workerId: String,
        siteId: String,
        siteScannedAr: Boolean,
    ): SavedRun {
        database.withTransaction {
            runs.insert(toEntity(result, workerId, siteId))
            queue.enqueue(
                SyncKind.ASSESSMENT.queueEntry(
                    refId = result.runId,
                    idempotencyKey = "assessment:${result.runId}",
                    payloadJson = SyncKind.assessmentPayload(result.runId),
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }

        retention.applyRun(
            workerId = workerId,
            siteId = siteId,
            moduleId = result.moduleId,
            moduleCode = result.moduleCode,
            mode = result.mode,
            scorePermille = result.scorePermille,
            passed = result.passed,
            hesitationFlag = result.hesitationFlag,
        )

        if (!result.certifiable) {
            return SavedRun(result, certificate = null, certificatePending = false)
        }

        val issued = issueFor(result, workerId, siteId, siteScannedAr)
        return SavedRun(
            result = result,
            certificate = issued,
            certificatePending = issued is CertificateRepository.IssueResult.NoSigningKey,
        )
    }

    /**
     * Mints the certificate for a certifiable run.
     *
     * `SlotTaken` is retried once against the refreshed head. Two supervisors certifying two workers
     * in the same second is ordinary at a shift handover, and the unique index on `(siteId, seq)` is
     * what decides the winner rather than whichever coroutine happened to run first.
     */
    private suspend fun issueFor(
        result: AssessmentResult,
        workerId: String,
        siteId: String,
        siteScannedAr: Boolean,
    ): CertificateRepository.IssueResult {
        val flags = buildFlags(result, siteScannedAr)

        repeat(SLOT_RETRY_ATTEMPTS) { attempt ->
            val outcome = certificates.issue(
                siteId = siteId,
                workerId = workerId,
                moduleCode = result.moduleCode,
                scorePermille = result.scorePermille,
                medianLatencyMs = result.medianLatencyMs,
                outcomeFlags = flags,
                runId = result.runId,
            )
            if (outcome !is CertificateRepository.IssueResult.SlotTaken) return outcome
            Log.i(TAG, "chain slot taken for $siteId, retrying (attempt ${attempt + 1})")
        }
        return CertificateRepository.IssueResult.SlotTaken
    }

    /**
     * Builds the signed flags from what the engine observed.
     *
     * Every one of these is a fact about the run, not a setting. `BUDDY_DRILL` requires a real peer
     * device id, `SITE_SCANNED_AR` requires the AR layer to have resolved real site anchors, and
     * `ASSISTED_MODE` records that gesture or voice input was used. Because they are inside the
     * signature, none of them can be improved after the fact.
     */
    private fun buildFlags(result: AssessmentResult, siteScannedAr: Boolean): OutcomeFlags {
        var flags = OutcomeFlags.NONE
        if (result.passed) flags = flags.with(OutcomeFlags.PASSED)
        if (result.hesitationFlag) flags = flags.with(OutcomeFlags.HESITATION)
        if (result.mode == AssessmentMode.BUDDY && !result.buddyPeerDeviceId.isNullOrBlank()) {
            flags = flags.with(OutcomeFlags.BUDDY_DRILL)
        }
        if (siteScannedAr && result.presentation == ArPresentation.SITE_SCANNED) {
            flags = flags.with(OutcomeFlags.SITE_SCANNED_AR)
        }
        if (result.mode == AssessmentMode.REFRESHER) flags = flags.with(OutcomeFlags.REFRESHER)
        if (result.assistiveInputUsed) flags = flags.with(OutcomeFlags.ASSISTED_MODE)
        return flags
    }

    /**
     * Re-attempts issuance for passes that were stored without a certificate.
     *
     * Called after a supervisor enrols a site key. Without this, every pass recorded before enrolment
     * would need to be re-run — which in practice means it would not be, and those workers would stay
     * uncertified.
     */
    suspend fun issuePendingCertificates(siteId: String, siteScannedAr: Boolean): Int {
        var issued = 0
        val candidates = runs.passedWithoutCertificate(siteId, PENDING_CERTIFICATE_SCAN_LIMIT)

        for (run in candidates) {
            val outcome = certificates.issue(
                siteId = run.siteId,
                workerId = run.workerId,
                moduleCode = run.moduleCode,
                scorePermille = run.scorePermille,
                medianLatencyMs = run.medianLatencyMs,
                outcomeFlags = flagsFromEntity(run, siteScannedAr),
                runId = run.runId,
            )
            if (outcome is CertificateRepository.IssueResult.Issued) issued++
        }
        return issued
    }

    private fun flagsFromEntity(run: AssessmentRunEntity, siteScannedAr: Boolean): OutcomeFlags {
        var flags = OutcomeFlags.NONE
        if (run.passed) flags = flags.with(OutcomeFlags.PASSED)
        if (run.hesitationFlag) flags = flags.with(OutcomeFlags.HESITATION)
        if (run.mode == AssessmentMode.BUDDY.name && !run.buddyPeerDeviceId.isNullOrBlank()) {
            flags = flags.with(OutcomeFlags.BUDDY_DRILL)
        }
        if (siteScannedAr && run.presentation == ArPresentation.SITE_SCANNED.name) {
            flags = flags.with(OutcomeFlags.SITE_SCANNED_AR)
        }
        if (run.mode == AssessmentMode.REFRESHER.name) flags = flags.with(OutcomeFlags.REFRESHER)
        return flags
    }

    // -----------------------------------------------------------------------
    // Serialisation
    // -----------------------------------------------------------------------

    private fun toEntity(
        result: AssessmentResult,
        workerId: String,
        siteId: String,
    ): AssessmentRunEntity = AssessmentRunEntity(
        runId = result.runId,
        workerId = workerId,
        siteId = siteId,
        moduleId = result.moduleId,
        moduleCode = result.moduleCode,
        scenarioId = result.scenarioId,
        catalogVersion = ModuleCatalog.CATALOG_VERSION,
        mode = result.mode.name,
        presentation = result.presentation.name,
        completion = result.completion.name,
        scorePermille = result.scorePermille,
        passed = result.passed,
        hesitationFlag = result.hesitationFlag,
        hesitationRatio = result.hesitationRatio,
        medianLatencyMs = result.medianLatencyMs,
        startedAtSec = result.startedAtEpochSec,
        finishedAtSec = result.finishedAtEpochSec,
        totalDurationMs = result.totalDurationMs,
        stepsJson = encodeSteps(result),
        failedCriticalStepsJson = json.encodeToString(result.failedCriticalStepIds),
        voidReason = result.voidReason?.name,
        abortReason = result.abortReason?.name,
        buddyPeerDeviceId = result.buddyPeerDeviceId,
        uploaded = false,
    )

    /**
     * Stores per-step detail in the exact shape the upload uses.
     *
     * One representation, not two. A second mapping at upload time is where a disputed score
     * silently stops matching the record it came from, and these rows have to stay recomputable
     * years later when someone contests a certificate.
     */
    private fun encodeSteps(result: AssessmentResult): String = json.encodeToString(
        result.steps.map { step ->
            StepResultUpload(
                stepId = step.stepId,
                outcome = step.outcome.name,
                latencyMs = step.latencyMs,
                expertMs = step.expertMs,
                timeoutMs = step.timeoutMs,
                correct = step.isCorrect,
                critical = step.critical,
                weight = step.weight,
                inputMethod = step.inputMethod.name,
                suspiciousFast = step.suspiciousFast,
            )
        },
    )

    fun decodeSteps(stepsJson: String): List<StepResultUpload> = try {
        json.decodeFromString(stepsJson)
    } catch (e: Exception) {
        // A corrupted blob must not stop the rest of the run uploading. The aggregate figures are
        // the ones compliance depends on; step detail is diagnostic.
        Log.w(TAG, "could not decode stored step detail", e)
        emptyList()
    }

    fun decodeFailedCriticalSteps(payload: String): List<String> = try {
        json.decodeFromString(payload)
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        const val TAG = "AssessmentRepository"
        const val SLOT_RETRY_ATTEMPTS = 3
        const val PENDING_CERTIFICATE_SCAN_LIMIT = 200
    }
}
