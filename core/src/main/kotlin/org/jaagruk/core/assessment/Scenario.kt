package org.jaagruk.core.assessment

import org.jaagruk.core.catalog.Pictogram

/** How a step is answered. Determines which input surfaces the UI offers. */
enum class StepKind {
    /** Exactly one right option. */
    SINGLE_CHOICE,

    /** A set of right options; partial selections are wrong. */
    MULTI_SELECT,

    /** Right options in the right order — evacuation sequencing, LOTO steps. */
    SEQUENCE,

    /** Point at a target in the AR scene (gesture, gaze-reticle or tap). */
    AR_POINT,

    /** Hold a target in the reticle for a dwell period — used where a tap is unrealistic. */
    AR_DWELL,

    /** Answered by reacting to a real peer during a two-phone buddy drill. */
    BUDDY_RESPONSE,
    ;

    /** True when the AR scene, not a list, presents the options. */
    val isSpatial: Boolean get() = this == AR_POINT || this == AR_DWELL

    /** True when answer order is part of correctness. */
    val isOrdered: Boolean get() = this == SEQUENCE
}

/** How the worker actually gave the answer. Recorded per step, reported on the dashboard. */
enum class InputMethod {
    TOUCH,
    GESTURE,
    VOICE,
    AR_RETICLE,
    PEER,

    /** Synthesised by the engine when a step timed out. */
    AUTO_TIMEOUT,

    /** Synthesised by the engine for steps never reached. */
    NOT_ANSWERED,
    ;

    val isAssistive: Boolean get() = this == GESTURE || this == VOICE
}

/**
 * One selectable answer.
 *
 * [pictogram] is mandatory, not optional. In zero-text mode it is the only thing a worker who
 * cannot read will see, so an option without one would be invisible to exactly the audience
 * this platform exists for.
 */
class StepOption(
    val optionId: String,
    val labelKey: String,
    val pictogram: Pictogram,
    /** Named target in the AR scene, required for spatial steps. */
    val arTargetKey: String? = null,
    /** Marks a deliberately plausible wrong answer, for authoring review. */
    val isDistractor: Boolean = false,
) {
    init {
        require(optionId.isNotBlank()) { "optionId must not be blank" }
        require(labelKey.isNotBlank()) { "labelKey must not be blank for option '$optionId'" }
    }

    override fun toString(): String = "StepOption($optionId, $pictogram)"
}

/**
 * One assessed decision.
 *
 * [expertMs] is the calibrated time a confident, trained worker takes. [timeoutMs] is the
 * point past which not deciding *is* the failure. The gap between them is where hesitation
 * lives, and it is the measurement that separates this assessment engine from a quiz.
 */
class StepSpec(
    val stepId: String,
    val kind: StepKind,
    val promptKey: String,
    val options: List<StepOption>,
    /** Correct option ids. Treated as an ordered list for [StepKind.SEQUENCE], a set otherwise. */
    val correctOptionIds: List<String>,
    val expertMs: Long,
    val timeoutMs: Long,
    val weight: Double = 1.0,
    /**
     * A step that alone decides competence. Getting it wrong fails the module regardless of
     * the total score, because "82 % overall, picked the water extinguisher for an
     * electrical fire" is not a pass in a real mine.
     */
    val critical: Boolean = false,
    /** Dwell requirement for [StepKind.AR_DWELL]. */
    val dwellMs: Long = 0L,
    /** Optional coaching key shown after a wrong answer. */
    val remediationKey: String? = null,
) {
    init {
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        require(promptKey.isNotBlank()) { "promptKey must not be blank for step '$stepId'" }
        require(options.isNotEmpty()) { "step '$stepId' has no options" }

        val optionIds = options.map { it.optionId }
        require(optionIds.distinct().size == optionIds.size) {
            "step '$stepId' has duplicate optionIds: " +
                optionIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        }

        require(correctOptionIds.isNotEmpty()) { "step '$stepId' has no correct option" }
        require(correctOptionIds.distinct().size == correctOptionIds.size) {
            "step '$stepId' lists a correct option more than once"
        }
        val unknown = correctOptionIds - optionIds.toSet()
        require(unknown.isEmpty()) {
            "step '$stepId' marks unknown option(s) as correct: $unknown"
        }

        if (kind == StepKind.SINGLE_CHOICE || kind.isSpatial) {
            require(correctOptionIds.size == 1) {
                "step '$stepId' is $kind so it must have exactly one correct option, " +
                    "found ${correctOptionIds.size}"
            }
        }
        if (kind == StepKind.SEQUENCE) {
            require(correctOptionIds.size >= 2) {
                "step '$stepId' is a SEQUENCE so it needs at least two ordered options"
            }
        }
        if (kind.isSpatial) {
            val missing = options.filter { it.arTargetKey.isNullOrBlank() }.map { it.optionId }
            require(missing.isEmpty()) {
                "spatial step '$stepId' has option(s) without an arTargetKey: $missing"
            }
        }
        if (kind == StepKind.AR_DWELL) {
            require(dwellMs > 0) { "AR_DWELL step '$stepId' needs a positive dwellMs" }
            require(dwellMs < timeoutMs) {
                "AR_DWELL step '$stepId' has dwellMs ($dwellMs) >= timeoutMs ($timeoutMs)"
            }
        }

        require(expertMs > 0) { "step '$stepId' needs a positive expertMs, got $expertMs" }
        require(timeoutMs > expertMs) {
            "step '$stepId' has timeoutMs ($timeoutMs) <= expertMs ($expertMs); " +
                "there would be no window in which hesitation could be measured"
        }
        require(weight > 0.0 && weight.isFinite()) {
            "step '$stepId' needs a positive finite weight, got $weight"
        }
    }

    val slowThresholdMs: Long get() = (expertMs * AssessmentConfig.SLOW_FACTOR).toLong()

    fun option(optionId: String): StepOption? = options.firstOrNull { it.optionId == optionId }

    override fun toString(): String =
        "StepSpec($stepId, $kind, expert=${expertMs}ms, timeout=${timeoutMs}ms, critical=$critical)"
}

