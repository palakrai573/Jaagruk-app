package org.jaagruk.core.retention

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Operational readiness band.
 *
 * Separate from statutory validity on purpose. A certificate issued eleven months ago is
 * still legally current under the Mines Act; whether the worker would still act correctly in
 * a gas leak is a different question, and it is the question the problem statement is
 * actually complaining about when it cites sub-20 % retention after one week.
 */
enum class ReadinessBand {
    /** Recent and reliable. */
    READY,

    /** Slipping. A short refresher restores it. */
    DUE,

    /** Substantially decayed. Refresher required before further hazardous work. */
    STALE,

    /** Effectively lost. The full module must be re-run; a refresher will not do. */
    EXPIRED,
    ;

    val needsAction: Boolean get() = this != READY
    val refresherIsEnough: Boolean get() = this == DUE || this == STALE
}

/**
 * Everything needed to compute a worker's readiness for one module.
 *
 * Stored per (worker, module) and deliberately small: five numbers, no history table. The
 * whole retention model is arithmetic over these, which is why a phone that has been switched
 * off for six weeks reports the correct readiness the instant it boots, with no server, no
 * background job having run, and no notification having been delivered.
 */
class RetentionState(
    /** Score the last successful pass consolidated to, in permille. */
    val baseScore: Int,
    val lastPassAtEpochSec: Long,
    /** Index into [SpacedRepetitionScheduler.STAGE_INTERVALS_DAYS]. */
    val refresherStage: Int,
    val nextDueAtEpochSec: Long,
    val consecutiveFailures: Int = 0,
    /** Wall-clock date the statutory certificate was issued. */
    val certifiedAtEpochSec: Long = lastPassAtEpochSec,
) {
    init {
        require(baseScore in 0..1000) { "baseScore must be 0..1000 permille, got $baseScore" }
        require(lastPassAtEpochSec >= 0) { "lastPassAtEpochSec must be >= 0, got $lastPassAtEpochSec" }
        require(refresherStage >= 0) { "refresherStage must be >= 0, got $refresherStage" }
        require(nextDueAtEpochSec >= 0) { "nextDueAtEpochSec must be >= 0, got $nextDueAtEpochSec" }
        require(consecutiveFailures >= 0) {
            "consecutiveFailures must be >= 0, got $consecutiveFailures"
        }
        require(certifiedAtEpochSec >= 0) {
            "certifiedAtEpochSec must be >= 0, got $certifiedAtEpochSec"
        }
    }

    val effectiveStage: Int
        get() = refresherStage.coerceAtMost(SpacedRepetitionScheduler.MAX_STAGE)

    fun copy(
        baseScore: Int = this.baseScore,
        lastPassAtEpochSec: Long = this.lastPassAtEpochSec,
        refresherStage: Int = this.refresherStage,
        nextDueAtEpochSec: Long = this.nextDueAtEpochSec,
        consecutiveFailures: Int = this.consecutiveFailures,
        certifiedAtEpochSec: Long = this.certifiedAtEpochSec,
    ): RetentionState = RetentionState(
        baseScore = baseScore,
        lastPassAtEpochSec = lastPassAtEpochSec,
        refresherStage = refresherStage,
        nextDueAtEpochSec = nextDueAtEpochSec,
        consecutiveFailures = consecutiveFailures,
        certifiedAtEpochSec = certifiedAtEpochSec,
    )

    override fun equals(other: Any?): Boolean =
        other is RetentionState &&
            baseScore == other.baseScore &&
            lastPassAtEpochSec == other.lastPassAtEpochSec &&
            refresherStage == other.refresherStage &&
            nextDueAtEpochSec == other.nextDueAtEpochSec &&
            consecutiveFailures == other.consecutiveFailures &&
            certifiedAtEpochSec == other.certifiedAtEpochSec

    override fun hashCode(): Int {
        var r = baseScore
        r = 31 * r + lastPassAtEpochSec.hashCode()
        r = 31 * r + refresherStage
        r = 31 * r + nextDueAtEpochSec.hashCode()
        r = 31 * r + consecutiveFailures
        r = 31 * r + certifiedAtEpochSec.hashCode()
        return r
    }

    override fun toString(): String =
        "RetentionState(base=$baseScore, stage=$refresherStage, lastPass=$lastPassAtEpochSec, " +
            "due=$nextDueAtEpochSec, fails=$consecutiveFailures)"
}

/**
 * Forgetting-curve arithmetic.
 *
 * Exponential decay with a half-life that lengthens each time the worker successfully
 * recalls the material. That shape is the actual finding behind spaced repetition — each
 * successful retrieval flattens the curve — and it is what makes a decaying score more
 * honest than a "certified on 4 March" stamp that says nothing about today.
 *
 * Readiness is always **computed on read**, never stored. Nothing has to run on schedule for
 * the number to be right, which matters on devices that spend shifts underground with the
 * radio off.
 */
