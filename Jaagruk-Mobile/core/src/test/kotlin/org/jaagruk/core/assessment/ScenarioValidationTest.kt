package org.jaagruk.core.assessment

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.catalog.ArTargets
import org.jaagruk.core.catalog.Pictogram
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An invalid scenario must be impossible to construct.
 *
 * Every case here is an authoring mistake that would otherwise surface mid-drill, underground,
 * to a worker who cannot report it usefully. Failing in `init` moves the whole class of bug into
 * a unit test.
 */
class ScenarioValidationTest {

    private fun option(id: String, arTarget: String? = null) =
        StepOption(id, "opt_$id", Pictogram.ANSWER_YES, arTarget)

    private fun step(
        id: String = "s",
        kind: StepKind = StepKind.SINGLE_CHOICE,
        options: List<StepOption> = listOf(option("s_a"), option("s_b")),
        correct: List<String> = listOf("s_a"),
        expertMs: Long = 1_000L,
        timeoutMs: Long = 5_000L,
        weight: Double = 1.0,
        dwellMs: Long = 0L,
        critical: Boolean = true,
    ) = StepSpec(
        stepId = id,
        kind = kind,
        promptKey = "step_${id}_prompt",
        options = options,
        correctOptionIds = correct,
        expertMs = expertMs,
        timeoutMs = timeoutMs,
        weight = weight,
        critical = critical,
        dwellMs = dwellMs,
    )

    // --- StepSpec ---------------------------------------------------------

    @Test
    fun `rejects a blank step id or prompt`() {
        assertThrows<IllegalArgumentException> { step(id = " ") }
        assertThrows<IllegalArgumentException> {
            StepSpec(
                stepId = "s",
                kind = StepKind.SINGLE_CHOICE,
                promptKey = "",
                options = listOf(option("a")),
                correctOptionIds = listOf("a"),
                expertMs = 100L,
                timeoutMs = 200L,
            )
        }
    }

    @Test
    fun `rejects a step with no options`() {
        assertThrows<IllegalArgumentException> { step(options = emptyList(), correct = listOf("x")) }
    }

    @Test
    fun `rejects duplicate option ids`() {
        val error = assertThrows<IllegalArgumentException> {
            step(options = listOf(option("s_a"), option("s_a")))
        }
        assertThat(error).hasMessageThat().contains("duplicate optionIds")
    }

    @Test
    fun `rejects a correct answer that is not one of the options`() {
        val error = assertThrows<IllegalArgumentException> { step(correct = listOf("s_z")) }
        assertThat(error).hasMessageThat().contains("unknown option")
    }

    @Test
    fun `rejects no correct answer or a repeated correct answer`() {
        assertThrows<IllegalArgumentException> { step(correct = emptyList()) }
        assertThrows<IllegalArgumentException> {
            step(
                kind = StepKind.MULTI_SELECT,
                options = listOf(option("s_a"), option("s_b")),
                correct = listOf("s_a", "s_a"),
            )
        }
    }

    @Test
    fun `single choice must have exactly one correct answer`() {
        val error = assertThrows<IllegalArgumentException> {
            step(correct = listOf("s_a", "s_b"))
        }
        assertThat(error).hasMessageThat().contains("exactly one correct option")
    }

    @Test
    fun `sequence needs at least two ordered options`() {
        val error = assertThrows<IllegalArgumentException> {
            step(kind = StepKind.SEQUENCE, correct = listOf("s_a"))
        }
        assertThat(error).hasMessageThat().contains("at least two ordered options")
    }

    @Test
    fun `spatial steps need an ar target on every option`() {
        val error = assertThrows<IllegalArgumentException> {
            step(
                kind = StepKind.AR_POINT,
                options = listOf(option("s_a", ArTargets.EXIT_PRIMARY), option("s_b")),
                correct = listOf("s_a"),
            )
        }
        assertThat(error).hasMessageThat().contains("arTargetKey")
    }

    @Test
    fun `ar dwell needs a positive dwell shorter than the timeout`() {
        assertThrows<IllegalArgumentException> {
            step(
                kind = StepKind.AR_DWELL,
                options = listOf(option("s_a", ArTargets.EXIT_PRIMARY)),
                correct = listOf("s_a"),
                dwellMs = 0L,
            )
        }
        assertThrows<IllegalArgumentException> {
            step(
                kind = StepKind.AR_DWELL,
                options = listOf(option("s_a", ArTargets.EXIT_PRIMARY)),
                correct = listOf("s_a"),
                timeoutMs = 2_000L,
                dwellMs = 3_000L,
            )
        }
    }

