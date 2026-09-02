package org.jaagruk.core.assessment

/**
 * What actually happened on one step.
 *
 * The distinction between [CORRECT_FAST] and [CORRECT_SLOW] is the core idea of this
 * platform. A conventional quiz records both as "right" and moves on. In an evacuation the
 * difference between deciding in two seconds and deciding in nine is the difference between
 * leaving and not leaving, and hesitation under pressure is a documented failure mode
 * independent of knowledge. So it is measured, stored, signed into the certificate, and
 * surfaced as its own cohort on the compliance dashboard.
 */
enum class OutcomeClass {
    /** Right answer, within the hesitation threshold. Genuinely ready. */
    CORRECT_FAST,

    /** Right answer, but slowly. Knows the material; may freeze when it counts. */
    CORRECT_SLOW,

    /** Wrong answer given. */
    INCORRECT,

    /** No answer inside the window. Counts as incorrect, and is recorded distinctly. */
    TIMEOUT,

    /** Step never reached — drill aborted. Excluded from the score denominator. */
    SKIPPED,
    ;

    val isCorrect: Boolean get() = this == CORRECT_FAST || this == CORRECT_SLOW

    /** True when the worker actually produced an answer. */
    val wasAnswered: Boolean get() = this != TIMEOUT && this != SKIPPED

    /** True when the step contributes to the score. */
    val isScored: Boolean get() = this != SKIPPED
}

/** How a run ended. */
enum class Completion {
    /** Every step was reached and sealed. */
    COMPLETED,

    /** Ended early by the worker, a lost peer or a lost session. Partial data retained. */
    ABORTED,

    /** Started but no step was ever sealed. No score, no certificate. */
    INCOMPLETE,

    /** Completed but discarded as invalid — see [VoidReason]. */
    VOIDED,
    ;

    /** Only a COMPLETED run can ever produce a certificate. */
    val canCertify: Boolean get() = this == COMPLETED
}

enum class VoidReason {
    /** Enough sub-human-reaction answers to indicate tapping through, not deciding. */
    GUESS_PATTERN,

    /** Scenario definition changed under a running session. */
    SCENARIO_MISMATCH,

    /** Buddy scenario finished without a genuine second device. */
    BUDDY_REQUIRED_BUT_SOLO,
}

enum class AbortReason {
    USER_CANCELLED,
    PEER_LOST,
    PEER_VERSION_MISMATCH,
    APP_BACKGROUNDED_TOO_LONG,
    AR_TRACKING_UNRECOVERABLE,
    CAMERA_LOST,
    SESSION_EXPIRED,
}

/** How a run was conducted. Drives certificate flags and dashboard filtering. */
enum class AssessmentMode {
    /** First certification for this module. */
    INITIAL,

    /** Short spaced-repetition check. */
    REFRESHER,

    /** Two real devices over Nearby Connections. */
    BUDDY,

    /** Supervisor demonstration — never certifies, never counts toward compliance. */
    PRACTICE,
    ;

    val certifies: Boolean get() = this != PRACTICE
}

/** How the scene was presented. Signed into the certificate so it cannot be overstated later. */
enum class ArPresentation {
    /** Full ARCore with resolved site Cloud Anchors — trained in the worker's real corridor. */
    SITE_SCANNED,

    /** ARCore with a generic room template. */
    ARCORE_GENERIC,

    /** Camera plus rotation sensors, markers on a virtual sphere. */
    SENSOR_FALLBACK,

    /** No camera path available; flat pictogram drill. */
    PICTOGRAM_2D,
    ;

    val isSiteScanned: Boolean get() = this == SITE_SCANNED
}

/** Sealed record of one step. Immutable once created. */
class StepResult(
    val stepId: String,
    val stepIndex: Int,
    val kind: StepKind,
    val outcome: OutcomeClass,
    val latencyMs: Long,
    val expertMs: Long,
    val timeoutMs: Long,
    val answeredOptionIds: List<String>,
    val correctOptionIds: List<String>,
    val stepScore: Double,
    val weight: Double,
    val critical: Boolean,
    val inputMethod: InputMethod,
    val suspiciousFast: Boolean,
) {
    init {
        require(stepScore in 0.0..1.0) { "stepScore must be 0.0..1.0, got $stepScore for '$stepId'" }
        require(latencyMs >= 0) { "latencyMs must be >= 0, got $latencyMs for '$stepId'" }
        require(weight > 0.0) { "weight must be positive, got $weight for '$stepId'" }
    }

    val isCorrect: Boolean get() = outcome.isCorrect

    /** How far past the expert baseline the decision took, as a multiple. 1.0 means on pace. */
    val paceMultiple: Double
        get() = if (expertMs <= 0L) Double.NaN else latencyMs.toDouble() / expertMs.toDouble()

    override fun toString(): String =
        "StepResult($stepId, $outcome, ${latencyMs}ms vs expert ${expertMs}ms, score=$stepScore)"
}

/**
 * Sealed record of a whole run.
 *
 * Everything needed to issue a certificate, populate the dashboard, drive the refresher
 * scheduler and explain the verdict to the worker in their own language.
 */
class AssessmentResult(
    val runId: String,
    val scenarioId: String,
    val moduleId: String,
    val moduleCode: Int,
    val mode: AssessmentMode,
    val presentation: ArPresentation,
    val completion: Completion,
    val scorePermille: Int,
    val passed: Boolean,
    val hesitationFlag: Boolean,
    val hesitationRatio: Double,
    val medianLatencyMs: Long,
    val steps: List<StepResult>,
    val failedCriticalStepIds: List<String>,
    val voidReason: VoidReason?,
    val abortReason: AbortReason?,
    val startedAtEpochSec: Long,
    val finishedAtEpochSec: Long,
    val totalDurationMs: Long,
    val buddyPeerDeviceId: String?,
) {
    init {
        require(scorePermille in 0..1000) { "scorePermille must be 0..1000, got $scorePermille" }
        require(hesitationRatio in 0.0..1.0) {
            "hesitationRatio must be 0.0..1.0, got $hesitationRatio"
        }
        require(!passed || completion.canCertify) {
            "a run that did not complete cannot be marked passed (completion=$completion)"
        }
        require((voidReason != null) == (completion == Completion.VOIDED)) {
            "voidReason and Completion.VOIDED must agree (voidReason=$voidReason, completion=$completion)"
        }
        require((abortReason != null) == (completion == Completion.ABORTED)) {
            "abortReason and Completion.ABORTED must agree (abortReason=$abortReason, completion=$completion)"
        }
    }

    val scorePercent: Double get() = scorePermille / 10.0

    val correctCount: Int get() = steps.count { it.outcome.isCorrect }

    val hesitantCount: Int get() = steps.count { it.outcome == OutcomeClass.CORRECT_SLOW }

    val timeoutCount: Int get() = steps.count { it.outcome == OutcomeClass.TIMEOUT }

    val scoredStepCount: Int get() = steps.count { it.outcome.isScored }

    /** True only when a certificate may be minted from this run. */
    val certifiable: Boolean get() = passed && completion.canCertify && mode.certifies

    /** Steps worth replaying with the worker — wrong, timed out, or notably hesitant. */
    val remediationStepIds: List<String>
        get() = steps.filter { !it.isCorrect || it.outcome == OutcomeClass.CORRECT_SLOW }
            .map { it.stepId }

    val assistiveInputUsed: Boolean get() = steps.any { it.inputMethod.isAssistive }

    override fun toString(): String =
        "AssessmentResult($runId, $moduleId, $completion, score=$scorePermille, passed=$passed, " +
            "hesitation=$hesitationFlag, median=${medianLatencyMs}ms)"
}
