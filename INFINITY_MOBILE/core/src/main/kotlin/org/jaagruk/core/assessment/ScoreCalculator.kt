package org.jaagruk.core.assessment

import kotlin.math.roundToLong

/** Answer comparison. Separated out so the "no partial credit" rule is testable in isolation. */
object AnswerMatcher {

    /**
     * @return true only for an exact match.
     *
     * There is no partial credit anywhere in this platform, and that is a safety decision
     * rather than a strictness preference. Selecting three of the four required PPE items for
     * a confined-space entry is not 75 % safe — it is an entry that should not happen. For
     * [StepKind.SEQUENCE] order is part of the answer, because doing the right things in the
     * wrong order (energise before lockout) is its own accident.
     */
    fun matches(step: StepSpec, answeredOptionIds: List<String>): Boolean {
        if (answeredOptionIds.isEmpty()) return false
        if (answeredOptionIds.distinct().size != answeredOptionIds.size) return false

        // An answer naming an option that does not exist in this step is never correct.
        val known = step.options.map { it.optionId }.toSet()
        if (answeredOptionIds.any { it !in known }) return false

        return if (step.kind.isOrdered) {
            answeredOptionIds == step.correctOptionIds
        } else {
            answeredOptionIds.toSet() == step.correctOptionIds.toSet()
        }
    }
}

/** Aggregated view of a set of step results. */
class ScoreAggregate(
    val scorePermille: Int,
    val hesitationRatio: Double,
    val hesitationFlag: Boolean,
    val medianLatencyMs: Long,
    val scoredStepCount: Int,
    val correctCount: Int,
    val hesitantCount: Int,
    val failedCriticalStepIds: List<String>,
) {
    override fun toString(): String =
        "ScoreAggregate(score=$scorePermille, hesitationRatio=%.3f, flag=$hesitationFlag, median=${medianLatencyMs}ms)"
            .format(hesitationRatio)
}

/**
 * All scoring arithmetic, as pure functions.
 *
 * Kept free of any session state so the exact numbers in `docs/ARCHITECTURE.md` §3.2 can be
 * asserted directly in unit tests, and so a disputed certificate can be recomputed from its
 * stored step results years later and produce the identical score.
 */
object ScoreCalculator {

    /**
     * Speed component, 1.0 at or under the expert baseline, 0.0 at the timeout, linear between.
     *
     * Linear rather than exponential on purpose: the score has to be explainable to a worker
     * with no formal schooling and to a DGMS inspector reading a printed report. "Half way to
     * the time limit means half the speed marks" survives that conversation.
     */
    fun latencyFactor(latencyMs: Long, expertMs: Long, timeoutMs: Long): Double {
        require(expertMs > 0) { "expertMs must be positive, got $expertMs" }
        require(timeoutMs > expertMs) { "timeoutMs ($timeoutMs) must exceed expertMs ($expertMs)" }
        return when {
            latencyMs <= expertMs -> 1.0
            latencyMs >= timeoutMs -> 0.0
            else -> (timeoutMs - latencyMs).toDouble() / (timeoutMs - expertMs).toDouble()
        }
    }

    /** `accuracy * (0.70 + 0.30 * latencyFactor)` — a wrong answer scores zero regardless of speed. */
    fun stepScore(correct: Boolean, latencyMs: Long, expertMs: Long, timeoutMs: Long): Double {
        if (!correct) return 0.0
        val latency = latencyFactor(latencyMs, expertMs, timeoutMs)
        return AssessmentConfig.ACCURACY_WEIGHT + AssessmentConfig.LATENCY_WEIGHT * latency
    }

    fun classify(correct: Boolean, answered: Boolean, latencyMs: Long, expertMs: Long): OutcomeClass {
        if (!answered) return OutcomeClass.TIMEOUT
        if (!correct) return OutcomeClass.INCORRECT
        val slowThreshold = (expertMs * AssessmentConfig.SLOW_FACTOR).toLong()
        return if (latencyMs <= slowThreshold) OutcomeClass.CORRECT_FAST else OutcomeClass.CORRECT_SLOW
    }

