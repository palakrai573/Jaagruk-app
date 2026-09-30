package org.jaagruk.core.assessment

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnswerMatcherTest {

    @Test
    fun `single choice needs the exact option`() {
        val step = TestScenarios.singleChoice("s")
        assertThat(AnswerMatcher.matches(step, listOf("s_right"))).isTrue()
        assertThat(AnswerMatcher.matches(step, listOf("s_wrong"))).isFalse()
    }

    @Test
    fun `multi select gives no partial credit`() {
        // Four of the five required PPE items for a confined-space entry is not 80 % safe; it is
        // an entry that should not happen. So partial selections are simply wrong.
        val step = TestScenarios.multiSelect("m")
        assertThat(AnswerMatcher.matches(step, listOf("m_a", "m_b"))).isTrue()
        assertThat(AnswerMatcher.matches(step, listOf("m_b", "m_a"))).isTrue()
        assertThat(AnswerMatcher.matches(step, listOf("m_a"))).isFalse()
        assertThat(AnswerMatcher.matches(step, listOf("m_a", "m_b", "m_c"))).isFalse()
    }

    @Test
    fun `sequence is order sensitive`() {
        // Energising before lockout is its own accident, so right items in the wrong order fails.
        val step = TestScenarios.sequence("q")
        assertThat(AnswerMatcher.matches(step, listOf("q_1", "q_2", "q_3"))).isTrue()
        assertThat(AnswerMatcher.matches(step, listOf("q_2", "q_1", "q_3"))).isFalse()
        assertThat(AnswerMatcher.matches(step, listOf("q_1", "q_2"))).isFalse()
    }

    @Test
    fun `rejects an empty answer`() {
        assertThat(AnswerMatcher.matches(TestScenarios.singleChoice("s"), emptyList())).isFalse()
    }

    @Test
    fun `rejects duplicated selections`() {
        val step = TestScenarios.multiSelect("m")
        assertThat(AnswerMatcher.matches(step, listOf("m_a", "m_a", "m_b"))).isFalse()
    }

    @Test
    fun `rejects an option that does not belong to the step`() {
        val step = TestScenarios.singleChoice("s")
        assertThat(AnswerMatcher.matches(step, listOf("some_other_option"))).isFalse()
        assertThat(AnswerMatcher.matches(step, listOf("s_right", "injected"))).isFalse()
    }
}

class ScoreCalculatorLatencyTest {

    @Test
    fun `full marks at or under the expert baseline`() {
        assertThat(ScoreCalculator.latencyFactor(0L, 1_000L, 5_000L)).isEqualTo(1.0)
        assertThat(ScoreCalculator.latencyFactor(999L, 1_000L, 5_000L)).isEqualTo(1.0)
        assertThat(ScoreCalculator.latencyFactor(1_000L, 1_000L, 5_000L)).isEqualTo(1.0)
    }

    @Test
    fun `zero marks at or past the timeout`() {
        assertThat(ScoreCalculator.latencyFactor(5_000L, 1_000L, 5_000L)).isEqualTo(0.0)
        assertThat(ScoreCalculator.latencyFactor(9_999L, 1_000L, 5_000L)).isEqualTo(0.0)
    }

    @Test
    fun `interpolates linearly between baseline and timeout`() {
        // Halfway through the window is half the speed marks: explainable to a worker with no
        // formal schooling and to an inspector reading a printed report.
        assertThat(ScoreCalculator.latencyFactor(3_000L, 1_000L, 5_000L)).isWithin(1e-9).of(0.5)
        assertThat(ScoreCalculator.latencyFactor(2_000L, 1_000L, 5_000L)).isWithin(1e-9).of(0.75)
        assertThat(ScoreCalculator.latencyFactor(4_000L, 1_000L, 5_000L)).isWithin(1e-9).of(0.25)
    }

    @Test
    fun `rejects an impossible window`() {
        assertThrows<IllegalArgumentException> { ScoreCalculator.latencyFactor(1L, 0L, 5L) }
        assertThrows<IllegalArgumentException> { ScoreCalculator.latencyFactor(1L, 5_000L, 5_000L) }
        assertThrows<IllegalArgumentException> { ScoreCalculator.latencyFactor(1L, 6_000L, 5_000L) }
    }

