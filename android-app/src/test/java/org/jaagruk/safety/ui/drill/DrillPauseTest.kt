package org.jaagruk.safety.ui.drill

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import org.jaagruk.core.assessment.AbortReason
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.assessment.AssessmentSession
import org.jaagruk.core.assessment.SessionState
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.speech.SpotResult
import org.jaagruk.core.speech.VoiceCommand
import org.jaagruk.core.util.FixedMonotonicTimeSource
import org.jaagruk.safety.ar.ArAvailability
import org.jaagruk.safety.ar.ArController
import org.jaagruk.safety.ar.ArControllerFactory
import org.jaagruk.safety.ar.ArState
import org.jaagruk.safety.ar.ArTrackingQuality
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.input.NarrationPlayer
import org.jaagruk.safety.input.VoiceCommandEngine
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DrillPauseTest {
    private val tracking = MutableStateFlow(ArState(quality = ArTrackingQuality.TRACKING))
    private val clock = FixedMonotonicTimeSource(1000)
    private lateinit var session: AssessmentSession
    private lateinit var model: DrillViewModel
    private lateinit var narration: NarrationPlayer
    private lateinit var assessments: AssessmentRepository
    private var finishNarration: (() -> Unit)? = null
    private val spokenKeys = mutableListOf<String>()
    private val voiceResults = MutableSharedFlow<SpotResult>(extraBufferCapacity = 4)
    private var completeAudioAutomatically = true

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    private fun startModel(autoCompleteNarration: Boolean = true) {
        completeAudioAutomatically = autoCompleteNarration
        val spec = ModuleCatalog.all.first().scenarios.first()
        session = AssessmentSession("pause-test", spec, 1, AssessmentMode.INITIAL,
            ArPresentation.ARCORE_GENERIC, monotonic = clock)
        assessments = mockk(relaxed = true)
        coEvery { assessments.startRun(any(), any(), any(), any(), any(), any(), any()) } returns
            AssessmentRepository.StartedRun(session, session.runId)
        val workers = mockk<WorkerRepository>(relaxed = true)
        coEvery { workers.find(any()) } returns null
        val ar = mockk<ArController>(relaxed = true)
        every { ar.state } returns tracking
        every { ar.capability } returns ArAvailability.Capability.ARCORE_READY
        val factory = mockk<ArControllerFactory>()
        every { factory.create(any()) } returns ar
        val voice = mockk<VoiceCommandEngine>(relaxed = true)
        every { voice.results } returns voiceResults
        narration = mockk(relaxed = true)
        every { narration.speak(any(), any(), any()) } answers {
            spokenKeys += firstArg<String>()
            finishNarration = thirdArg()
            if (completeAudioAutomatically) finishNarration?.invoke()
        }
        every { narration.state } returns MutableStateFlow(NarrationPlayer.State())
        model = DrillViewModel(assessments, workers, mockk(relaxed = true), mockk(relaxed = true),
            factory, mockk(relaxed = true), voice, narration)
        model.start("worker", spec.scenarioId, AssessmentMode.INITIAL.name)
    }

    @After fun cleanup() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun completeSequence() {
        repeat(1 + requireNotNull(session.currentStep).options.size * 2) {
            val complete = finishNarration
            finishNarration = null
            complete?.invoke()
        }
    }

    @Test fun foregroundCannotOverrideLostTracking() {
        startModel()
        tracking.value = ArState(quality = ArTrackingQuality.LOST)
        model.onBackgrounded()
        model.onForegrounded()
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        assertThat(model.state.value.paused).isTrue()
    }

    @Test fun trackingRecoveryCannotOverrideBackgroundPause() {
        startModel()
        tracking.value = ArState(quality = ArTrackingQuality.LOST)
        model.onBackgrounded()
        tracking.value = ArState(quality = ArTrackingQuality.TRACKING)
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        val remaining = session.remainingOnCurrentStepMs()
        clock.advance(20000)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining)
        model.onForegrounded()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining)
    }

    @Test fun resumeButtonCannotOverrideLostTracking() {
        startModel()
        tracking.value = ArState(quality = ArTrackingQuality.LOST)
        model.resume()
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
    }

    @Test fun initialisingCameraDoesNotConsumeDecisionTime() {
        startModel()
        tracking.value = ArState(quality = ArTrackingQuality.INITIALISING)
        val remaining = session.remainingOnCurrentStepMs()
        clock.advance(20000)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining)
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        tracking.value = ArState(quality = ArTrackingQuality.TRACKING)
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
    }

    @Test fun initialNarrationDoesNotConsumeResponseTime() {
        startModel(autoCompleteNarration = false)
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        val remaining = session.remainingOnCurrentStepMs()
        clock.advance(15000)
        model.resume()
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        completeSequence()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining)
    }

    @Test fun narrationCompletionCannotClearOtherInterruptions() {
        startModel(autoCompleteNarration = false)
        tracking.value = ArState(quality = ArTrackingQuality.LOST)
        model.onBackgrounded()
        completeSequence()
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        model.onForegrounded()
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        tracking.value = ArState(quality = ArTrackingQuality.TRACKING)
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
    }

    @Test fun narrationIncludesEveryNumberedOptionInDisplayOrder() {
        startModel()
        val step = requireNotNull(session.currentStep)
        val expected = listOf(step.promptKey) + step.options.flatMapIndexed { index, option ->
            listOf("option_number_${index + 1}", option.labelKey)
        }
        assertThat(spokenKeys).containsExactlyElementsIn(expected).inOrder()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
    }

    @Test fun finishingOnlyThePromptDoesNotStartTheTimerBeforeOptions() {
        startModel(autoCompleteNarration = false)
        finishNarration?.invoke()
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        assertThat(spokenKeys).hasSize(2)
    }

    @Test fun replayDoesNotResetTimeAndIgnoresDelayedVoiceAnswers() {
        startModel()
        clock.advance(1000)
        val remaining = session.remainingOnCurrentStepMs()
        completeAudioAutomatically = false
        fun emit(command: VoiceCommand) {
            voiceResults.tryEmit(SpotResult.Match(command, 0.0, 1.0, "test", 1.0))
        }
        emit(VoiceCommand.REPEAT)
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
        clock.advance(1000)
        emit(VoiceCommand.ONE)
        assertThat(session.currentStepIndex).isEqualTo(0)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining - 1000)
        completeSequence()
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining - 1000)
    }

    @Test fun skippingNarrationCancelsTheSequenceAndStartsReadingTime() {
        startModel(autoCompleteNarration = false)
        val oldCompletion = finishNarration
        val remaining = session.remainingOnCurrentStepMs()
        clock.advance(5000)
        model.continueWithoutAudio()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining)
        oldCompletion?.invoke()
        assertThat(spokenKeys).hasSize(1)
        clock.advance(1000)
        assertThat(session.remainingOnCurrentStepMs()).isEqualTo(remaining - 1000)
    }

    @Test fun skippingNarrationCannotOverrideTrackingOrBackgrounding() {
        startModel(autoCompleteNarration = false)
        tracking.value = ArState(quality = ArTrackingQuality.LOST)
        model.onBackgrounded()
        model.continueWithoutAudio()
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        tracking.value = ArState(quality = ArTrackingQuality.TRACKING)
        assertThat(session.state).isEqualTo(SessionState.PAUSED)
        model.onForegrounded()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
    }

    @Test fun duplicateBackgroundEventsDoNotExtendTheAllowedAbsence() {
        startModel()
        model.onBackgrounded()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(15))
        model.onBackgrounded()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        model.onForegrounded()
        assertThat(session.state).isEqualTo(SessionState.FINISHED)
    }

    @Test fun foregroundClearsThePreviousBackgroundDeadline() {
        startModel()
        model.onBackgrounded()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(15))
        model.onForegrounded()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
        model.onBackgrounded()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        model.onForegrounded()
        assertThat(session.state).isEqualTo(SessionState.RUNNING)
    }

    @Test fun repeatedStopWhileSavingPersistsTheRunOnlyOnce() {
        startModel()
        val pending = CompletableDeferred<AssessmentRepository.SavedRun>()
        coEvery { assessments.saveResult(any(), any(), any(), any()) } coAnswers { pending.await() }
        model.abort(AbortReason.USER_CANCELLED)
        model.abort(AbortReason.USER_CANCELLED)
        coVerify(exactly = 1) { assessments.saveResult(any(), any(), any(), any()) }
        assertThat(model.state.value.finishedRunId).isNull()
        assertThat(model.state.value.saving).isTrue()
        pending.complete(AssessmentRepository.SavedRun(session.finish(), null, false))
        assertThat(model.state.value.finishedRunId).isEqualTo(session.runId)
        assertThat(model.state.value.saving).isFalse()
    }

    @Test fun storageFailureIsReportedWithoutClaimingCompletionOrRetryingSideEffects() {
        startModel()
        coEvery { assessments.saveResult(any(), any(), any(), any()) } throws IllegalStateException("disk failure")
        model.abort(AbortReason.USER_CANCELLED)
        assertThat(model.state.value.finishedRunId).isNull()
        assertThat(model.state.value.saving).isFalse()
        assertThat(model.state.value.fatalMessage).isNotNull()
        model.abort(AbortReason.USER_CANCELLED)
        coVerify(exactly = 1) { assessments.saveResult(any(), any(), any(), any()) }
    }
}
