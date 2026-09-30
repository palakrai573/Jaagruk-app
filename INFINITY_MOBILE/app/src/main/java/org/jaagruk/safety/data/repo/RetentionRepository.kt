package org.jaagruk.safety.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.core.retention.ReadinessCalculator
import org.jaagruk.core.retention.RequiredAction
import org.jaagruk.core.retention.RetentionState
import org.jaagruk.core.retention.SpacedRepetitionScheduler
import org.jaagruk.core.retention.ValidityAssessment
import org.jaagruk.core.retention.ValidityEvaluator
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.db.RefresherScheduleEntity
import org.jaagruk.safety.data.db.TrainingProgressEntity
import org.jaagruk.safety.sync.SyncKind

/**
 * Retention state, readiness and the refresher schedule.
 *
 * Readiness is **computed on read**, never stored. There is no decay job, no nightly task, no
 * server tick. A handset that spent six weeks underground with the radio off reports the correct
 * figure the instant it powers on, because the figure is arithmetic over five stored numbers rather
 * than a cached value that something was supposed to have refreshed.
 *
 * Statutory validity and operational readiness are kept strictly apart. A certificate from eleven
 * months ago is legally current; whether the worker would still act correctly in a gas leak is a
 * different question. The cohort that matters most to a site officer is the one that is
 * statutorily valid *and* operationally stale, and merging the two numbers is exactly what would
 * hide it.
 */