    @Test
    fun `rejects a timeout that is not above the expert baseline`() {
        // Without a gap between the two there is no window in which hesitation could exist,
        // which would silently disable the platform's core measurement for that step.
        val error = assertThrows<IllegalArgumentException> {
            step(expertMs = 5_000L, timeoutMs = 5_000L)
        }
        assertThat(error).hasMessageThat().contains("hesitation could be measured")
        assertThrows<IllegalArgumentException> { step(expertMs = 6_000L, timeoutMs = 5_000L) }
    }

    @Test
    fun `rejects a non positive expert baseline or weight`() {
        assertThrows<IllegalArgumentException> { step(expertMs = 0L) }
        assertThrows<IllegalArgumentException> { step(expertMs = -1L) }
        assertThrows<IllegalArgumentException> { step(weight = 0.0) }
        assertThrows<IllegalArgumentException> { step(weight = -1.0) }
        assertThrows<IllegalArgumentException> { step(weight = Double.NaN) }
        assertThrows<IllegalArgumentException> { step(weight = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `slow threshold derives from the expert baseline`() {
        assertThat(step(expertMs = 1_500L, timeoutMs = 9_000L).slowThresholdMs).isEqualTo(3_000L)
    }

    @Test
    fun `option lookup works`() {
        val spec = step()
        assertThat(spec.option("s_a")).isNotNull()
        assertThat(spec.option("nope")).isNull()
    }

    // --- ScenarioSpec -----------------------------------------------------

    @Test
    fun `rejects a scenario with no steps`() {
        assertThrows<IllegalArgumentException> {
            ScenarioSpec("s", "m", "t", emptyList())
        }
    }

    @Test
    fun `rejects duplicate step ids`() {
        val error = assertThrows<IllegalArgumentException> {
            ScenarioSpec("s", "m", "t", listOf(step("dup"), step("dup")))
        }
        assertThat(error).hasMessageThat().contains("duplicate stepIds")
    }

    @Test
    fun `rejects blank identifiers`() {
        assertThrows<IllegalArgumentException> { ScenarioSpec(" ", "m", "t", listOf(step())) }
        assertThrows<IllegalArgumentException> { ScenarioSpec("s", " ", "t", listOf(step())) }
        assertThrows<IllegalArgumentException> { ScenarioSpec("s", "m", " ", listOf(step())) }
    }

    @Test
    fun `rejects an out of range pass threshold or hesitation limit`() {
        assertThrows<IllegalArgumentException> {
            ScenarioSpec("s", "m", "t", listOf(step()), passThresholdPermille = 1_001)
        }
        assertThrows<IllegalArgumentException> {
            ScenarioSpec("s", "m", "t", listOf(step()), passThresholdPermille = -1)
        }
        assertThrows<IllegalArgumentException> {
            ScenarioSpec("s", "m", "t", listOf(step()), hesitationRatioLimit = 1.5)
        }
    }

    @Test
    fun `requires at least one critical step`() {
        // Every safety drill must contain a decision that alone determines competence.
        val error = assertThrows<IllegalArgumentException> {
            ScenarioSpec("s", "m", "t", listOf(step(critical = false)))
        }
        assertThat(error).hasMessageThat().contains("no critical step")
    }

    @Test
    fun `a buddy scenario must contain a buddy step`() {
        val error = assertThrows<IllegalArgumentException> {
            ScenarioSpec("s", "m", "t", listOf(step()), requiresBuddy = true)
        }
        assertThat(error).hasMessageThat().contains("no BUDDY_RESPONSE step")
    }

    @Test
    fun `exposes derived properties`() {
        val scenario = ScenarioSpec(
            scenarioId = "s",
            moduleId = "m",
            titleKey = "t",
            steps = listOf(
                step("a", weight = 2.0, timeoutMs = 5_000L),
                step("b", weight = 1.0, timeoutMs = 7_000L, critical = false),
            ),
        )
        assertThat(scenario.totalWeight).isWithin(1e-9).of(3.0)
        assertThat(scenario.criticalStepIds).containsExactly("a")
        assertThat(scenario.maxDurationMs).isEqualTo(12_000L)
        assertThat(scenario.stepIndex("b")).isEqualTo(1)
        assertThat(scenario.stepIndex("missing")).isEqualTo(-1)
        assertThat(scenario.step("a")).isNotNull()
    }

    @Test
    fun `scoring weights sum to one`() {
        assertThat(AssessmentConfig.ACCURACY_WEIGHT + AssessmentConfig.LATENCY_WEIGHT)
            .isWithin(1e-12).of(1.0)
    }
}
