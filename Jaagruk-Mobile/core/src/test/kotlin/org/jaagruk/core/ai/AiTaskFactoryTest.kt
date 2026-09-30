package org.jaagruk.core.ai

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.assessment.AssessmentResult
import org.jaagruk.core.assessment.Completion
import org.jaagruk.core.assessment.InputMethod
import org.jaagruk.core.assessment.OutcomeClass
import org.jaagruk.core.assessment.StepKind
import org.jaagruk.core.assessment.StepResult
import org.junit.jupiter.api.Test

/**
 * The mapping from a finished run to a coaching request.
 *
 * Worth its own tests because it is the boundary the AI layer sits behind: nothing a model produced
 * can enter a task, and a step the worker got right must not produce a paragraph explaining that
 * they got it right.
 */
class AiTaskFactoryTest {

    /** Resolves keys to readable text the way the Android resource layer does. */
    private val resolver = StringResolver { key ->
        when (key) {
            "step_fire_detect_alarm_prompt" -> "Smoke is coming from the panel. What first?"
            "step_fire_pick_extinguisher_prompt" -> "Which extinguisher for a motor control panel?"
            "step_gas_rescue_decision_prompt" -> "Your buddy has collapsed inside the tank."
            "opt_raise_alarm" -> "Raise the alarm"
            "opt_fight_fire_first" -> "Fight the fire first"
            "opt_ext_co2" -> "Carbon dioxide extinguisher"
            "opt_ext_water" -> "Water extinguisher"
            "opt_do_not_enter" -> "Do not enter, raise the alarm"
            "opt_enter_and_pull" -> "Go in and pull them out"
            else -> ""
        }
    }

    private fun step(
        stepId: String,
        index: Int,
        outcome: OutcomeClass,
        answered: List<String>,
        correct: List<String>,
        latencyMs: Long = 2_000,
        expertMs: Long = 3_000,
        critical: Boolean = false,
    ) = StepResult(
        stepId = stepId,
        stepIndex = index,
        kind = StepKind.SINGLE_CHOICE,
        outcome = outcome,
        latencyMs = latencyMs,
        expertMs = expertMs,
        timeoutMs = 12_000,
        answeredOptionIds = answered,
        correctOptionIds = correct,
        stepScore = if (outcome.isCorrect) 1.0 else 0.0,
        weight = 1.0,
        critical = critical,
        inputMethod = if (outcome.wasAnswered) InputMethod.TOUCH else InputMethod.AUTO_TIMEOUT,
        suspiciousFast = false,
    )

    private fun result(steps: List<StepResult>) = AssessmentResult(
        runId = "run-1",
        scenarioId = "fire-evac-full",
        moduleId = "fire-evacuation",
        moduleCode = 1,
        mode = AssessmentMode.INITIAL,
        presentation = ArPresentation.ARCORE_GENERIC,
        completion = Completion.COMPLETED,
        scorePermille = 800,
        passed = true,
        hesitationFlag = false,
        hesitationRatio = 0.0,
        medianLatencyMs = 2_000,
        steps = steps,
        failedCriticalStepIds = emptyList(),
        voidReason = null,
        abortReason = null,
        startedAtEpochSec = 1_760_000_000,
        finishedAtEpochSec = 1_760_000_120,
        totalDurationMs = 120_000,
        buddyPeerDeviceId = null,
    )

    // -----------------------------------------------------------------------
    // What gets coached
    // -----------------------------------------------------------------------

    @Test
    fun `a wrong answer is coached`() {
        val run = result(
            listOf(
                step(
                    "fire_pick_extinguisher", 0, OutcomeClass.INCORRECT,
                    answered = listOf("ext_water"), correct = listOf("ext_co2"),
                ),
            ),
        )
        val task = AiTaskFactory.stepCoaching(run, "fire_pick_extinguisher", AiLanguage.ENGLISH, resolver)!!
        assertThat(task.focus).isEqualTo(CoachingFocus.WRONG_ANSWER)
        assertThat(task.chosenLabels).containsExactly("Water extinguisher")
        assertThat(task.correctLabels).containsExactly("Carbon dioxide extinguisher")
    }