class RetentionRepository(
    private val database: JaagrukDatabase,
    private val clock: WallClock,
) {

    private val progress = database.trainingProgressDao()
    private val schedule = database.refresherScheduleDao()
    private val queue = database.syncQueueDao()

    /** One module's standing for one worker, ready for display. */
    data class ModuleStanding(
        val moduleId: String,
        val moduleCode: Int,
        val titleKey: String,
        val validity: ValidityAssessment,
        val attempts: Int,
        val bestScorePermille: Int,
        val lastHesitationFlag: Boolean,
        val fullyImplemented: Boolean,
    ) {
        val readinessPermille: Int get() = validity.readinessPermille
        val band: ReadinessBand get() = validity.band
        val requiredAction: RequiredAction get() = validity.requiredAction
    }

    fun observeStandings(workerId: String): Flow<List<ModuleStanding>> =
        progress.observeForWorker(workerId).map { rows ->
            val nowSec = clock.epochSeconds()
            val byModule = rows.associateBy { it.moduleId }
            ModuleCatalog.all.map { module ->
                val row = byModule[module.moduleId]
                ModuleStanding(
                    moduleId = module.moduleId,
                    moduleCode = module.moduleCode,
                    titleKey = module.titleKey,
                    validity = ValidityEvaluator.evaluate(row?.toRetentionState(), nowSec),
                    attempts = row?.attempts ?: 0,
                    bestScorePermille = row?.bestScorePermille ?: 0,
                    lastHesitationFlag = row?.lastHesitationFlag ?: false,
                    fullyImplemented = module in ModuleCatalog.fullyImplemented,
                )
            }
        }

    suspend fun standing(workerId: String, moduleId: String): ModuleStanding? {
        val module = ModuleCatalog.byId(moduleId) ?: return null
        val row = progress.find(workerId, moduleId)
        return ModuleStanding(
            moduleId = module.moduleId,
            moduleCode = module.moduleCode,
            titleKey = module.titleKey,
            validity = ValidityEvaluator.evaluate(row?.toRetentionState(), clock.epochSeconds()),
            attempts = row?.attempts ?: 0,
            bestScorePermille = row?.bestScorePermille ?: 0,
            lastHesitationFlag = row?.lastHesitationFlag ?: false,
            fullyImplemented = module in ModuleCatalog.fullyImplemented,
        )
    }

    suspend fun retentionState(workerId: String, moduleId: String): RetentionState? =
        progress.find(workerId, moduleId)?.toRetentionState()

    /**
     * Which mode the next run of this module should use.
     *
     * The decision is not the caller's to make. Offering a two-minute refresher to a worker whose
     * readiness has already collapsed would issue a renewal on evidence that does not support it.
     */
    suspend fun recommendedMode(workerId: String, moduleId: String): AssessmentMode {
        val state = retentionState(workerId, moduleId) ?: return AssessmentMode.INITIAL
        val nowSec = clock.epochSeconds()
        return if (SpacedRepetitionScheduler.refresherIsSufficient(state, nowSec) &&
            state.baseScore > 0
        ) {
            AssessmentMode.REFRESHER
        } else {
            AssessmentMode.INITIAL
        }
    }

    /**
     * Folds a completed run into the retention model and re-arms the schedule.
     *
     * One transaction covering progress, schedule and the outbound queue: a partial write here would
     * leave a worker either permanently overdue or, worse, permanently not due.
     */
    suspend fun applyRun(
        workerId: String,
        siteId: String,
        moduleId: String,
        moduleCode: Int,
        mode: AssessmentMode,
        scorePermille: Int,
        passed: Boolean,
        hesitationFlag: Boolean,
    ) {
        val nowSec = clock.epochSeconds()

        database.withTransaction {
            val existing = progress.find(workerId, moduleId)
            val before = existing?.toRetentionState()

            val after = when {
                !passed && before != null -> SpacedRepetitionScheduler.onRefresherFailed(
                    before,
                    nowSec,
                )

                !passed -> null

                before == null || before.baseScore == 0 ->
                    SpacedRepetitionScheduler.onInitialPass(scorePermille, nowSec)

                mode == AssessmentMode.REFRESHER ->
                    SpacedRepetitionScheduler.onRefresherPassed(before, scorePermille, nowSec)

                // A full re-run resets the statutory clock as well as readiness. A refresher never
                // does, which is the distinction that keeps a two-minute check from silently
                // renewing a twelve-month certificate.
                else -> SpacedRepetitionScheduler.onFullRerunPassed(before, scorePermille, nowSec)
            }

            val row = TrainingProgressEntity(
                workerId = workerId,
                moduleId = moduleId,
                siteId = siteId,
                moduleCode = moduleCode,
                baseScore = after?.baseScore ?: existing?.baseScore ?: 0,
                lastPassAtSec = after?.lastPassAtEpochSec ?: existing?.lastPassAtSec ?: 0L,
                certifiedAtSec = after?.certifiedAtEpochSec ?: existing?.certifiedAtSec ?: 0L,
                refresherStage = after?.refresherStage ?: existing?.refresherStage ?: 0,
                nextDueAtSec = after?.nextDueAtEpochSec ?: existing?.nextDueAtSec ?: 0L,
                consecutiveFailures = after?.consecutiveFailures
                    ?: ((existing?.consecutiveFailures ?: 0) + if (passed) 0 else 1),
                attempts = (existing?.attempts ?: 0) + 1,
                bestScorePermille = maxOf(existing?.bestScorePermille ?: 0, scorePermille),
                lastHesitationFlag = hesitationFlag,
                updatedAtSec = nowSec,
            )
            progress.upsert(row)

            if (row.nextDueAtSec > 0L) {
                schedule.upsert(
                    RefresherScheduleEntity(
                        workerId = workerId,
                        moduleId = moduleId,
                        dueAtSec = row.nextDueAtSec,
                        stage = row.refresherStage,
                        notifiedAtSec = 0L,
                    ),
                )
            }

            queue.enqueue(
                SyncKind.PROGRESS.queueEntry(
                    refId = "$workerId|$moduleId",
                    // Keyed by the update instant as well as the pair, so a later run enqueues a
                    // fresh upload rather than being swallowed as a duplicate of the earlier one.
                    idempotencyKey = "progress:$workerId:$moduleId:$nowSec",
                    payloadJson = SyncKind.progressPayload(workerId, moduleId),
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Schedule
    // -----------------------------------------------------------------------

    fun observeSchedule(workerId: String): Flow<List<RefresherScheduleEntity>> =
        schedule.observeForWorker(workerId)

    suspend fun dueNow(): List<RefresherScheduleEntity> = schedule.due(clock.epochSeconds())

    /** Earliest upcoming refresher across every worker on this handset. Drives the next alarm. */
    suspend fun earliestDue(): RefresherScheduleEntity? = schedule.earliest()

    suspend fun markNotified(workerId: String, moduleId: String) =
        schedule.markNotified(workerId, moduleId, clock.epochSeconds())

    suspend fun overdueProgress(): List<TrainingProgressEntity> =
        progress.due(clock.epochSeconds())

    /**
     * Site-wide readiness, computed across every worker held on this handset.
     *
     * Deliberately available offline: a supervisor standing at a portal with no signal can still see
     * who on their shift is stale, which is the moment the answer is actually useful.
     */
    suspend fun siteReadinessSummary(workerIds: Collection<String>): ReadinessSummary {
        val nowSec = clock.epochSeconds()
        var ready = 0
        var due = 0
        var stale = 0
        var expired = 0
        var neverCertified = 0
        var validButStale = 0

        for (workerId in workerIds) {
            val rows = progress.forWorker(workerId).associateBy { it.moduleId }
            for (module in ModuleCatalog.all) {
                val assessment = ValidityEvaluator.evaluate(
                    rows[module.moduleId]?.toRetentionState(),
                    nowSec,
                )
                if (assessment.requiredAction == RequiredAction.NEVER_CERTIFIED) {
                    neverCertified++
                    continue
                }
                when (assessment.band) {
                    ReadinessBand.READY -> ready++
                    ReadinessBand.DUE -> due++
                    ReadinessBand.STALE -> stale++
                    ReadinessBand.EXPIRED -> expired++
                }
                if (assessment.statutorilyValidButStale) validButStale++
            }
        }

        return ReadinessSummary(
            ready = ready,
            due = due,
            stale = stale,
            expired = expired,
            neverCertified = neverCertified,
            statutorilyValidButStale = validButStale,
        )
    }

    data class ReadinessSummary(
        val ready: Int,
        val due: Int,
        val stale: Int,
        val expired: Int,
        val neverCertified: Int,
        /** The cohort a site officer should look at first. */
        val statutorilyValidButStale: Int,
    ) {
        val total: Int get() = ready + due + stale + expired + neverCertified

        val readyPercent: Double
            get() = if (total == 0) 0.0 else ready * 100.0 / total
    }

    /** Days until this worker's readiness on a module drops below the DUE threshold. */
    suspend fun daysUntilDue(workerId: String, moduleId: String): Double? {
        val state = retentionState(workerId, moduleId) ?: return null
        return ReadinessCalculator.daysUntilReadinessFallsTo(
            state = state,
            nowEpochSec = clock.epochSeconds(),
            targetPermille = ReadinessCalculator.DUE_THRESHOLD,
        )
    }
}

/**
 * Bridges the stored row to the pure model in `:core`.
 *
 * Kept as an extension rather than a method on the entity so the Room entity stays a plain data
 * holder and the retention arithmetic stays testable without Android.
 */
internal fun TrainingProgressEntity.toRetentionState(): RetentionState = RetentionState(
    baseScore = baseScore.coerceIn(0, 1000),
    lastPassAtEpochSec = lastPassAtSec.coerceAtLeast(0L),
    refresherStage = refresherStage.coerceAtLeast(0),
    nextDueAtEpochSec = nextDueAtSec.coerceAtLeast(0L),
    consecutiveFailures = consecutiveFailures.coerceAtLeast(0),
    // A row written before a pass has a zero certifiedAt. Falling back to lastPass keeps the
    // statutory arithmetic on a real date instead of 1970, which would report every untouched
    // module as decades expired.
    certifiedAtEpochSec = if (certifiedAtSec > 0L) certifiedAtSec else lastPassAtSec.coerceAtLeast(0L),
)