    /**
     * Weighted mean of scored steps, in permille.
     *
     * Skipped steps are excluded from both numerator and denominator: an aborted drill reports
     * how the worker did on what they actually attempted, rather than punishing them for a
     * radio dropout. Whether that partial run may certify is a separate decision made by
     * [Completion].
     */
    fun aggregate(steps: List<StepResult>, hesitationRatioLimit: Double): ScoreAggregate {
        require(hesitationRatioLimit in 0.0..1.0) {
            "hesitationRatioLimit must be 0.0..1.0, got $hesitationRatioLimit"
        }

        val scored = steps.filter { it.outcome.isScored }
        val totalWeight = scored.sumOf { it.weight }

        val scorePermille = if (scored.isEmpty() || totalWeight <= 0.0) {
            0
        } else {
            val weighted = scored.sumOf { it.stepScore * it.weight } / totalWeight
            (weighted * 1000.0).roundToLong().coerceIn(0L, 1000L).toInt()
        }

        val correct = scored.count { it.outcome.isCorrect }
        val hesitant = scored.count { it.outcome == OutcomeClass.CORRECT_SLOW }

        // Zero correct answers means zero hesitation by definition, not a division by zero.
        val hesitationRatio = if (correct == 0) 0.0 else hesitant.toDouble() / correct.toDouble()

        return ScoreAggregate(
            scorePermille = scorePermille,
            hesitationRatio = hesitationRatio,
            hesitationFlag = hesitationRatio > hesitationRatioLimit,
            medianLatencyMs = medianLatency(scored),
            scoredStepCount = scored.size,
            correctCount = correct,
            hesitantCount = hesitant,
            failedCriticalStepIds = scored.filter { it.critical && !it.outcome.isCorrect }
                .map { it.stepId },
        )
    }

    /**
     * Median decision time across scored steps.
     *
     * Timeouts are included at their full `timeoutMs`. Excluding them would let a worker who
     * answered two steps quickly and timed out on four report a fast median, which is the
     * opposite of the truth. Median rather than mean so one interruption does not distort the
     * number that gets signed into the certificate.
     */
    fun medianLatency(steps: List<StepResult>): Long {
        val samples = steps
            .filter { it.outcome.isScored }
            .map { if (it.outcome == OutcomeClass.TIMEOUT) it.timeoutMs else it.latencyMs }
            .sorted()

        if (samples.isEmpty()) return 0L
        val mid = samples.size / 2
        return if (samples.size % 2 == 1) {
            samples[mid]
        } else {
            // Integer mean of the two central samples, rounded half-up, overflow-safe.
            val a = samples[mid - 1]
            val b = samples[mid]
            a + (b - a + 1) / 2
        }
    }

    /**
     * The pass rule from `docs/ARCHITECTURE.md` §3.4 — all three conditions must hold.
     *
     * Score alone is not enough. A worker can average 82 % and still have chosen a water
     * extinguisher for an electrical fire, and a worker can be entirely correct yet hesitate
     * on most decisions. Both fail here, for different and explainable reasons.
     */
    fun evaluatePass(aggregate: ScoreAggregate, scenario: ScenarioSpec): PassEvaluation {
        val failures = mutableListOf<PassFailure>()

        if (aggregate.scorePermille < scenario.passThresholdPermille) {
            failures += PassFailure.BelowThreshold(
                aggregate.scorePermille,
                scenario.passThresholdPermille,
            )
        }
        if (aggregate.failedCriticalStepIds.isNotEmpty()) {
            failures += PassFailure.CriticalStepFailed(aggregate.failedCriticalStepIds)
        }
        if (aggregate.hesitationFlag) {
            failures += PassFailure.TooHesitant(
                aggregate.hesitationRatio,
                scenario.hesitationRatioLimit,
            )
        }
        return PassEvaluation(passed = failures.isEmpty(), failures = failures)
    }
}

/** Why a run did not pass, in terms the worker can be shown and the officer can act on. */
sealed interface PassFailure {
    class BelowThreshold(val scorePermille: Int, val requiredPermille: Int) : PassFailure

    class CriticalStepFailed(val stepIds: List<String>) : PassFailure

    class TooHesitant(val ratio: Double, val limit: Double) : PassFailure
}

class PassEvaluation(val passed: Boolean, val failures: List<PassFailure>) {
    init {
        require(passed == failures.isEmpty()) {
            "PassEvaluation is inconsistent: passed=$passed with ${failures.size} failure(s)"
        }
    }
}
