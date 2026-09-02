package org.jaagruk.core.assessment

import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource
import org.jaagruk.core.util.SystemWallClock
import org.jaagruk.core.util.WallClock

/** Lifecycle of a single run. */
enum class SessionState {
    NOT_STARTED,
    RUNNING,

    /** Backgrounded or interrupted. The latency clock is stopped. */
    PAUSED,

    /** Every step is sealed; the result has not been computed yet. */
    STEPS_DONE,

    /** [AssessmentSession.finish] has produced an immutable result. */
    FINISHED,
    ;

    val acceptsInput: Boolean get() = this == RUNNING
    val isTerminal: Boolean get() = this == FINISHED
}

/** Result of offering an answer to the session. */
sealed interface SubmitOutcome {
    class Accepted(val stepResult: StepResult, val isLastStep: Boolean) : SubmitOutcome

    class Ignored(val reason: IgnoreReason) : SubmitOutcome
}

/**
 * Why an answer was discarded.
 *
 * Every one of these is a real field condition rather than a defensive placeholder:
 * a glove double-tap, a voice command recognised just after the window closed, a gesture
 * landing while the drill is paused for a phone call.
 */
enum class IgnoreReason {
    /** Session not accepting input (not started, paused, or already over). */
    NOT_RUNNING,

    /**
     * The answer names a step that is no longer current — a double-tap, or an answer that
     * arrived after the step was sealed. Requiring the caller to name the step it is
     * answering is what makes this detectable instead of silently answering the next step.
     */
    STALE_STEP,

    /** No options selected. A genuine non-answer is handled by the timeout path instead. */
    EMPTY_ANSWER,
}

/** Emitted by [AssessmentSession.poll]. */
sealed interface PollEvent {
    class StepTimedOut(val stepResult: StepResult, val isLastStep: Boolean) : PollEvent
}

/**
 * Drives one assessment run.
 *
 * Deliberately not a coroutine, not a Flow and not an Android component. The UI calls
 * [poll] on its own frame or timer tick and [submit] when the worker acts, which makes the
 * entire run reproducible from a fixed [MonotonicTimeSource] in a unit test — including
 * timeouts, pauses and hesitation classification.
 *
 * All latency comes from [monotonic]. Wall time is used only to date the run. On a shared
 * site phone whose clock gets corrected mid-shift, a wall-clock delta can go negative; a
 * negative decision latency would corrupt the one measurement this platform is built on.
 */