/**
 * A complete assessable drill.
 *
 * Validation happens in `init`, so an invalid scenario cannot exist at runtime. Authoring
 * mistakes — an unreachable correct answer, a timeout below the expert baseline, a duplicated
 * step id — fail at construction in a unit test rather than mid-drill in a mine.
 */
class ScenarioSpec(
    val scenarioId: String,
    val moduleId: String,
    val titleKey: String,
    val steps: List<StepSpec>,
    val passThresholdPermille: Int = AssessmentConfig.DEFAULT_PASS_THRESHOLD_PERMILLE,
    val hesitationRatioLimit: Double = AssessmentConfig.DEFAULT_HESITATION_RATIO_LIMIT,
    /** Requires two real devices. A solo run of this scenario cannot set the buddy flag. */
    val requiresBuddy: Boolean = false,
    /** Short refresher variant used by the spaced-repetition scheduler. */
    val isRefresherVariant: Boolean = false,
) {
    init {
        require(scenarioId.isNotBlank()) { "scenarioId must not be blank" }
        require(moduleId.isNotBlank()) { "moduleId must not be blank for scenario '$scenarioId'" }
        require(titleKey.isNotBlank()) { "titleKey must not be blank for scenario '$scenarioId'" }
        require(steps.isNotEmpty()) { "scenario '$scenarioId' has no steps" }

        val ids = steps.map { it.stepId }
        require(ids.distinct().size == ids.size) {
            "scenario '$scenarioId' has duplicate stepIds: " +
                ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        }
        require(passThresholdPermille in 0..1000) {
            "passThresholdPermille must be 0..1000, got $passThresholdPermille"
        }
        require(hesitationRatioLimit in 0.0..1.0) {
            "hesitationRatioLimit must be 0.0..1.0, got $hesitationRatioLimit"
        }
        if (requiresBuddy) {
            require(steps.any { it.kind == StepKind.BUDDY_RESPONSE }) {
                "scenario '$scenarioId' requires a buddy but has no BUDDY_RESPONSE step"
            }
        }
        require(steps.any { it.critical }) {
            "scenario '$scenarioId' has no critical step; every safety drill must have at " +
                "least one decision that alone determines competence"
        }
    }

    val totalWeight: Double get() = steps.sumOf { it.weight }

    val criticalStepIds: List<String> get() = steps.filter { it.critical }.map { it.stepId }

    /** Worst-case wall time if the worker times out on every step. Used for UI budgeting. */
    val maxDurationMs: Long get() = steps.sumOf { it.timeoutMs }

    fun step(stepId: String): StepSpec? = steps.firstOrNull { it.stepId == stepId }

    fun stepIndex(stepId: String): Int = steps.indexOfFirst { it.stepId == stepId }

    override fun toString(): String =
        "ScenarioSpec($scenarioId, module=$moduleId, steps=${steps.size}, buddy=$requiresBuddy)"
}

/** Tunables shared by the engine, the score calculator and the certificate flags. */
object AssessmentConfig {

    /** Correct beyond `expertMs * SLOW_FACTOR` counts as hesitation. */
    const val SLOW_FACTOR: Double = 2.0

    /** A correct answer never scores below this, however slow. Correctness dominates. */
    const val ACCURACY_WEIGHT: Double = 0.70

    /** The remaining share that speed is worth. */
    const val LATENCY_WEIGHT: Double = 0.30

    /** Faster than a human can read the prompt: recorded as a probable guess. */
    const val SUSPICIOUS_FAST_MS: Long = 250L

    /** This many suspiciously fast answers voids the run as a tap-through. */
    const val SUSPICIOUS_FAST_VOID_THRESHOLD: Int = 3

    const val DEFAULT_PASS_THRESHOLD_PERMILLE: Int = 700

    const val DEFAULT_HESITATION_RATIO_LIMIT: Double = 0.34

    /** A backgrounded drill is abandoned after this long rather than resumed misleadingly. */
    const val BACKGROUND_ABORT_MS: Long = 5 * 60 * 1000L

    init {
        check(ACCURACY_WEIGHT + LATENCY_WEIGHT == 1.0) {
            "scoring weights must sum to 1.0"
        }
    }
}