    @Test
    fun `a slow correct answer is coached as hesitation`() {
        val run = result(
            listOf(
                step(
                    "fire_detect_alarm", 0, OutcomeClass.CORRECT_SLOW,
                    answered = listOf("raise_alarm"), correct = listOf("raise_alarm"),
                    latencyMs = 9_000, expertMs = 3_000,
                ),
            ),
        )
        val task = AiTaskFactory.stepCoaching(run, "fire_detect_alarm", AiLanguage.ENGLISH, resolver)!!
        assertThat(task.focus).isEqualTo(CoachingFocus.HESITATED)
        assertThat(task.paceMultiple).isEqualTo(3.0)
    }

    @Test
    fun `a timeout is coached`() {
        val run = result(
            listOf(
                step(
                    "fire_detect_alarm", 0, OutcomeClass.TIMEOUT,
                    answered = emptyList(), correct = listOf("raise_alarm"),
                    latencyMs = 12_000,
                ),
            ),
        )
        val task = AiTaskFactory.stepCoaching(run, "fire_detect_alarm", AiLanguage.ENGLISH, resolver)!!
        assertThat(task.focus).isEqualTo(CoachingFocus.TIMED_OUT)
        assertThat(task.chosenLabels).isEmpty()
    }

    @Test
    fun `a correct fast answer is not coached`() {
        val run = result(
            listOf(
                step(
                    "fire_detect_alarm", 0, OutcomeClass.CORRECT_FAST,
                    answered = listOf("raise_alarm"), correct = listOf("raise_alarm"),
                ),
            ),
        )
        assertThat(AiTaskFactory.stepCoaching(run, "fire_detect_alarm", AiLanguage.ENGLISH, resolver))
            .isNull()
    }

    @Test
    fun `a skipped step is not coached`() {
        val run = result(
            listOf(
                step(
                    "fire_detect_alarm", 0, OutcomeClass.SKIPPED,
                    answered = emptyList(), correct = listOf("raise_alarm"),
                ),
            ),
        )
        assertThat(AiTaskFactory.stepCoaching(run, "fire_detect_alarm", AiLanguage.ENGLISH, resolver))
            .isNull()
    }

    @Test
    fun `an unknown step id yields nothing`() {
        val run = result(
            listOf(
                step(
                    "fire_detect_alarm", 0, OutcomeClass.INCORRECT,
                    answered = listOf("fight_fire_first"), correct = listOf("raise_alarm"),
                ),
            ),
        )
        assertThat(AiTaskFactory.stepCoaching(run, "no_such_step", AiLanguage.ENGLISH, resolver))
            .isNull()
    }

    @Test
    fun `a step whose text cannot be resolved yields nothing`() {
        // A catalog step with no translation must not produce a prompt containing empty strings.
        val run = result(
            listOf(
                step(
                    "unresolvable_step", 0, OutcomeClass.INCORRECT,
                    answered = listOf("x"), correct = listOf("y"),
                ),
            ),
        )
        assertThat(AiTaskFactory.stepCoaching(run, "unresolvable_step", AiLanguage.ENGLISH, resolver))
            .isNull()
    }

    // -----------------------------------------------------------------------
    // Batch
    // -----------------------------------------------------------------------

