package org.jaagruk.core.assessment

import org.jaagruk.core.catalog.ArTargets
import org.jaagruk.core.catalog.Pictogram

/** Small, explicit scenarios so scoring assertions are readable arithmetic. */
object TestScenarios {

    fun singleChoice(
        id: String,
        expertMs: Long = 1_000L,
        timeoutMs: Long = 5_000L,
        weight: Double = 1.0,
        critical: Boolean = false,
    ): StepSpec = StepSpec(
        stepId = id,
        kind = StepKind.SINGLE_CHOICE,
        promptKey = "step_${id}_prompt",
        options = listOf(
            StepOption("${id}_right", "opt_${id}_right", Pictogram.ANSWER_YES),
            StepOption("${id}_wrong", "opt_${id}_wrong", Pictogram.ANSWER_NO),
            StepOption("${id}_other", "opt_${id}_other", Pictogram.WARNING_GENERAL),
        ),
        correctOptionIds = listOf("${id}_right"),
        expertMs = expertMs,
        timeoutMs = timeoutMs,
        weight = weight,
        critical = critical,
    )

    fun multiSelect(
        id: String,
        expertMs: Long = 2_000L,
        timeoutMs: Long = 8_000L,
    ): StepSpec = StepSpec(
        stepId = id,
        kind = StepKind.MULTI_SELECT,
        promptKey = "step_${id}_prompt",
        options = listOf(
            StepOption("${id}_a", "opt_a", Pictogram.WEAR_HELMET),
            StepOption("${id}_b", "opt_b", Pictogram.WEAR_GLOVES),
            StepOption("${id}_c", "opt_c", Pictogram.WEAR_SAFETY_BOOTS),
            StepOption("${id}_d", "opt_d", Pictogram.WARNING_GENERAL),
        ),
        correctOptionIds = listOf("${id}_a", "${id}_b"),
        expertMs = expertMs,
        timeoutMs = timeoutMs,
    )

    fun sequence(
        id: String,
        expertMs: Long = 3_000L,
        timeoutMs: Long = 12_000L,
    ): StepSpec = StepSpec(
        stepId = id,
        kind = StepKind.SEQUENCE,
        promptKey = "step_${id}_prompt",
        options = listOf(
            StepOption("${id}_1", "opt_1", Pictogram.STOP_HAND),
            StepOption("${id}_2", "opt_2", Pictogram.DISCONNECT_BEFORE_WORK),
            StepOption("${id}_3", "opt_3", Pictogram.LOCKOUT_TAGOUT),
        ),
        correctOptionIds = listOf("${id}_1", "${id}_2", "${id}_3"),
        expertMs = expertMs,
        timeoutMs = timeoutMs,
    )

    fun arPoint(
        id: String,
        expertMs: Long = 1_500L,
        timeoutMs: Long = 6_000L,
        critical: Boolean = true,
    ): StepSpec = StepSpec(
        stepId = id,
        kind = StepKind.AR_POINT,
        promptKey = "step_${id}_prompt",
        options = listOf(
            StepOption("${id}_exit", "opt_exit", Pictogram.EMERGENCY_EXIT_RIGHT, ArTargets.EXIT_PRIMARY),
            StepOption("${id}_lift", "opt_lift", Pictogram.DO_NOT_USE_LIFT_IN_FIRE, ArTargets.LIFT_DOOR),
        ),
        correctOptionIds = listOf("${id}_exit"),
        expertMs = expertMs,
        timeoutMs = timeoutMs,
        critical = critical,
    )

    fun buddyResponse(
        id: String,
        expertMs: Long = 2_000L,
        timeoutMs: Long = 8_000L,
    ): StepSpec = StepSpec(
        stepId = id,
        kind = StepKind.BUDDY_RESPONSE,
        promptKey = "step_${id}_prompt",
        options = listOf(
            StepOption("${id}_alarm", "opt_alarm", Pictogram.RAISE_ALARM),
            StepOption("${id}_enter", "opt_enter", Pictogram.DO_NOT_ENTER_ALONE),
        ),
        correctOptionIds = listOf("${id}_alarm"),
        expertMs = expertMs,
        timeoutMs = timeoutMs,
        critical = true,
    )

    /** Three single-choice steps; the first is critical. */
    fun threeStep(
        scenarioId: String = "test-three-step",
        passThresholdPermille: Int = AssessmentConfig.DEFAULT_PASS_THRESHOLD_PERMILLE,
        hesitationRatioLimit: Double = AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
    ): ScenarioSpec = ScenarioSpec(
        scenarioId = scenarioId,
        moduleId = "test-module",
        titleKey = "scenario_test_title",
        steps = listOf(
            singleChoice("s1", critical = true),
            singleChoice("s2"),
            singleChoice("s3"),
        ),
        passThresholdPermille = passThresholdPermille,
        hesitationRatioLimit = hesitationRatioLimit,
    )

    /** One step of each kind, for exercising the matcher end to end. */
    fun mixedKinds(scenarioId: String = "test-mixed"): ScenarioSpec = ScenarioSpec(
        scenarioId = scenarioId,
        moduleId = "test-module",
        titleKey = "scenario_test_title",
        steps = listOf(
            singleChoice("m1", critical = true),
            multiSelect("m2"),
            sequence("m3"),
            arPoint("m4", critical = false),
        ),
    )

    fun buddyScenario(scenarioId: String = "test-buddy"): ScenarioSpec = ScenarioSpec(
        scenarioId = scenarioId,
        moduleId = "test-module",
        titleKey = "scenario_test_title",
        steps = listOf(singleChoice("b1"), buddyResponse("b2")),
        requiresBuddy = true,
    )
}