    @Test
    fun `a correct answer never scores below the accuracy floor`() {
        for (latency in listOf(0L, 1_000L, 2_500L, 4_999L, 5_000L, 50_000L)) {
            val score = ScoreCalculator.stepScore(true, latency, 1_000L, 5_000L)
            assertThat(score).isAtLeast(AssessmentConfig.ACCURACY_WEIGHT)
            assertThat(score).isAtMost(1.0)
        }
    }

    @Test
    fun `a wrong answer scores zero however fast`() {
        assertThat(ScoreCalculator.stepScore(false, 0L, 1_000L, 5_000L)).isEqualTo(0.0)
        assertThat(ScoreCalculator.stepScore(false, 4_999L, 1_000L, 5_000L)).isEqualTo(0.0)
    }

    @Test
    fun `step score matches the documented formula`() {
        // 0.70 + 0.30 * 0.5
        assertThat(ScoreCalculator.stepScore(true, 3_000L, 1_000L, 5_000L))
            .isWithin(1e-9).of(0.85)
    }
}

class ScoreCalculatorClassificationTest {

    @Test
    fun `correct within twice the baseline is fast`() {
        assertThat(ScoreCalculator.classify(true, true, 1_000L, 1_000L))
            .isEqualTo(OutcomeClass.CORRECT_FAST)
        assertThat(ScoreCalculator.classify(true, true, 2_000L, 1_000L))
            .isEqualTo(OutcomeClass.CORRECT_FAST)
    }

    @Test
    fun `correct past twice the baseline is hesitation`() {
        // The distinction the whole platform exists to record: knowing the answer is not the same
        // as acting on it in time.
        assertThat(ScoreCalculator.classify(true, true, 2_001L, 1_000L))
            .isEqualTo(OutcomeClass.CORRECT_SLOW)
        assertThat(ScoreCalculator.classify(true, true, 4_500L, 1_000L))
            .isEqualTo(OutcomeClass.CORRECT_SLOW)
    }

    @Test
    fun `wrong answers are incorrect and non answers are timeouts`() {
        assertThat(ScoreCalculator.classify(false, true, 500L, 1_000L))
            .isEqualTo(OutcomeClass.INCORRECT)
        assertThat(ScoreCalculator.classify(false, false, 5_000L, 1_000L))
            .isEqualTo(OutcomeClass.TIMEOUT)
        assertThat(ScoreCalculator.classify(true, false, 5_000L, 1_000L))
            .isEqualTo(OutcomeClass.TIMEOUT)
    }

    @Test
    fun `outcome class properties are consistent`() {
        assertThat(OutcomeClass.CORRECT_FAST.isCorrect).isTrue()
        assertThat(OutcomeClass.CORRECT_SLOW.isCorrect).isTrue()
        assertThat(OutcomeClass.INCORRECT.isCorrect).isFalse()
        assertThat(OutcomeClass.TIMEOUT.wasAnswered).isFalse()
        assertThat(OutcomeClass.SKIPPED.isScored).isFalse()
        assertThat(OutcomeClass.TIMEOUT.isScored).isTrue()
    }
}

class ScoreAggregationTest {

    private fun result(
        stepId: String,
        outcome: OutcomeClass,
        latencyMs: Long,
        expertMs: Long = 1_000L,
        timeoutMs: Long = 5_000L,
        weight: Double = 1.0,
        critical: Boolean = false,
    ): StepResult = StepResult(
        stepId = stepId,
        stepIndex = 0,
        kind = StepKind.SINGLE_CHOICE,
        outcome = outcome,
        latencyMs = latencyMs,
        expertMs = expertMs,
        timeoutMs = timeoutMs,
        answeredOptionIds = if (outcome.wasAnswered) listOf("x") else emptyList(),
        correctOptionIds = listOf("x"),
        stepScore = ScoreCalculator.stepScore(outcome.isCorrect, latencyMs, expertMs, timeoutMs),
        weight = weight,
        critical = critical,
        inputMethod = if (outcome.wasAnswered) InputMethod.TOUCH else InputMethod.AUTO_TIMEOUT,
        suspiciousFast = false,
    )

    @Test
    fun `all fast and correct scores a perfect thousand`() {
        val aggregate = ScoreCalculator.aggregate(
            listOf(
                result("a", OutcomeClass.CORRECT_FAST, 500L),
                result("b", OutcomeClass.CORRECT_FAST, 800L),
            ),
            AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
        )
        assertThat(aggregate.scorePermille).isEqualTo(1_000)
        assertThat(aggregate.hesitationFlag).isFalse()
    }