object ReadinessCalculator {

    /** Half-life for a worker who has passed once and done no refreshers. */
    const val INITIAL_HALF_LIFE_DAYS: Double = 45.0

    /** Each completed refresher stage extends the half-life by this fraction. */
    const val HALF_LIFE_GROWTH_PER_STAGE: Double = 0.5

    /** Ceiling, so the model never claims a skill is effectively permanent. */
    const val MAX_HALF_LIFE_DAYS: Double = 180.0

    const val READY_THRESHOLD: Int = 700
    const val DUE_THRESHOLD: Int = 500
    const val STALE_THRESHOLD: Int = 300

    const val SECONDS_PER_DAY: Long = 86_400L

    fun halfLifeDays(refresherStage: Int): Double {
        require(refresherStage >= 0) { "refresherStage must be >= 0, got $refresherStage" }
        val grown = INITIAL_HALF_LIFE_DAYS * (1.0 + HALF_LIFE_GROWTH_PER_STAGE * refresherStage)
        return grown.coerceAtMost(MAX_HALF_LIFE_DAYS)
    }

    /**
     * @return readiness in permille, 0..1000.
     *
     * A [lastPassAtEpochSec] in the future — routine when a device's clock is wrong or a
     * record synced from a phone whose clock was ahead — is clamped to "now" rather than
     * producing a readiness above the base score.
     */
    fun readiness(
        baseScore: Int,
        lastPassAtEpochSec: Long,
        nowEpochSec: Long,
        refresherStage: Int,
    ): Int {
        require(baseScore in 0..1000) { "baseScore must be 0..1000, got $baseScore" }
        if (baseScore == 0) return 0

        val elapsedSeconds = (nowEpochSec - lastPassAtEpochSec).coerceAtLeast(0L)
        val elapsedDays = elapsedSeconds.toDouble() / SECONDS_PER_DAY.toDouble()
        val halfLife = halfLifeDays(refresherStage)

        val decayed = baseScore.toDouble() * 0.5.pow(elapsedDays / halfLife)
        return decayed.roundToInt().coerceIn(0, 1000)
    }

    fun readiness(state: RetentionState, nowEpochSec: Long): Int = readiness(
        baseScore = state.baseScore,
        lastPassAtEpochSec = state.lastPassAtEpochSec,
        nowEpochSec = nowEpochSec,
        refresherStage = state.effectiveStage,
    )

    fun band(readinessPermille: Int): ReadinessBand = when {
        readinessPermille >= READY_THRESHOLD -> ReadinessBand.READY
        readinessPermille >= DUE_THRESHOLD -> ReadinessBand.DUE
        readinessPermille >= STALE_THRESHOLD -> ReadinessBand.STALE
        else -> ReadinessBand.EXPIRED
    }

    fun band(state: RetentionState, nowEpochSec: Long): ReadinessBand =
        band(readiness(state, nowEpochSec))

    /**
     * Consolidates a new pass into the stored base score.
     *
     * Moves halfway toward full retention, and never below the score just achieved. A strong
     * worker's base does not fall because one refresher was merely good, and a weak first
     * attempt does not permanently cap them.
     */
    fun consolidate(currentBase: Int, achievedScore: Int): Int {
        require(currentBase in 0..1000) { "currentBase must be 0..1000, got $currentBase" }
        require(achievedScore in 0..1000) { "achievedScore must be 0..1000, got $achievedScore" }
        val consolidated = currentBase + ((1000 - currentBase) * 0.5).roundToInt()
        return maxOf(consolidated, achievedScore).coerceIn(0, 1000)
    }

    /**
     * Days until readiness will fall to [targetPermille]. Null when it never will, or already has.
     * Used to tell a site officer *when* a cohort goes stale, not just that it eventually does.
     */
    fun daysUntilReadinessFallsTo(
        state: RetentionState,
        nowEpochSec: Long,
        targetPermille: Int,
    ): Double? {
        require(targetPermille in 0..1000) { "targetPermille must be 0..1000, got $targetPermille" }
        if (targetPermille <= 0 || state.baseScore <= 0) return null
        val current = readiness(state, nowEpochSec)
        if (current <= targetPermille) return 0.0

        val halfLife = halfLifeDays(state.effectiveStage)
        // current * 0.5^(d / halfLife) = target  ->  d = halfLife * log2(current / target)
        val ratio = current.toDouble() / targetPermille.toDouble()
        val halvings = kotlin.math.ln(ratio) / kotlin.math.ln(2.0)
        return halfLife * halvings
    }
}