class AssessmentSession(
    val runId: String,
    val scenario: ScenarioSpec,
    val moduleCode: Int,
    val mode: AssessmentMode,
    val presentation: ArPresentation,
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
    private val wallClock: WallClock = SystemWallClock,
    val buddyPeerDeviceId: String? = null,
) {

    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(moduleCode in 1..255) { "moduleCode must be 1..255, got $moduleCode" }
        if (mode == AssessmentMode.BUDDY) {
            require(!buddyPeerDeviceId.isNullOrBlank()) {
                "BUDDY mode requires a peer device id; use a solo mode for a single-device run"
            }
        }
    }

    private val sealedResults = mutableListOf<StepResult>()

    private var _state: SessionState = SessionState.NOT_STARTED
    private var stepIndex: Int = 0
    private var sessionStartMonotonic: Long = 0L
    private var stepStartMonotonic: Long = 0L
    private var pauseStartedAtMonotonic: Long? = null
    private var pausedMsForCurrentStep: Long = 0L
    private var pausedMsTotal: Long = 0L
    private var startedAtEpochSec: Long = 0L
    private var suspiciousFastCount: Int = 0
    private var abortReason: AbortReason? = null
    private var cachedResult: AssessmentResult? = null

    val state: SessionState get() = _state

    val stepResults: List<StepResult> get() = sealedResults.toList()

    val currentStepIndex: Int get() = stepIndex

    val totalSteps: Int get() = scenario.steps.size

    val currentStep: StepSpec?
        get() = scenario.steps.getOrNull(stepIndex).takeIf {
            _state == SessionState.RUNNING || _state == SessionState.PAUSED
        }

    /** 0.0..1.0, for a progress indicator. */
    val progress: Double
        get() = if (totalSteps == 0) 0.0 else sealedResults.size.toDouble() / totalSteps.toDouble()

    val result: AssessmentResult? get() = cachedResult

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun start() {
        check(_state == SessionState.NOT_STARTED) { "session $runId already started (state=$_state)" }
        val now = monotonic.elapsedMillis()
        sessionStartMonotonic = now
        stepStartMonotonic = now
        startedAtEpochSec = wallClock.epochSeconds()
        pausedMsForCurrentStep = 0L
        pausedMsTotal = 0L
        _state = SessionState.RUNNING
    }

    /**
     * Stops the latency clock. Idempotent.
     *
     * Called when the app is backgrounded, tracking is lost, or the peer goes stale. Paused
     * time never counts against the worker: being interrupted by a supervisor is not
     * hesitation.
     */
    fun pause() {
        if (_state != SessionState.RUNNING) return
        pauseStartedAtMonotonic = monotonic.elapsedMillis()
        _state = SessionState.PAUSED
    }

    /** Resumes and folds the paused interval out of the current step's latency. Idempotent. */
    fun resume() {
        if (_state != SessionState.PAUSED) return
        val pausedAt = pauseStartedAtMonotonic
        if (pausedAt != null) {
            val pausedFor = (monotonic.elapsedMillis() - pausedAt).coerceAtLeast(0L)
            pausedMsForCurrentStep += pausedFor
            pausedMsTotal += pausedFor
        }
        pauseStartedAtMonotonic = null
        _state = SessionState.RUNNING
    }

    /** How long the session has been paused, for the "resume or discard" prompt. */
    fun currentPauseDurationMs(): Long {
        val pausedAt = pauseStartedAtMonotonic ?: return 0L
        return (monotonic.elapsedMillis() - pausedAt).coerceAtLeast(0L)
    }

    /**
     * Ends the run early. Unreached steps are sealed as [OutcomeClass.SKIPPED].
     *
     * A worker never loses what they already did to a dropped Bluetooth link or a lost
     * camera. The partial run is scored on the steps actually attempted and stored; whether
     * it can certify is decided separately by [Completion].
     */
    fun abort(reason: AbortReason) {
        if (_state == SessionState.FINISHED) return
        if (_state == SessionState.NOT_STARTED) {
            // Nothing was ever timed. Record the reason so the caller still gets a coherent
            // INCOMPLETE result rather than an exception.
            startedAtEpochSec = wallClock.epochSeconds()
            sessionStartMonotonic = monotonic.elapsedMillis()
            stepStartMonotonic = sessionStartMonotonic
        }
        if (_state == SessionState.PAUSED) resume()
        abortReason = reason
        sealRemainingAsSkipped()
        _state = SessionState.STEPS_DONE
    }

    // -----------------------------------------------------------------------
    // Timing
    // -----------------------------------------------------------------------

    /** Latency accrued on the current step, excluding paused time. */
    fun elapsedOnCurrentStepMs(): Long {
        if (_state != SessionState.RUNNING && _state != SessionState.PAUSED) return 0L
        val now = pauseStartedAtMonotonic ?: monotonic.elapsedMillis()
        return (now - stepStartMonotonic - pausedMsForCurrentStep).coerceAtLeast(0L)
    }

    /** Milliseconds left before the current step times out. Never negative. */
    fun remainingOnCurrentStepMs(): Long {
        val step = currentStep ?: return 0L
        return (step.timeoutMs - elapsedOnCurrentStepMs()).coerceAtLeast(0L)
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    /**
     * Offers an answer for [stepId].
     *
     * Naming the step is required rather than implied. A glove double-tap, a voice command
     * recognised 80 ms after a tap, and a peer message replayed by the transport all become
     * an explicit [IgnoreReason.STALE_STEP] instead of accidentally answering the next step
     * in zero milliseconds — which is exactly how a scoring engine ends up certifying someone
     * who never saw the question.
     */
    fun submit(stepId: String, optionIds: List<String>, inputMethod: InputMethod): SubmitOutcome {
        if (_state != SessionState.RUNNING) return SubmitOutcome.Ignored(IgnoreReason.NOT_RUNNING)

        val step = scenario.steps.getOrNull(stepIndex)
            ?: return SubmitOutcome.Ignored(IgnoreReason.NOT_RUNNING)

        if (step.stepId != stepId) return SubmitOutcome.Ignored(IgnoreReason.STALE_STEP)
        if (optionIds.isEmpty()) return SubmitOutcome.Ignored(IgnoreReason.EMPTY_ANSWER)

        val latency = elapsedOnCurrentStepMs()

        // An answer that lands at or past the deadline is a timeout, whatever it said. Doing
        // this here rather than trusting the caller means a stalled UI thread cannot gift a
        // worker a late-but-counted answer.
        if (latency >= step.timeoutMs) {
            val timedOut = sealStep(
                step = step,
                latencyMs = step.timeoutMs,
                answeredOptionIds = optionIds,
                inputMethod = InputMethod.AUTO_TIMEOUT,
                answered = false,
            )
            return SubmitOutcome.Accepted(timedOut, isLastStep = _state == SessionState.STEPS_DONE)
        }

        val sealed = sealStep(
            step = step,
            latencyMs = latency,
            answeredOptionIds = optionIds,
            inputMethod = inputMethod,
            answered = true,
        )
        return SubmitOutcome.Accepted(sealed, isLastStep = _state == SessionState.STEPS_DONE)
    }

    /** Convenience for single-answer steps. */
    fun submitSingle(stepId: String, optionId: String, inputMethod: InputMethod): SubmitOutcome =
        submit(stepId, listOf(optionId), inputMethod)

    /**
     * Advances the timeout clock. Call from the UI tick; safe to call at any rate.
     *
     * @return a [PollEvent.StepTimedOut] if this call sealed a step, otherwise null.
     */
    fun poll(): PollEvent? {
        if (_state != SessionState.RUNNING) return null
        val step = scenario.steps.getOrNull(stepIndex) ?: return null
        if (elapsedOnCurrentStepMs() < step.timeoutMs) return null

        val sealed = sealStep(
            step = step,
            latencyMs = step.timeoutMs,
            answeredOptionIds = emptyList(),
            inputMethod = InputMethod.AUTO_TIMEOUT,
            answered = false,
        )
        return PollEvent.StepTimedOut(sealed, isLastStep = _state == SessionState.STEPS_DONE)
    }

    // -----------------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------------

    /**
     * Seals the run and computes the immutable result. Idempotent — repeated calls return the
     * same object, so a recomposition or a retried save cannot produce two different verdicts
     * for one run.
     */
    fun finish(): AssessmentResult {
        cachedResult?.let { return it }

        // finish() with steps still open and no explicit reason is a user cancellation.
        if (_state == SessionState.RUNNING || _state == SessionState.PAUSED) {
            if (sealedResults.size < scenario.steps.size) {
                abort(AbortReason.USER_CANCELLED)
            }
        }
        if (_state == SessionState.NOT_STARTED) {
            startedAtEpochSec = wallClock.epochSeconds()
            sessionStartMonotonic = monotonic.elapsedMillis()
            sealRemainingAsSkipped()
        }

        val aggregate = ScoreCalculator.aggregate(sealedResults, scenario.hesitationRatioLimit)
        val evaluation = ScoreCalculator.evaluatePass(aggregate, scenario)

        var completion = when {
            abortReason != null -> Completion.ABORTED
            sealedResults.none { it.outcome.isScored } -> Completion.INCOMPLETE
            else -> Completion.COMPLETED
        }

        // Voiding only applies to a run that otherwise completed; an aborted run is already
        // reported honestly as aborted.
        var voidReason: VoidReason? = null
        if (completion == Completion.COMPLETED) {
            voidReason = when {
                suspiciousFastCount >= AssessmentConfig.SUSPICIOUS_FAST_VOID_THRESHOLD ->
                    VoidReason.GUESS_PATTERN

                scenario.requiresBuddy && buddyPeerDeviceId.isNullOrBlank() ->
                    VoidReason.BUDDY_REQUIRED_BUT_SOLO

                else -> null
            }
            if (voidReason != null) completion = Completion.VOIDED
        }

        val finishedMonotonic = monotonic.elapsedMillis()
        val activeDuration = (finishedMonotonic - sessionStartMonotonic - pausedMsTotal)
            .coerceAtLeast(0L)

        val computed = AssessmentResult(
            runId = runId,
            scenarioId = scenario.scenarioId,
            moduleId = scenario.moduleId,
            moduleCode = moduleCode,
            mode = mode,
            presentation = presentation,
            completion = completion,
            scorePermille = aggregate.scorePermille,
            passed = evaluation.passed && completion == Completion.COMPLETED,
            hesitationFlag = aggregate.hesitationFlag,
            hesitationRatio = aggregate.hesitationRatio,
            medianLatencyMs = aggregate.medianLatencyMs,
            steps = sealedResults.toList(),
            failedCriticalStepIds = aggregate.failedCriticalStepIds,
            voidReason = voidReason,
            abortReason = abortReason,
            startedAtEpochSec = startedAtEpochSec,
            finishedAtEpochSec = wallClock.epochSeconds(),
            totalDurationMs = activeDuration,
            buddyPeerDeviceId = buddyPeerDeviceId,
        )

        cachedResult = computed
        _state = SessionState.FINISHED
        return computed
    }

    /** Why the run did not pass, in a form the UI can localise. Empty when it passed. */
    fun passFailures(): List<PassFailure> {
        val aggregate = ScoreCalculator.aggregate(sealedResults, scenario.hesitationRatioLimit)
        return ScoreCalculator.evaluatePass(aggregate, scenario).failures
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun sealStep(
        step: StepSpec,
        latencyMs: Long,
        answeredOptionIds: List<String>,
        inputMethod: InputMethod,
        answered: Boolean,
    ): StepResult {
        val correct = answered && AnswerMatcher.matches(step, answeredOptionIds)
        val outcome = ScoreCalculator.classify(
            correct = correct,
            answered = answered,
            latencyMs = latencyMs,
            expertMs = step.expertMs,
        )
        val suspicious = answered && latencyMs < AssessmentConfig.SUSPICIOUS_FAST_MS
        if (suspicious) suspiciousFastCount++

        val sealed = StepResult(
            stepId = step.stepId,
            stepIndex = stepIndex,
            kind = step.kind,
            outcome = outcome,
            latencyMs = latencyMs,
            expertMs = step.expertMs,
            timeoutMs = step.timeoutMs,
            answeredOptionIds = answeredOptionIds.toList(),
            correctOptionIds = step.correctOptionIds.toList(),
            stepScore = ScoreCalculator.stepScore(
                correct = correct,
                latencyMs = latencyMs,
                expertMs = step.expertMs,
                timeoutMs = step.timeoutMs,
            ),
            weight = step.weight,
            critical = step.critical,
            inputMethod = inputMethod,
            suspiciousFast = suspicious,
        )
        sealedResults += sealed
        advance()
        return sealed
    }

    private fun advance() {
        stepIndex++
        pausedMsForCurrentStep = 0L
        pauseStartedAtMonotonic = null
        stepStartMonotonic = monotonic.elapsedMillis()
        if (stepIndex >= scenario.steps.size) {
            _state = SessionState.STEPS_DONE
        }
    }

    private fun sealRemainingAsSkipped() {
        while (stepIndex < scenario.steps.size) {
            val step = scenario.steps[stepIndex]
            sealedResults += StepResult(
                stepId = step.stepId,
                stepIndex = stepIndex,
                kind = step.kind,
                outcome = OutcomeClass.SKIPPED,
                latencyMs = 0L,
                expertMs = step.expertMs,
                timeoutMs = step.timeoutMs,
                answeredOptionIds = emptyList(),
                correctOptionIds = step.correctOptionIds.toList(),
                stepScore = 0.0,
                weight = step.weight,
                critical = step.critical,
                inputMethod = InputMethod.NOT_ANSWERED,
                suspiciousFast = false,
            )
            stepIndex++
        }
    }
}