    @Test
    fun `all wrong scores zero`() {
        val aggregate = ScoreCalculator.aggregate(
            listOf(
                result("a", OutcomeClass.INCORRECT, 500L),
                result("b", OutcomeClass.TIMEOUT, 5_000L),
            ),
            AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
        )
        assertThat(aggregate.scorePermille).isEqualTo(0)
        // Zero correct answers must mean zero hesitation, not a division by zero.
        assertThat(aggregate.hesitationRatio).isEqualTo(0.0)
        assertThat(aggregate.hesitationFlag).isFalse()
    }

    @Test
    fun `weights are respected`() {
        // A heavy correct step and a light wrong step: 2.0/3.0 of full marks.
        val aggregate = ScoreCalculator.aggregate(
            listOf(
                result("heavy", OutcomeClass.CORRECT_FAST, 500L, weight = 2.0),
                result("light", OutcomeClass.INCORRECT, 500L, weight = 1.0),
            ),
            AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
        )
        assertThat(aggregate.scorePermille).isEqualTo(667)
    }

    @Test
    fun `skipped steps leave the denominator alone`() {
        // A radio dropout must not punish a worker for steps they never saw.
        val aggregate = ScoreCalculator.aggregate(
            listOf(
                result("a", OutcomeClass.CORRECT_FAST, 500L),
                result("b", OutcomeClass.SKIPPED, 0L),
                result("c", OutcomeClass.SKIPPED, 0L),
            ),
            AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
        )
        assertThat(aggregate.scorePermille).isEqualTo(1_000)
        assertThat(aggregate.scoredStepCount).isEqualTo(1)
    }

    @Test
    fun `every step skipped scores zero without dividing by zero`() {
        val aggregate = ScoreCalculator.aggregate(
            listOf(result("a", OutcomeClass.SKIPPED, 0L)),
            AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
        )
        assertThat(aggregate.scorePermille).isEqualTo(0)
        assertThat(aggregate.scoredStepCount).isEqualTo(0)
    }

    @Test
    fun `an empty result list is handled`() {
        val aggregate = ScoreCalculator.aggregate(emptyList(), 0.34)
        assertThat(aggregate.scorePermille).isEqualTo(0)
        assertThat(aggregate.medianLatencyMs).isEqualTo(0L)
        assertThat(aggregate.failedCriticalStepIds).isEmpty()
    }

    @Test
    fun `hesitation ratio counts slow correct answers over all correct answers`() {
        val aggregate = ScoreCalculator.aggregate(
            listOf(
                result("a", OutcomeClass.CORRECT_FAST, 500L),
                result("b", OutcomeClass.CORRECT_SLOW, 3_000L),
                result("c", OutcomeClass.CORRECT_SLOW, 3_500L),
                result("d", OutcomeClass.INCORRECT, 800L),
            ),
            AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
        )
        assertThat(aggregate.correctCount).isEqualTo(3)
        assertThat(aggregate.hesitantCount).isEqualTo(2)
        assertThat(aggregate.hesitationRatio).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(aggregate.hesitationFlag).isTrue()
    }

    @Test
    fun `hesitation flag respects the configured limit`() {
        val steps = listOf(
            result("a", OutcomeClass.CORRECT_FAST, 500L),
            result("b", OutcomeClass.CORRECT_FAST, 600L),
            result("c", OutcomeClass.CORRECT_SLOW, 3_000L),
        )
        // 1/3 = 0.333, just under the 0.34 default.
        assertThat(ScoreCalculator.aggregate(steps, 0.34).hesitationFlag).isFalse()
        assertThat(ScoreCalculator.aggregate(steps, 0.30).hesitationFlag).isTrue()
    }

    @Test
    fun `critical failures are collected`() {
        val aggregate = ScoreCalculator.aggregate(
            listOf(
                result("safe", OutcomeClass.CORRECT_FAST, 500L),
                result("extinguisher", OutcomeClass.INCORRECT, 900L, critical = true),
                result("exit", OutcomeClass.TIMEOUT, 5_000L, critical = true),
            ),
            AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
        )
        assertThat(aggregate.failedCriticalStepIds).containsExactly("extinguisher", "exit")
    }

    @Test
    fun `median of an odd count is the middle sample`() {
        val median = ScoreCalculator.medianLatency(
            listOf(
                result("a", OutcomeClass.CORRECT_FAST, 100L),
                result("b", OutcomeClass.CORRECT_FAST, 900L),
                result("c", OutcomeClass.CORRECT_FAST, 500L),
            ),
        )
        assertThat(median).isEqualTo(500L)
    }

