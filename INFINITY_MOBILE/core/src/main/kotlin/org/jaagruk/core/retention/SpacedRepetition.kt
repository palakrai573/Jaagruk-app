package org.jaagruk.core.retention

/**
 * Schedules short AR refreshers at expanding intervals.
 *
 * The problem statement's own number — retention below 20 % one week after classroom
 * training — is a scheduling problem, not a content problem. Making the first exposure more
 * engaging (which is all a one-shot AR module does) does not move it. Repeated retrieval at
 * expanding intervals does.
 *
 * Everything here is arithmetic over stored timestamps. Nothing depends on a background job
 * having run or a notification having been delivered, so a missed notification only delays a
 * prompt; it cannot corrupt the schedule. On a device that was off for a month, the correct
 * next-due time is derived the moment it powers on.
 */
object SpacedRepetitionScheduler {

    /**
     * Days after the last pass at which each refresher falls due.
     *
     * Front-loaded because the steepest part of the forgetting curve is the first 48 hours;
     * the day-2 check is doing most of the work.
     */
    val STAGE_INTERVALS_DAYS: IntArray = intArrayOf(2, 7, 21, 60, 120)

    val MAX_STAGE: Int = STAGE_INTERVALS_DAYS.size - 1

    /** Retry delay after a failed refresher. */
    const val FAILURE_RETRY_DAYS: Int = 1

    /**
     * Consecutive failures after which a refresher is no longer enough and the full module
     * must be re-run. Three failed short checks means the material is genuinely gone.
     */
    const val FAILURES_BEFORE_FULL_RERUN: Int = 3

    const val SECONDS_PER_DAY: Long = 86_400L

    fun intervalDays(stage: Int): Int {
        require(stage >= 0) { "stage must be >= 0, got $stage" }
        return STAGE_INTERVALS_DAYS[stage.coerceAtMost(MAX_STAGE)]
    }

    fun nextDueEpochSec(lastPassAtEpochSec: Long, stage: Int): Long {
        require(lastPassAtEpochSec >= 0) {
            "lastPassAtEpochSec must be >= 0, got $lastPassAtEpochSec"
        }
        return lastPassAtEpochSec + intervalDays(stage) * SECONDS_PER_DAY
    }

    /** State after a worker's first successful certification of a module. */
    fun onInitialPass(achievedScore: Int, nowEpochSec: Long): RetentionState {
        require(achievedScore in 0..1000) { "achievedScore must be 0..1000, got $achievedScore" }
        return RetentionState(
            baseScore = achievedScore,
            lastPassAtEpochSec = nowEpochSec,
            refresherStage = 0,
            nextDueAtEpochSec = nextDueEpochSec(nowEpochSec, 0),
            consecutiveFailures = 0,
            certifiedAtEpochSec = nowEpochSec,
        )
    }

    /**
     * Advances the schedule after a passed refresher: base consolidates upward, the stage
     * advances, and the next interval lengthens. At [MAX_STAGE] it stays there and repeats
     * every 120 days, which becomes the ongoing periodic check the Mines Act expects.
     *
     * [certifiedAtEpochSec] is intentionally **not** advanced. A refresher maintains
     * operational readiness; it does not silently renew a statutory certificate. Only a full
     * module re-run does that, and conflating the two is precisely the sloppiness the problem
     * statement objects to.
     */
    fun onRefresherPassed(
        state: RetentionState,
        achievedScore: Int,
        nowEpochSec: Long,
    ): RetentionState {
        require(achievedScore in 0..1000) { "achievedScore must be 0..1000, got $achievedScore" }
        val newStage = (state.refresherStage + 1).coerceAtMost(MAX_STAGE)
        return state.copy(
            baseScore = ReadinessCalculator.consolidate(state.baseScore, achievedScore),
            lastPassAtEpochSec = nowEpochSec,
            refresherStage = newStage,
            nextDueAtEpochSec = nextDueEpochSec(nowEpochSec, newStage),
            consecutiveFailures = 0,
        )
    }

    /**
     * Drops back one stage and retries tomorrow.
     *
     * [RetentionState.lastPassAtEpochSec] is not touched, so readiness keeps decaying from the
     * last genuine pass. A failed attempt must never look like progress.
     */
    fun onRefresherFailed(state: RetentionState, nowEpochSec: Long): RetentionState {
        val newStage = (state.refresherStage - 1).coerceAtLeast(0)
        return state.copy(
            refresherStage = newStage,
            nextDueAtEpochSec = nowEpochSec + FAILURE_RETRY_DAYS * SECONDS_PER_DAY,
            consecutiveFailures = state.consecutiveFailures + 1,
        )
    }

    /** State after re-running the full module — resets the statutory clock too. */
    fun onFullRerunPassed(
        state: RetentionState,
        achievedScore: Int,
        nowEpochSec: Long,
    ): RetentionState = RetentionState(
        baseScore = ReadinessCalculator.consolidate(state.baseScore, achievedScore),
        lastPassAtEpochSec = nowEpochSec,
        refresherStage = 0,
        nextDueAtEpochSec = nextDueEpochSec(nowEpochSec, 0),
        consecutiveFailures = 0,
        certifiedAtEpochSec = nowEpochSec,
    )

    fun isDue(state: RetentionState, nowEpochSec: Long): Boolean =
        nowEpochSec >= state.nextDueAtEpochSec

    fun secondsUntilDue(state: RetentionState, nowEpochSec: Long): Long =
        (state.nextDueAtEpochSec - nowEpochSec).coerceAtLeast(0L)

