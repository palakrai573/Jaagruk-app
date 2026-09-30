package org.jaagruk.core.assessment

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.util.FixedMonotonicTimeSource
import org.jaagruk.core.util.FixedWallClock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssessmentSessionTest {

    private lateinit var clock: FixedMonotonicTimeSource
    private lateinit var wall: FixedWallClock

    private fun session(
        scenario: ScenarioSpec = TestScenarios.threeStep(),
        mode: AssessmentMode = AssessmentMode.INITIAL,
        presentation: ArPresentation = ArPresentation.SITE_SCANNED,
        peerDeviceId: String? = null,
    ): AssessmentSession {
        clock = FixedMonotonicTimeSource(1_000_000L)
        wall = FixedWallClock(1_760_000_000_000L)
        return AssessmentSession(
            runId = "run-1",
            scenario = scenario,
            moduleCode = 1,
            mode = mode,
            presentation = presentation,
            monotonic = clock,
            wallClock = wall,
            buddyPeerDeviceId = peerDeviceId,
        )
    }

    private fun AssessmentSession.answerCurrent(
        correct: Boolean,
        afterMs: Long,
        inputMethod: InputMethod = InputMethod.TOUCH,
    ): SubmitOutcome {
        val step = requireNotNull(currentStep) { "no current step" }
        clock.advance(afterMs)
        val optionId = if (correct) {
            step.correctOptionIds.first()
        } else {
            step.options.first { it.optionId !in step.correctOptionIds }.optionId
        }
        return submitSingle(step.stepId, optionId, inputMethod)
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    fun `a clean fast run passes with a perfect score`() {
        val session = session()
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 500L) }

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.COMPLETED)
        assertThat(result.scorePermille).isEqualTo(1_000)
        assertThat(result.passed).isTrue()
        assertThat(result.certifiable).isTrue()
        assertThat(result.hesitationFlag).isFalse()
        assertThat(result.steps.map { it.outcome })
            .containsExactly(
                OutcomeClass.CORRECT_FAST,
                OutcomeClass.CORRECT_FAST,
                OutcomeClass.CORRECT_FAST,
            )
    }

    @Test
    fun `latency is measured per step not cumulatively`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 400L)
        session.answerCurrent(correct = true, afterMs = 700L)
        session.answerCurrent(correct = true, afterMs = 1_200L)

        val result = session.finish()
        assertThat(result.steps.map { it.latencyMs }).containsExactly(400L, 700L, 1_200L).inOrder()
    }

    @Test
    fun `state advances through the documented lifecycle`() {
        val session = session()
        assertThat(session.state).isEqualTo(SessionState.NOT_STARTED)
        session.start()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
        repeat(3) { session.answerCurrent(correct = true, afterMs = 300L) }
        assertThat(session.state).isEqualTo(SessionState.STEPS_DONE)
        session.finish()
        assertThat(session.state).isEqualTo(SessionState.FINISHED)
    }

    @Test
    fun `progress reflects sealed steps`() {
        val session = session()
        session.start()
        assertThat(session.progress).isWithin(1e-9).of(0.0)
        session.answerCurrent(correct = true, afterMs = 300L)
        assertThat(session.progress).isWithin(1e-9).of(1.0 / 3.0)
    }

    // -----------------------------------------------------------------------
    // Hesitation
    // -----------------------------------------------------------------------

    @Test
    fun `slow correct answers raise the hesitation flag`() {
        val session = session()
        session.start()
        // expertMs is 1000, so beyond 2000 ms is hesitation on every step.
        repeat(3) { session.answerCurrent(correct = true, afterMs = 3_000L) }

        val result = session.finish()
        assertThat(result.steps.map { it.outcome })
            .containsExactly(
                OutcomeClass.CORRECT_SLOW,
                OutcomeClass.CORRECT_SLOW,
                OutcomeClass.CORRECT_SLOW,
            )
        assertThat(result.hesitationFlag).isTrue()
        assertThat(result.hesitationRatio).isWithin(1e-9).of(1.0)
        // Entirely correct, yet not a pass: the point of measuring decision latency at all.
        assertThat(result.passed).isFalse()
    }

    @Test
    fun `a single hesitant step in three does not fail the run`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 500L)
        session.answerCurrent(correct = true, afterMs = 500L)
        session.answerCurrent(correct = true, afterMs = 3_000L)

        val result = session.finish()
        assertThat(result.hesitationRatio).isWithin(1e-9).of(1.0 / 3.0)
        assertThat(result.hesitationFlag).isFalse()
        assertThat(result.passed).isTrue()
    }

    // -----------------------------------------------------------------------
    // Timeouts
    // -----------------------------------------------------------------------

    @Test
    fun `poll seals a step that ran out of time`() {
        val session = session()
        session.start()

        assertThat(session.poll()).isNull()
        clock.advance(5_000L)
        val event = session.poll()

        assertThat(event).isInstanceOf(PollEvent.StepTimedOut::class.java)
        val timedOut = (event as PollEvent.StepTimedOut).stepResult
        assertThat(timedOut.outcome).isEqualTo(OutcomeClass.TIMEOUT)
        assertThat(timedOut.latencyMs).isEqualTo(5_000L)
        assertThat(timedOut.inputMethod).isEqualTo(InputMethod.AUTO_TIMEOUT)
        assertThat(session.currentStep?.stepId).isEqualTo("s2")
    }

    @Test
    fun `an answer arriving at or after the deadline is a timeout`() {
        val session = session()
        session.start()
        val outcome = session.answerCurrent(correct = true, afterMs = 5_000L)

        val sealed = (outcome as SubmitOutcome.Accepted).stepResult
        assertThat(sealed.outcome).isEqualTo(OutcomeClass.TIMEOUT)
        assertThat(sealed.inputMethod).isEqualTo(InputMethod.AUTO_TIMEOUT)
        // The answer text is kept for diagnostics even though it did not count.
        assertThat(sealed.answeredOptionIds).isNotEmpty()
    }

    @Test
    fun `a fully timed out run completes with a zero score`() {
        val session = session()
        session.start()
        repeat(3) {
            clock.advance(5_000L)
            session.poll()
        }

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.COMPLETED)
        assertThat(result.scorePermille).isEqualTo(0)
        assertThat(result.passed).isFalse()
        assertThat(result.timeoutCount).isEqualTo(3)
    }

    @Test
    fun `remaining time never goes negative`() {
        val session = session()
        session.start()
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(5_000L)
        clock.advance(4_000L)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(1_000L)
        clock.advance(9_000L)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(0L)
    }

    // -----------------------------------------------------------------------
    // Duplicate and stale input
    // -----------------------------------------------------------------------

    @Test
    fun `a duplicate answer for a sealed step is ignored`() {
        val session = session()
        session.start()
        val step = requireNotNull(session.currentStep)
        clock.advance(400L)

        val first = session.submitSingle(step.stepId, step.correctOptionIds.first(), InputMethod.TOUCH)
        assertThat(first).isInstanceOf(SubmitOutcome.Accepted::class.java)

        // A glove double-tap. Without naming the step, this would answer step two in 0 ms.
        val second = session.submitSingle(step.stepId, step.correctOptionIds.first(), InputMethod.TOUCH)
        assertThat(second).isInstanceOf(SubmitOutcome.Ignored::class.java)
        assertThat((second as SubmitOutcome.Ignored).reason).isEqualTo(IgnoreReason.STALE_STEP)
        assertThat(session.stepResults).hasSize(1)
        assertThat(session.currentStep?.stepId).isEqualTo("s2")
    }

    @Test
    fun `an answer for a future step is ignored`() {
        val session = session()
        session.start()
        val outcome = session.submitSingle("s3", "s3_right", InputMethod.VOICE)
        assertThat((outcome as SubmitOutcome.Ignored).reason).isEqualTo(IgnoreReason.STALE_STEP)
    }

    @Test
    fun `an empty answer is ignored rather than scored wrong`() {
        val session = session()
        session.start()
        val outcome = session.submit("s1", emptyList(), InputMethod.TOUCH)
        assertThat((outcome as SubmitOutcome.Ignored).reason).isEqualTo(IgnoreReason.EMPTY_ANSWER)
        assertThat(session.stepResults).isEmpty()
    }

    @Test
    fun `input before start and after finish is ignored`() {
        val session = session()
        assertThat((session.submitSingle("s1", "s1_right", InputMethod.TOUCH) as SubmitOutcome.Ignored).reason)
            .isEqualTo(IgnoreReason.NOT_RUNNING)

        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 300L) }
        session.finish()

        assertThat((session.submitSingle("s1", "s1_right", InputMethod.TOUCH) as SubmitOutcome.Ignored).reason)
            .isEqualTo(IgnoreReason.NOT_RUNNING)
    }

    @Test
    fun `poll does nothing once the session is over`() {
        val session = session()
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 300L) }
        clock.advance(60_000L)
        assertThat(session.poll()).isNull()
    }

    // -----------------------------------------------------------------------
    // Pause and resume
    // -----------------------------------------------------------------------

    @Test
    fun `paused time is excluded from latency`() {
        // Being interrupted by a supervisor is not hesitation.
        val session = session()
        session.start()
        clock.advance(600L)
        session.pause()
        clock.advance(120_000L)
        session.resume()
        clock.advance(300L)

        val step = requireNotNull(session.currentStep)
        val outcome = session.submitSingle(step.stepId, step.correctOptionIds.first(), InputMethod.TOUCH)
        val sealed = (outcome as SubmitOutcome.Accepted).stepResult

        assertThat(sealed.latencyMs).isEqualTo(900L)
        assertThat(sealed.outcome).isEqualTo(OutcomeClass.CORRECT_FAST)
    }

    @Test
    fun `the timeout clock is frozen while paused`() {
        val session = session()
        session.start()
        clock.advance(4_000L)
        session.pause()
        clock.advance(600_000L)

        assertThat(session.elapsedOnCurrentStepMs()).isEqualTo(4_000L)
        assertThat(session.poll()).isNull()

        session.resume()
        clock.advance(1_000L)
        assertThat(session.poll()).isInstanceOf(PollEvent.StepTimedOut::class.java)
    }

    @Test
    fun `input while paused is ignored`() {
        val session = session()
        session.start()
        session.pause()
        val outcome = session.submitSingle("s1", "s1_right", InputMethod.TOUCH)
        assertThat((outcome as SubmitOutcome.Ignored).reason).isEqualTo(IgnoreReason.NOT_RUNNING)
    }

    @Test
    fun `pause and resume are idempotent`() {
        val session = session()
        session.start()
        clock.advance(500L)
        session.pause()
        session.pause()
        clock.advance(1_000L)
        session.resume()
        session.resume()
        assertThat(session.elapsedOnCurrentStepMs()).isEqualTo(500L)
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
    }

    @Test
    fun `pause duration is reported for the resume prompt`() {
        val session = session()
        session.start()
        session.pause()
        clock.advance(90_000L)
        assertThat(session.currentPauseDurationMs()).isEqualTo(90_000L)
        assertThat(session.currentPauseDurationMs())
            .isGreaterThan(AssessmentConfig.BACKGROUND_ABORT_MS / 4)
    }

    @Test
    fun `paused steps do not accumulate pause time across steps`() {
        val session = session()
        session.start()
        clock.advance(300L)
        session.pause()
        clock.advance(10_000L)
        session.resume()
        session.answerCurrent(correct = true, afterMs = 200L)

        // The next step starts with a clean pause budget.
        clock.advance(400L)
        assertThat(session.elapsedOnCurrentStepMs()).isEqualTo(400L)
    }

    // -----------------------------------------------------------------------
    // Abort
    // -----------------------------------------------------------------------

    @Test
    fun `an abort keeps the work already done`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 400L)
        session.abort(AbortReason.PEER_LOST)

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.ABORTED)
        assertThat(result.abortReason).isEqualTo(AbortReason.PEER_LOST)
        // The one answered step still scores; the rest are skipped, not failed.
        assertThat(result.scorePermille).isEqualTo(1_000)
        assertThat(result.steps.map { it.outcome })
            .containsExactly(OutcomeClass.CORRECT_FAST, OutcomeClass.SKIPPED, OutcomeClass.SKIPPED)
        // An aborted run can never certify, however well the attempted steps went.
        assertThat(result.passed).isFalse()
        assertThat(result.certifiable).isFalse()
    }

    @Test
    fun `aborting before any answer yields an incomplete run`() {
        val session = session()
        session.start()
        session.abort(AbortReason.CAMERA_LOST)

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.ABORTED)
        assertThat(result.scorePermille).isEqualTo(0)
        assertThat(result.scoredStepCount).isEqualTo(0)
    }

    @Test
    fun `aborting a session that never started still produces a result`() {
        val session = session()
        session.abort(AbortReason.SESSION_EXPIRED)
        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.ABORTED)
        assertThat(result.steps).hasSize(3)
        assertThat(result.steps.all { it.outcome == OutcomeClass.SKIPPED }).isTrue()
    }

    @Test
    fun `finishing early is recorded as a user cancellation`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 400L)

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.ABORTED)
        assertThat(result.abortReason).isEqualTo(AbortReason.USER_CANCELLED)
    }

    @Test
    fun `finishing without ever starting is incomplete`() {
        val result = session().finish()
        assertThat(result.completion).isEqualTo(Completion.INCOMPLETE)
        assertThat(result.passed).isFalse()
        assertThat(result.abortReason).isNull()
    }

    @Test
    fun `abort after finish is a no-op`() {
        val session = session()
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 300L) }
        val first = session.finish()
        session.abort(AbortReason.PEER_LOST)
        assertThat(session.finish()).isSameInstanceAs(first)
    }

    // -----------------------------------------------------------------------
    // Guess pattern
    // -----------------------------------------------------------------------

    @Test
    fun `three sub-reaction-time answers void the run`() {
        val session = session()
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 100L) }

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.VOIDED)
        assertThat(result.voidReason).isEqualTo(VoidReason.GUESS_PATTERN)
        assertThat(result.passed).isFalse()
        assertThat(result.certifiable).isFalse()
        assertThat(result.steps.all { it.suspiciousFast }).isTrue()
    }

    @Test
    fun `two fast answers are allowed`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 100L)
        session.answerCurrent(correct = true, afterMs = 200L)
        session.answerCurrent(correct = true, afterMs = 900L)

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.COMPLETED)
        assertThat(result.passed).isTrue()
    }

    @Test
    fun `the suspicious threshold is exclusive`() {
        val session = session()
        session.start()
        repeat(3) {
            session.answerCurrent(correct = true, afterMs = AssessmentConfig.SUSPICIOUS_FAST_MS)
        }
        val result = session.finish()
        assertThat(result.steps.none { it.suspiciousFast }).isTrue()
        assertThat(result.completion).isEqualTo(Completion.COMPLETED)
    }

    // -----------------------------------------------------------------------
    // Critical steps and modes
    // -----------------------------------------------------------------------

    @Test
    fun `failing the critical step fails a high scoring run`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = false, afterMs = 300L) // s1 is critical
        session.answerCurrent(correct = true, afterMs = 300L)
        session.answerCurrent(correct = true, afterMs = 300L)

        val result = session.finish()
        assertThat(result.scorePermille).isEqualTo(667)
        assertThat(result.failedCriticalStepIds).containsExactly("s1")
        assertThat(result.passed).isFalse()
        assertThat(session.passFailures().filterIsInstance<PassFailure.CriticalStepFailed>())
            .hasSize(1)
    }

    @Test
    fun `practice mode never certifies`() {
        val session = session(mode = AssessmentMode.PRACTICE)
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 400L) }

        val result = session.finish()
        assertThat(result.passed).isTrue()
        assertThat(result.certifiable).isFalse()
    }

    @Test
    fun `a buddy scenario run solo is voided`() {
        val session = session(scenario = TestScenarios.buddyScenario())
        session.start()
        repeat(2) { session.answerCurrent(correct = true, afterMs = 400L) }

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.VOIDED)
        assertThat(result.voidReason).isEqualTo(VoidReason.BUDDY_REQUIRED_BUT_SOLO)
    }

    @Test
    fun `a buddy scenario with a real peer completes`() {
        val session = session(
            scenario = TestScenarios.buddyScenario(),
            mode = AssessmentMode.BUDDY,
            peerDeviceId = "peer-device-2",
        )
        session.start()
        repeat(2) { session.answerCurrent(correct = true, afterMs = 400L) }

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.COMPLETED)
        assertThat(result.buddyPeerDeviceId).isEqualTo("peer-device-2")
        assertThat(result.certifiable).isTrue()
    }

    @Test
    fun `buddy mode requires a peer id`() {
        assertThrows<IllegalArgumentException> {
            AssessmentSession(
                runId = "r",
                scenario = TestScenarios.buddyScenario(),
                moduleCode = 1,
                mode = AssessmentMode.BUDDY,
                presentation = ArPresentation.ARCORE_GENERIC,
                buddyPeerDeviceId = null,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Determinism and bookkeeping
    // -----------------------------------------------------------------------

    @Test
    fun `finish is idempotent`() {
        val session = session()
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 400L) }
        assertThat(session.finish()).isSameInstanceAs(session.finish())
    }

    @Test
    fun `start cannot be called twice`() {
        val session = session()
        session.start()
        assertThrows<IllegalStateException> { session.start() }
    }

    @Test
    fun `run duration excludes paused time`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 500L)
        session.pause()
        clock.advance(60_000L)
        session.resume()
        session.answerCurrent(correct = true, afterMs = 500L)
        session.answerCurrent(correct = true, afterMs = 500L)

        val result = session.finish()
        assertThat(result.totalDurationMs).isEqualTo(1_500L)
    }

    @Test
    fun `wall clock timestamps are recorded`() {
        val session = session()
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 300L) }
        wall.advanceDays(0.0)

        val result = session.finish()
        assertThat(result.startedAtEpochSec).isEqualTo(1_760_000_000L)
        assertThat(result.finishedAtEpochSec).isAtLeast(result.startedAtEpochSec)
    }

    @Test
    fun `input method is recorded per step`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 300L, inputMethod = InputMethod.VOICE)
        session.answerCurrent(correct = true, afterMs = 300L, inputMethod = InputMethod.GESTURE)
        session.answerCurrent(correct = true, afterMs = 300L, inputMethod = InputMethod.TOUCH)

        val result = session.finish()
        assertThat(result.steps.map { it.inputMethod })
            .containsExactly(InputMethod.VOICE, InputMethod.GESTURE, InputMethod.TOUCH).inOrder()
        assertThat(result.assistiveInputUsed).isTrue()
    }

    @Test
    fun `remediation lists wrong and hesitant steps`() {
        val session = session()
        session.start()
        session.answerCurrent(correct = true, afterMs = 300L)
        session.answerCurrent(correct = false, afterMs = 300L)
        session.answerCurrent(correct = true, afterMs = 4_000L)

        val result = session.finish()
        assertThat(result.remediationStepIds).containsExactly("s2", "s3")
    }

    @Test
    fun `all step kinds can be answered end to end`() {
        val session = session(scenario = TestScenarios.mixedKinds())
        session.start()

        session.submitSingle("m1", "m1_right", InputMethod.TOUCH)
        clock.advance(500L)
        session.submit("m2", listOf("m2_a", "m2_b"), InputMethod.TOUCH)
        clock.advance(500L)
        session.submit("m3", listOf("m3_1", "m3_2", "m3_3"), InputMethod.TOUCH)
        clock.advance(500L)
        session.submitSingle("m4", "m4_exit", InputMethod.AR_RETICLE)

        val result = session.finish()
        assertThat(result.completion).isEqualTo(Completion.COMPLETED)
        assertThat(result.correctCount).isEqualTo(4)
        assertThat(result.passed).isTrue()
    }

    @Test
    fun `presentation mode is carried into the result`() {
        val session = session(presentation = ArPresentation.SENSOR_FALLBACK)
        session.start()
        repeat(3) { session.answerCurrent(correct = true, afterMs = 300L) }

        val result = session.finish()
        assertThat(result.presentation).isEqualTo(ArPresentation.SENSOR_FALLBACK)
        assertThat(result.presentation.isSiteScanned).isFalse()
    }

    @Test
    fun `session rejects an invalid module code`() {
        assertThrows<IllegalArgumentException> {
            AssessmentSession(
                runId = "r",
                scenario = TestScenarios.threeStep(),
                moduleCode = 0,
                mode = AssessmentMode.INITIAL,
                presentation = ArPresentation.PICTOGRAM_2D,
            )
        }
    }

    @Test
    fun `session rejects a blank run id`() {
        assertThrows<IllegalArgumentException> {
            AssessmentSession(
                runId = " ",
                scenario = TestScenarios.threeStep(),
                moduleCode = 1,
                mode = AssessmentMode.INITIAL,
                presentation = ArPresentation.PICTOGRAM_2D,
            )
        }
    }
}