    @Test
    fun `median of an even count rounds the midpoint up`() {
        val median = ScoreCalculator.medianLatency(
            listOf(
                result("a", OutcomeClass.CORRECT_FAST, 100L),
                result("b", OutcomeClass.CORRECT_FAST, 201L),
            ),
        )
        assertThat(median).isEqualTo(151L)
    }

    @Test
    fun `median counts a timeout at its full window`() {
        // Excluding timeouts would let someone who answered two steps quickly and timed out on
        // four report a fast median, which is the opposite of the truth.
        val median = ScoreCalculator.medianLatency(
            listOf(
                result("a", OutcomeClass.CORRECT_FAST, 200L),
                result("b", OutcomeClass.TIMEOUT, 0L, timeoutMs = 5_000L),
                result("c", OutcomeClass.TIMEOUT, 0L, timeoutMs = 5_000L),
            ),
        )
        assertThat(median).isEqualTo(5_000L)
    }

    @Test
    fun `median ignores skipped steps and handles an empty list`() {
        assertThat(
            ScoreCalculator.medianLatency(listOf(result("a", OutcomeClass.SKIPPED, 0L))),
        ).isEqualTo(0L)
        assertThat(ScoreCalculator.medianLatency(emptyList())).isEqualTo(0L)
    }
}

class PassRuleTest {

    private val scenario = TestScenarios.threeStep()

    private fun aggregate(
        scorePermille: Int,
        hesitationRatio: Double = 0.0,
        criticalFailures: List<String> = emptyList(),
        hesitationLimit: Double = AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
    ): ScoreAggregate = ScoreAggregate(
        scorePermille = scorePermille,
        hesitationRatio = hesitationRatio,
        hesitationFlag = hesitationRatio > hesitationLimit,
        medianLatencyMs = 1_500L,
        scoredStepCount = 3,
        correctCount = 3,
        hesitantCount = 0,
        failedCriticalStepIds = criticalFailures,
    )

    @Test
    fun `passes when all three conditions hold`() {
        val evaluation = ScoreCalculator.evaluatePass(aggregate(820), scenario)
        assertThat(evaluation.passed).isTrue()
        assertThat(evaluation.failures).isEmpty()
    }

    @Test
    fun `fails below the score threshold`() {
        val evaluation = ScoreCalculator.evaluatePass(aggregate(699), scenario)
        assertThat(evaluation.passed).isFalse()
        assertThat(evaluation.failures.filterIsInstance<PassFailure.BelowThreshold>()).hasSize(1)
    }

    @Test
    fun `a failed critical step fails a high scoring run`() {
        // 92 % overall while having chosen a water extinguisher for an electrical fire is not a
        // pass in a real mine, whatever the average says.
        val evaluation = ScoreCalculator.evaluatePass(
            aggregate(920, criticalFailures = listOf("fire_pick_extinguisher")),
            scenario,
        )
        assertThat(evaluation.passed).isFalse()
        val failure = evaluation.failures.filterIsInstance<PassFailure.CriticalStepFailed>().single()
        assertThat(failure.stepIds).containsExactly("fire_pick_extinguisher")
    }

    @Test
    fun `excessive hesitation fails an otherwise correct run`() {
        val evaluation = ScoreCalculator.evaluatePass(aggregate(780, hesitationRatio = 0.8), scenario)
        assertThat(evaluation.passed).isFalse()
        val failure = evaluation.failures.filterIsInstance<PassFailure.TooHesitant>().single()
        assertThat(failure.ratio).isWithin(1e-9).of(0.8)
        assertThat(failure.limit).isWithin(1e-9).of(scenario.hesitationRatioLimit)
    }

    @Test
    fun `reports every reason at once`() {
        val evaluation = ScoreCalculator.evaluatePass(
            aggregate(400, hesitationRatio = 0.9, criticalFailures = listOf("s1")),
            scenario,
        )
        assertThat(evaluation.failures).hasSize(3)
    }

    @Test
    fun `pass evaluation cannot be internally inconsistent`() {
        assertThrows<IllegalArgumentException> {
            PassEvaluation(passed = true, failures = listOf(PassFailure.BelowThreshold(1, 2)))
        }
        assertThrows<IllegalArgumentException> {
            PassEvaluation(passed = false, failures = emptyList())
        }
    }
}