    /**
     * Whether a short refresher still suffices, or the whole module must be redone.
     *
     * A refresher is a *check* on retained knowledge. Once readiness has decayed past
     * [ReadinessBand.EXPIRED], or three consecutive refreshers have failed, there is nothing
     * left to check and re-running the module is the only honest option.
     */
    fun refresherIsSufficient(state: RetentionState, nowEpochSec: Long): Boolean {
        if (state.consecutiveFailures >= FAILURES_BEFORE_FULL_RERUN) return false
        return ReadinessCalculator.band(state, nowEpochSec) != ReadinessBand.EXPIRED
    }
}

/** What kind of training a worker owes on a module right now. */
enum class RequiredAction {
    NONE,
    REFRESHER_DUE,
    FULL_RERUN_REQUIRED,
    NEVER_CERTIFIED,
    ;

    val blocksHazardousWork: Boolean
        get() = this == FULL_RERUN_REQUIRED || this == NEVER_CERTIFIED
}

/**
 * Combined statutory + operational verdict for one worker on one module.
 *
 * Both dimensions are reported, never merged. The most dangerous cohort on a site is the one
 * that is statutorily valid and operationally stale — legally clear to work, practically
 * unprepared — and merging the two numbers would hide exactly that group.
 */
class ValidityAssessment(
    val statutoryValid: Boolean,
    val statutoryExpiryEpochSec: Long,
    val daysUntilStatutoryExpiry: Long,
    val readinessPermille: Int,
    val band: ReadinessBand,
    val requiredAction: RequiredAction,
    val refresherDue: Boolean,
    val secondsUntilRefresherDue: Long,
) {
    /** The cohort a site officer should look at first. */
    val statutorilyValidButStale: Boolean
        get() = statutoryValid && (band == ReadinessBand.STALE || band == ReadinessBand.EXPIRED)

    val clearedForHazardousWork: Boolean
        get() = statutoryValid && !requiredAction.blocksHazardousWork

    override fun toString(): String =
        "ValidityAssessment(statutory=$statutoryValid, readiness=$readinessPermille/$band, " +
            "action=$requiredAction, expiresInDays=$daysUntilStatutoryExpiry)"
}

/**
 * Applies the statutory clock from the Factories Act 1948 and the Mines Act 1952 alongside the
 * decay model.
 *
 * The statutory side is pure date arithmetic and never affected by decay — an auditor's
 * question is "was this person certified within the last twelve months", and that must be
 * answerable with a yes or a no.
 */
object ValidityEvaluator {

    /** Periodic re-certification window used by both Acts, as applied here. */
    const val STATUTORY_VALIDITY_DAYS: Long = 365L

    const val SECONDS_PER_DAY: Long = 86_400L

    /** Lead time at which a site officer should be warned about upcoming expiry. */
    const val EXPIRY_WARNING_DAYS: Long = 30L

    fun statutoryExpiryEpochSec(certifiedAtEpochSec: Long): Long {
        require(certifiedAtEpochSec >= 0) {
            "certifiedAtEpochSec must be >= 0, got $certifiedAtEpochSec"
        }
        return certifiedAtEpochSec + STATUTORY_VALIDITY_DAYS * SECONDS_PER_DAY
    }

    fun evaluate(state: RetentionState?, nowEpochSec: Long): ValidityAssessment {
        if (state == null) {
            return ValidityAssessment(
                statutoryValid = false,
                statutoryExpiryEpochSec = 0L,
                daysUntilStatutoryExpiry = 0L,
                readinessPermille = 0,
                band = ReadinessBand.EXPIRED,
                requiredAction = RequiredAction.NEVER_CERTIFIED,
                refresherDue = false,
                secondsUntilRefresherDue = 0L,
            )
        }

        val expiry = statutoryExpiryEpochSec(state.certifiedAtEpochSec)
        val statutoryValid = nowEpochSec < expiry
        val daysLeft = ((expiry - nowEpochSec).coerceAtLeast(0L)) / SECONDS_PER_DAY

        val readiness = ReadinessCalculator.readiness(state, nowEpochSec)
        val band = ReadinessCalculator.band(readiness)
        val due = SpacedRepetitionScheduler.isDue(state, nowEpochSec)
        val refresherEnough = SpacedRepetitionScheduler.refresherIsSufficient(state, nowEpochSec)

        val action = when {
            !statutoryValid -> RequiredAction.FULL_RERUN_REQUIRED
            !refresherEnough -> RequiredAction.FULL_RERUN_REQUIRED
            due || band.needsAction -> RequiredAction.REFRESHER_DUE
            else -> RequiredAction.NONE
        }

        return ValidityAssessment(
            statutoryValid = statutoryValid,
            statutoryExpiryEpochSec = expiry,
            daysUntilStatutoryExpiry = daysLeft,
            readinessPermille = readiness,
            band = band,
            requiredAction = action,
            refresherDue = due,
            secondsUntilRefresherDue = SpacedRepetitionScheduler.secondsUntilDue(state, nowEpochSec),
        )
    }

    fun expiringSoon(state: RetentionState, nowEpochSec: Long): Boolean {
        val expiry = statutoryExpiryEpochSec(state.certifiedAtEpochSec)
        val secondsLeft = expiry - nowEpochSec
        return secondsLeft in 0..(EXPIRY_WARNING_DAYS * SECONDS_PER_DAY)
    }
}