    @Test
    fun `coachable steps returns only the ones needing help in presentation order`() {
        val run = result(
            listOf(
                step(
                    "fire_detect_alarm", 0, OutcomeClass.CORRECT_FAST,
                    answered = listOf("raise_alarm"), correct = listOf("raise_alarm"),
                ),
                step(
                    "gas_rescue_decision", 2, OutcomeClass.INCORRECT,
                    answered = listOf("enter_and_pull"), correct = listOf("do_not_enter"),
                    critical = true,
                ),
                step(
                    "fire_pick_extinguisher", 1, OutcomeClass.CORRECT_SLOW,
                    answered = listOf("ext_co2"), correct = listOf("ext_co2"),
                    latencyMs = 8_000, expertMs = 3_000,
                ),
            ),
        )
        val tasks = AiTaskFactory.coachableSteps(run, AiLanguage.ENGLISH, resolver)
        assertThat(tasks.map { it.stepId })
            .containsExactly("fire_pick_extinguisher", "gas_rescue_decision")
            .inOrder()
    }

    @Test
    fun `a perfect run has nothing to coach`() {
        val run = result(
            listOf(
                step(
                    "fire_detect_alarm", 0, OutcomeClass.CORRECT_FAST,
                    answered = listOf("raise_alarm"), correct = listOf("raise_alarm"),
                ),
            ),
        )
        assertThat(AiTaskFactory.coachableSteps(run, AiLanguage.ENGLISH, resolver)).isEmpty()
    }

    // -----------------------------------------------------------------------
    // The boundary
    // -----------------------------------------------------------------------

    @Test
    fun `a coaching task carries no score and no pass flag`() {
        // Asserted structurally: StepCoaching has no field for either, so a model can never be told
        // the verdict and cannot restate it. If a future change adds one, this test's reasoning is
        // what to re-read.
        val run = result(
            listOf(
                step(
                    "fire_pick_extinguisher", 0, OutcomeClass.INCORRECT,
                    answered = listOf("ext_water"), correct = listOf("ext_co2"),
                ),
            ),
        )
        val task = AiTaskFactory.stepCoaching(run, "fire_pick_extinguisher", AiLanguage.ENGLISH, resolver)!!
        val fieldNames = task.javaClass.declaredFields.map { it.name.lowercase() }
        assertThat(fieldNames.none { it.contains("score") }).isTrue()
        assertThat(fieldNames.none { it.contains("passed") }).isTrue()
        assertThat(fieldNames.none { it.contains("permille") }).isTrue()
    }

    @Test
    fun `the retrieval query is built from the step wording and the correct action`() {
        val run = result(
            listOf(
                step(
                    "gas_rescue_decision", 0, OutcomeClass.INCORRECT,
                    answered = listOf("enter_and_pull"), correct = listOf("do_not_enter"),
                    critical = true,
                ),
            ),
        )
        val task = AiTaskFactory.stepCoaching(run, "gas_rescue_decision", AiLanguage.ENGLISH, resolver)!!
        assertThat(task.retrievalQuery()).contains("collapsed inside the tank")
        assertThat(task.retrievalQuery()).contains("Do not enter")
        // The wrong answer is deliberately absent from the query: retrieving on it would find the
        // passage describing the mistake rather than the one explaining the rule.
        assertThat(task.retrievalQuery()).doesNotContain("pull them out")
    }

    @Test
    fun `coaching against the real corpus produces a grounded prompt`() {
        val run = result(
            listOf(
                step(
                    "gas_rescue_decision", 0, OutcomeClass.INCORRECT,
                    answered = listOf("enter_and_pull"), correct = listOf("do_not_enter"),
                    critical = true,
                ),
            ),
        )
        val task = AiTaskFactory.stepCoaching(run, "gas_rescue_decision", AiLanguage.ENGLISH, resolver)!!
        val grounding = SafetyCorpus.retriever.retrieveForCoaching(task)
        assertThat(grounding).isInstanceOf(RetrievalResult.Grounded::class.java)
        val built = PromptBuilder.build(
            task,
            (grounding as RetrievalResult.Grounded).passages.map { it.passage },
        )!!
        assertThat(built.passageIds).contains("gas-never-enter-to-rescue-en")
        assertThat(built.text).contains("breathing apparatus")
    }
}
