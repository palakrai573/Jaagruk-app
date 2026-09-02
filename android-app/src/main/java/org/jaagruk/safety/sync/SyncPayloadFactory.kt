package org.jaagruk.safety.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.db.SyncQueueEntity
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.sync.api.AssessmentUpload
import org.jaagruk.safety.sync.api.CertificateUpload
import org.jaagruk.safety.sync.api.HazardUpload
import org.jaagruk.safety.sync.api.ProgressUpload

/**
 * Turns a queue entry into the exact DTO the server expects.
 *
 * There is one of these, used by both delivery paths — direct upload over HTTP and relay through a
 * supervisor's handset over Nearby. That is deliberate: two builders would be two chances for the wire
 * shape to drift, and a record that uploads correctly by one route and 422s by the other is a bug that
 * only shows up underground where nobody can debug it.
 *
 * It also means the idempotency key travels unchanged through both routes, which is what collapses the
 * two paths onto a single server-side row instead of double-ingesting a relayed certificate.
 */
class SyncPayloadFactory(
    private val database: JaagrukDatabase,
    private val assessments: AssessmentRepository,
) {

    private val certificateDao = database.certificateDao()
    private val runDao = database.assessmentRunDao()
    private val hazardDao = database.hazardTagDao()
    private val progressDao = database.trainingProgressDao()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** One buildable item. `null` from [build] means the underlying row is gone. */
    sealed interface Item {
        val idempotencyKey: String

        data class Certificate(val upload: CertificateUpload) : Item {
            override val idempotencyKey: String get() = upload.idempotencyKey
        }

        data class Assessment(val upload: AssessmentUpload) : Item {
            override val idempotencyKey: String get() = upload.idempotencyKey
        }

        data class Hazard(val upload: HazardUpload) : Item {
            override val idempotencyKey: String get() = upload.idempotencyKey
        }

        data class Progress(val upload: ProgressUpload) : Item {
            override val idempotencyKey: String get() = upload.idempotencyKey
        }
    }

    suspend fun build(entry: SyncQueueEntity): Item? = when (SyncKind.fromWireName(entry.kind)) {
        SyncKind.CERTIFICATE -> certificate(entry)?.let(Item::Certificate)
        SyncKind.ASSESSMENT -> assessment(entry)?.let(Item::Assessment)
        SyncKind.HAZARD -> hazard(entry)?.let(Item::Hazard)
        SyncKind.PROGRESS -> progress(entry)?.let(Item::Progress)
        SyncKind.RELAY -> relay(entry)
        null -> null
    }

    // -----------------------------------------------------------------------
    // Local rows
    // -----------------------------------------------------------------------

    private suspend fun certificate(entry: SyncQueueEntity): CertificateUpload? {
        val certificate = certificateDao.find(entry.refId) ?: return null
        val moduleId = ModuleCatalog.byCode(certificate.moduleCode)?.moduleId ?: return null
        return CertificateUpload(
            idempotencyKey = entry.idempotencyKey,
            // Only `qrText` is trusted server-side. Everything else is context for name resolution,
            // and the server re-decodes and re-verifies the signed bytes, so a device cannot claim a
            // score its own signature does not support.
            qrText = certificate.qrText,
            workerId = certificate.workerId,
            moduleId = moduleId,
            keyEpoch = certificate.keyEpoch,
            runId = certificate.runId,
        )
    }

    private suspend fun assessment(entry: SyncQueueEntity): AssessmentUpload? {
        val run = runDao.find(entry.refId) ?: return null
        return AssessmentUpload(
            idempotencyKey = entry.idempotencyKey,
            runId = run.runId,
            workerId = run.workerId,
            siteId = run.siteId,
            moduleId = run.moduleId,
            moduleCode = run.moduleCode,
            scenarioId = run.scenarioId,
            catalogVersion = run.catalogVersion,
            // Enum names are stored upper-case so `valueOf` round-trips locally; the wire is
            // lower-case snake to match the server's own check constraints exactly.
            mode = run.mode.lowercase(),
            presentation = run.presentation.lowercase(),
            completion = run.completion.lowercase(),
            scorePermille = run.scorePermille,
            passed = run.passed,
            hesitationFlag = run.hesitationFlag,
            hesitationRatio = run.hesitationRatio,
            medianLatencyMs = run.medianLatencyMs,
            startedAtSec = run.startedAtSec,
            finishedAtSec = run.finishedAtSec,
            totalDurationMs = run.totalDurationMs,
            steps = assessments.decodeSteps(run.stepsJson).map { step ->
                step.copy(
                    outcome = step.outcome.lowercase(),
                    inputMethod = step.inputMethod.lowercase(),
                )
            },
            failedCriticalStepIds = assessments.decodeFailedCriticalSteps(
                run.failedCriticalStepsJson,
            ),
            voidReason = run.voidReason?.lowercase(),
            abortReason = run.abortReason?.lowercase(),
            buddyPeerDeviceId = run.buddyPeerDeviceId,
        )
    }

    private suspend fun hazard(entry: SyncQueueEntity): HazardUpload? {
        val hazard = hazardDao.find(entry.refId) ?: return null
        return HazardUpload(
            idempotencyKey = entry.idempotencyKey,
            hazardId = hazard.hazardId,
            siteId = hazard.siteId,
            reporterWorkerId = hazard.reporterWorkerId,
            category = hazard.category,
            severity = hazard.severity,
            note = hazard.note,
            latitude = hazard.latitude,
            longitude = hazard.longitude,
            zoneLabel = hazard.zoneLabel,
            arAnchorId = hazard.arAnchorId,
            photoMediaId = hazard.photoMediaId,
            voiceMediaId = hazard.voiceMediaId,
            reportedAtSec = hazard.createdAtSec,
        )
    }

    private suspend fun progress(entry: SyncQueueEntity): ProgressUpload? {
        val parts = entry.refId.split('|', limit = 2)
        if (parts.size != 2) return null
        val row = progressDao.find(parts[0], parts[1]) ?: return null
        return ProgressUpload(
            idempotencyKey = entry.idempotencyKey,
            workerId = row.workerId,
            siteId = row.siteId,
            moduleId = row.moduleId,
            moduleCode = row.moduleCode,
            baseScore = row.baseScore,
            lastPassAtSec = row.lastPassAtSec,
            certifiedAtSec = row.certifiedAtSec,
            refresherStage = row.refresherStage,
            nextDueAtSec = row.nextDueAtSec,
            consecutiveFailures = row.consecutiveFailures,
            attempts = row.attempts,
            bestScorePermille = row.bestScorePermille,
            lastHesitationFlag = row.lastHesitationFlag,
        )
    }

    // -----------------------------------------------------------------------
    // Relay
    // -----------------------------------------------------------------------

    /**
     * A record that arrived from another handset.
     *
     * Stored as the finished DTO rather than as a reference, because the relaying device does not own
     * the underlying row and must not invent one. It is a courier: it carries signed bytes it cannot
     * alter, under an idempotency key it cannot change, and it never appends to another site's chain.
     */
    @Serializable
    data class RelayEnvelope(
        @SerialName("item_kind") val itemKind: String,
        @SerialName("certificate") val certificate: CertificateUpload? = null,
        @SerialName("assessment") val assessment: AssessmentUpload? = null,
        @SerialName("hazard") val hazard: HazardUpload? = null,
        @SerialName("progress") val progress: ProgressUpload? = null,
    )

    /** One transfer between two handsets. */
    @Serializable
    data class RelayBundle(
        @SerialName("origin_device_id") val originDeviceId: String,
        @SerialName("site_id") val siteId: String,
        @SerialName("items") val items: List<RelayEnvelope> = emptyList(),
    )

    fun encodeBundle(bundle: RelayBundle): String = json.encodeToString(RelayBundle.serializer(), bundle)

    fun decodeBundle(payload: String): RelayBundle? = try {
        json.decodeFromString(RelayBundle.serializer(), payload)
    } catch (e: Exception) {
        // A malformed bundle is dropped, not partially applied. The sender keeps its queue because it
        // never receives an ACK, so nothing is lost by refusing this one.
        null
    }

    fun encodeEnvelope(envelope: RelayEnvelope): String =
        json.encodeToString(RelayEnvelope.serializer(), envelope)

    fun envelopeFor(item: Item): RelayEnvelope = when (item) {
        is Item.Certificate -> RelayEnvelope(SyncKind.CERTIFICATE.wireName, certificate = item.upload)
        is Item.Assessment -> RelayEnvelope(SyncKind.ASSESSMENT.wireName, assessment = item.upload)
        is Item.Hazard -> RelayEnvelope(SyncKind.HAZARD.wireName, hazard = item.upload)
        is Item.Progress -> RelayEnvelope(SyncKind.PROGRESS.wireName, progress = item.upload)
    }

    private fun relay(entry: SyncQueueEntity): Item? {
        val envelope = try {
            json.decodeFromString(RelayEnvelope.serializer(), entry.payloadJson)
        } catch (e: Exception) {
            return null
        }
        return when (envelope.itemKind) {
            SyncKind.CERTIFICATE.wireName -> envelope.certificate?.let(Item::Certificate)
            SyncKind.ASSESSMENT.wireName -> envelope.assessment?.let(Item::Assessment)
            SyncKind.HAZARD.wireName -> envelope.hazard?.let(Item::Hazard)
            SyncKind.PROGRESS.wireName -> envelope.progress?.let(Item::Progress)
            else -> null
        }
    }
}
