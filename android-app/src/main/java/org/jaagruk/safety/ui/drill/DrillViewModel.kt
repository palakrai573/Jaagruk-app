package org.jaagruk.safety.ui.drill

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.assessment.AbortReason
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.assessment.AssessmentSession
import org.jaagruk.core.assessment.IgnoreReason
import org.jaagruk.core.assessment.InputMethod
import org.jaagruk.core.assessment.PollEvent
import org.jaagruk.core.assessment.ScenarioSpec
import org.jaagruk.core.assessment.SessionState
import org.jaagruk.core.assessment.StepKind
import org.jaagruk.core.assessment.StepSpec
import org.jaagruk.core.assessment.SubmitOutcome
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.speech.SpotResult
import org.jaagruk.core.speech.VoiceCommand
import org.jaagruk.core.speech.VoiceCommandKind
import org.jaagruk.safety.R
import org.jaagruk.safety.ar.ArAvailability
import org.jaagruk.safety.ar.ArController
import org.jaagruk.safety.ar.ArControllerFactory
import org.jaagruk.safety.ar.ArTrackingQuality
import org.jaagruk.safety.ar.SceneMarker
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.input.GloveGesture
import org.jaagruk.safety.input.NarrationPlayer
import org.jaagruk.safety.input.VoiceCommandEngine
import org.jaagruk.safety.ui.LocaleManager
import org.jaagruk.safety.ui.components.UiMessage
import javax.inject.Inject

/**
 * Drives one drill.
 *
 * The engine itself is `org.jaagruk.core.assessment.AssessmentSession`, which is a plain object with no
 * coroutines, no Android and no clock of its own — so every timing rule it enforces is covered by unit
 * tests with a fake clock. This class is the shell: it ticks the session, routes touch, voice and gesture
 * into it, and pauses it when the scene stops being trustworthy.
 *
 * The pause behaviour is the part that matters most. Decision latency is the measurement this whole platform
 * rests on, and letting the clock run while tracking is lost, or while the worker has stepped out of the
 * cleared zone, would quietly turn an interruption into evidence of hesitation.
 */
@HiltViewModel
class DrillViewModel @Inject constructor(
    private val assessments: AssessmentRepository,
    private val workers: WorkerRepository,
    private val sites: SiteRepository,
    private val deviceProfile: DeviceProfile,
    private val arFactory: ArControllerFactory,
    private val voiceEngine: VoiceCommandEngine,
    private val narration: NarrationPlayer,
) : ViewModel() {

    /** One option, resolved for display. */
    data class OptionView(
        val optionId: String,
        val labelKey: String,
        val pictogram: org.jaagruk.core.catalog.Pictogram,
        val ordinal: Int,
        val selected: Boolean,
    )

    data class State(
        val loading: Boolean = true,
        val scenarioTitleKey: String? = null,
        val stepIndex: Int = 0,
        val totalSteps: Int = 0,
        val promptKey: String? = null,
        val stepKind: StepKind = StepKind.SINGLE_CHOICE,
        val options: List<OptionView> = emptyList(),
        val remainingMs: Long = 0L,
        val timeoutMs: Long = 1L,
        val paused: Boolean = false,
        val pauseReason: UiMessage? = null,
        val pictogramMode: Boolean = false,
        val capability: ArAvailability.Capability = ArAvailability.Capability.PICTOGRAM_ONLY,
        val voiceAvailable: Boolean = false,
        val gestureCandidate: GloveGesture? = null,
        val message: UiMessage? = null,
        val finishedRunId: String? = null,
        val fatalMessage: UiMessage? = null,
    ) {
        val progress: Float
            get() = if (totalSteps == 0) 0f else stepIndex.toFloat() / totalSteps.toFloat()

        val remainingFraction: Float
            get() = if (timeoutMs <= 0L) 0f else (remainingMs.toFloat() / timeoutMs.toFloat())
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var controller: ArController? = null
    private var session: AssessmentSession? = null
    private var scenario: ScenarioSpec? = null
    private var tickJob: Job? = null
    private var workerId: String = ""
    private var siteId: String = ""
    private var multiSelection = mutableSetOf<String>()
    private var siteScanned = false
    private var backgroundedAtMs: Long = 0L
    private var dwellOptionId: String? = null

    val arController: ArController? get() = controller

    // -----------------------------------------------------------------------
    // Start-up
    // -----------------------------------------------------------------------

    fun start(workerId: String, scenarioId: String, modeName: String) {
        if (session != null) return
        this.workerId = workerId

        viewModelScope.launch {
            val spec = ModuleCatalog.scenario(scenarioId)
            if (spec == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    fatalMessage = UiMessage.error(R.string.drill_unknown_scenario),
                )
                return@launch
            }
            scenario = spec

            val module = ModuleCatalog.byId(spec.moduleId)
            if (module == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    fatalMessage = UiMessage.error(R.string.drill_unknown_scenario),
                )
                return@launch
            }

            val worker = workers.find(workerId)
            siteId = worker?.siteId ?: deviceProfile.activeSiteId().orEmpty()
            siteScanned = siteId.isNotBlank() && sites.isSiteScanned(siteId)

            val mode = runCatching { AssessmentMode.valueOf(modeName) }
                .getOrDefault(AssessmentMode.INITIAL)

            // A buddy scenario reached without a paired peer must not run as a solo drill that then
            // silently voids at the end. The screen refuses up front and says why.
            if (spec.requiresBuddy && mode != AssessmentMode.BUDDY) {
                _state.value = _state.value.copy(
                    loading = false,
                    fatalMessage = UiMessage.error(R.string.drill_needs_buddy),
                )
                return@launch
            }

            val preferFlat = worker?.pictogramMode == true && FLAT_FOR_PICTOGRAM_MODE
            val ar = arFactory.create(preferFlat = preferFlat)
            controller = ar

            // The presentation is decided by what the AR layer can actually achieve, never requested by the
            // UI, because it is signed into the certificate.
            val presentation = if (siteScanned && ar.capability == ArAvailability.Capability.ARCORE_READY) {
                ArPresentation.ARCORE_GENERIC
            } else {
                ar.capability.basePresentation()
            }

            val started = assessments.startRun(
                workerId = workerId,
                siteId = siteId,
                scenario = spec,
                moduleCode = module.moduleCode,
                mode = mode,
                presentation = presentation,
            )
            session = started.session

            narration.prepare(worker?.preferredLanguage ?: LocaleManager.current())

            val voice = voiceEngine.prepare(
                languageTag = worker?.preferredLanguage ?: LocaleManager.current(),
            )
            if (voice == VoiceCommandEngine.Availability.READY) voiceEngine.startListening()

            _state.value = _state.value.copy(
                loading = false,
                scenarioTitleKey = spec.titleKey,
                totalSteps = spec.steps.size,
                pictogramMode = worker?.pictogramMode == true,
                capability = ar.capability,
                voiceAvailable = voice == VoiceCommandEngine.Availability.READY,
            )

            started.session.start()
            presentCurrentStep()
            observeAr()
            observeVoice()
            startTicking()
        }
    }

    private fun observeAr() {
        val ar = controller ?: return
        viewModelScope.launch {
            ar.state.collect { arState ->
                // Tracking loss pauses the drill. Scoring a decision against a frozen scene would be
                // measuring the phone, not the worker.
                val unusable = !arState.quality.isUsable &&
                    arState.quality != ArTrackingQuality.INITIALISING
                if (unusable) {
                    pause(UiMessage.warning(R.string.drill_paused_tracking))
                } else if (_state.value.paused && _state.value.pauseReason?.resId == R.string.drill_paused_tracking) {
                    resume()
                }

                arState.failureMessageKey?.let { key ->
                    if (key == AR_GAVE_UP_KEY) {
                        _state.value = _state.value.copy(
                            message = UiMessage.warning(R.string.drill_ar_gave_up),
                        )
                    }
                }

                handleDwell(arState.reticleOptionId, arState.dwellProgress)
            }
        }
    }

    private fun observeVoice() {
        viewModelScope.launch {
            voiceEngine.results.collect { result ->
                when (result) {
                    is SpotResult.Match -> applyVoiceCommand(result.command)

                    // A rejection is reported, not swallowed. "I heard you but could not tell which" is
                    // useful; silence makes the app look deaf and stops people using voice at all.
                    is SpotResult.Rejected ->
                        _state.value = _state.value.copy(
                            message = UiMessage.info(R.string.drill_voice_unclear),
                        )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Step presentation
    // -----------------------------------------------------------------------

    private fun presentCurrentStep() {
        val active = session ?: return
        val step = active.currentStep
        if (step == null) {
            finish()
            return
        }

        multiSelection = mutableSetOf()

        val markers = step.options.mapNotNull { option ->
            val target = option.arTargetKey ?: return@mapNotNull null
            SceneMarker(
                optionId = option.optionId,
                targetKey = target,
                pictogram = option.pictogram,
                label = null,
                highlighted = false,
            )
        }

        val ar = controller
        if (ar != null) {
            if (markers.isEmpty()) ar.clearMarkers() else ar.setMarkers(siteId, markers)
            // A dwell is armed against whatever the worker looks at, not against the correct answer. Arming
            // it on the right option would make holding the wrong one impossible, which would turn a
            // sustained-attention test into a free hint.
            ar.cancelDwell()
        }

        _state.value = _state.value.copy(
            stepIndex = active.currentStepIndex,
            promptKey = step.promptKey,
            stepKind = step.kind,
            timeoutMs = step.timeoutMs,
            remainingMs = active.remainingOnCurrentStepMs(),
            options = step.toOptionViews(emptySet()),
            message = null,
        )

        // The prompt is read aloud automatically. For a worker who cannot read it, the audio *is* the
        // question, so waiting for them to press a speaker button would be waiting for something they
        // cannot know is there.
        narrateCurrentPrompt(step)
    }

    private fun narrateCurrentPrompt(step: StepSpec) {
        // Fallback text is resolved in the composable; the key alone lets the player pick a bundled
        // recording, which is the only path that works for Santali.
        narration.speak(step.promptKey, "")
    }

    private fun StepSpec.toOptionViews(selected: Set<String>): List<OptionView> =
        options.mapIndexed { index, option ->
            OptionView(
                optionId = option.optionId,
                labelKey = option.labelKey,
                pictogram = option.pictogram,
                ordinal = index + 1,
                selected = option.optionId in selected,
            )
        }

    // -----------------------------------------------------------------------
    // Ticking
    // -----------------------------------------------------------------------

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val active = session ?: continue
                if (active.state == SessionState.FINISHED) break

                when (val event = active.poll()) {
                    is PollEvent.StepTimedOut -> {
                        _state.value = _state.value.copy(
                            message = UiMessage.warning(R.string.drill_timed_out),
                        )
                        if (event.isLastStep) finish() else presentCurrentStep()
                    }

                    null -> _state.value = _state.value.copy(
                        remainingMs = active.remainingOnCurrentStepMs(),
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    fun onOptionTapped(optionId: String) {
        val active = session ?: return
        val step = active.currentStep ?: return

        when (step.kind) {
            StepKind.MULTI_SELECT, StepKind.SEQUENCE -> {
                // Sequence steps keep insertion order, which is the answer; multi-select toggles.
                if (step.kind == StepKind.SEQUENCE) {
                    if (!multiSelection.add(optionId)) multiSelection.remove(optionId)
                } else if (!multiSelection.add(optionId)) {
                    multiSelection.remove(optionId)
                }
                _state.value = _state.value.copy(options = step.toOptionViews(multiSelection))
            }

            else -> submit(step, listOf(optionId), InputMethod.TOUCH)
        }
    }

    /** Confirms a multi-select or sequence answer. */
    fun onConfirmSelection() {
        val active = session ?: return
        val step = active.currentStep ?: return
        if (multiSelection.isEmpty()) {
            _state.value = _state.value.copy(
                message = UiMessage.info(R.string.drill_select_something),
            )
            return
        }
        submit(step, multiSelection.toList(), InputMethod.TOUCH)
    }

    /** A tap on the AR scene rather than on a card. */
    fun onSceneTapped(x: Float, y: Float) {
        val optionId = controller?.hitTest(x, y) ?: return
        onOptionTapped(optionId)
    }

    fun onGesture(gesture: GloveGesture) {
        val active = session ?: return
        val step = active.currentStep ?: return

        when (gesture) {
            // Pointing selects whatever is under the reticle, which is the whole point of gesture input in
            // an AR scene: no reaching for the glass at all.
            GloveGesture.POINTING_UP ->
                controller?.state?.value?.reticleOptionId?.let { optionId ->
                    submit(step, listOf(optionId), InputMethod.GESTURE)
                }

            GloveGesture.OPEN_PALM ->
                if (multiSelection.isNotEmpty()) {
                    submit(step, multiSelection.toList(), InputMethod.GESTURE)
                } else {
                    step.options.firstOrNull { it.pictogram == org.jaagruk.core.catalog.Pictogram.ANSWER_YES }
                        ?.let { submit(step, listOf(it.optionId), InputMethod.GESTURE) }
                }

            GloveGesture.CLOSED_FIST ->
                step.options.firstOrNull { it.pictogram == org.jaagruk.core.catalog.Pictogram.ANSWER_NO }
                    ?.let { submit(step, listOf(it.optionId), InputMethod.GESTURE) }

            GloveGesture.THUMB_UP -> onConfirmSelection()

            GloveGesture.THUMB_DOWN -> {
                multiSelection.clear()
                _state.value = _state.value.copy(options = step.toOptionViews(emptySet()))
            }
        }
    }

    /**
     * Applies a recognised command.
     *
     * The mapping is per kind rather than per command so a site that enrols only part of the vocabulary
     * still gets coherent behaviour from what it has. Where a command cannot apply to the current step, the
     * worker is told rather than ignored — silence is what makes people stop using voice.
     */
    private fun applyVoiceCommand(command: VoiceCommand) {
        val active = session ?: return
        val step = active.currentStep ?: return

        when (command.kind) {
            VoiceCommandKind.OPTION_INDEX -> {
                val index = (command.optionIndex ?: return) - 1
                val option = step.options.getOrNull(index)
                if (option == null) {
                    notApplicable()
                } else {
                    submit(step, listOf(option.optionId), InputMethod.VOICE)
                }
            }

            // Directions map onto the arrow pictograms, which is how a spatial step's options are drawn.
            VoiceCommandKind.DIRECTION -> {
                val wanted = when (command) {
                    VoiceCommand.LEFT -> org.jaagruk.core.catalog.Pictogram.ARROW_LEFT
                    VoiceCommand.RIGHT -> org.jaagruk.core.catalog.Pictogram.ARROW_RIGHT
                    else -> org.jaagruk.core.catalog.Pictogram.ARROW_STRAIGHT
                }
                val option = step.options.firstOrNull { it.pictogram == wanted }
                if (option == null) notApplicable() else {
                    submit(step, listOf(option.optionId), InputMethod.VOICE)
                }
            }

            VoiceCommandKind.CONFIRM -> when (command) {
                VoiceCommand.YES ->
                    if (multiSelection.isNotEmpty()) {
                        onConfirmSelection()
                    } else {
                        selectByPictogram(step, org.jaagruk.core.catalog.Pictogram.ANSWER_YES)
                    }

                else -> selectByPictogram(step, org.jaagruk.core.catalog.Pictogram.ANSWER_NO)
            }

            VoiceCommandKind.NAVIGATION -> when (command) {
                VoiceCommand.NEXT -> onConfirmSelection()

                VoiceCommand.BACK -> {
                    // Clears the working selection. It deliberately does not go back a step: a sealed step
                    // has a measured latency, and letting a worker retry it would make the measurement
                    // meaningless.
                    multiSelection.clear()
                    _state.value = _state.value.copy(options = step.toOptionViews(emptySet()))
                }

                else -> abort(AbortReason.USER_CANCELLED)
            }

            // Keywords name the thing directly, which is how a Santali speaker answers without needing to
            // count options. Matched on the option's own label key, so the scenario needs to know nothing
            // about voice.
            VoiceCommandKind.KEYWORD -> {
                val option = step.options.firstOrNull {
                    it.labelKey.contains(command.commandKey, ignoreCase = true)
                }
                if (option == null) notApplicable() else {
                    submit(step, listOf(option.optionId), InputMethod.VOICE)
                }
            }

            VoiceCommandKind.UTILITY -> when (command) {
                VoiceCommand.REPEAT -> narrateCurrentPrompt(step)
                else -> _state.value = _state.value.copy(
                    message = UiMessage.info(R.string.drill_help_hint),
                )
            }
        }
    }

    /**
     * Arms and completes an AR dwell.
     *
     * A dwell step asks the worker to hold their attention on one thing for a sustained moment — the AR
     * equivalent of "keep looking at the assembly point marker until you are sure". The controller resets
     * progress whenever they look away, so credit cannot accumulate across glances.
     */
    private fun handleDwell(reticleOptionId: String?, dwellProgress: Float) {
        val active = session ?: return
        val step = active.currentStep ?: return
        if (step.kind != StepKind.AR_DWELL || step.dwellMs <= 0L) return
        val ar = controller ?: return

        if (reticleOptionId == null) {
            if (dwellOptionId != null) {
                ar.cancelDwell()
                dwellOptionId = null
            }
            return
        }

        if (reticleOptionId != dwellOptionId) {
            dwellOptionId = reticleOptionId
            ar.beginDwell(reticleOptionId, step.dwellMs)
            return
        }

        if (dwellProgress >= 1f) {
            dwellOptionId = null
            ar.cancelDwell()
            submit(step, listOf(reticleOptionId), InputMethod.AR_RETICLE)
        }
    }

    private fun selectByPictogram(step: StepSpec, pictogram: org.jaagruk.core.catalog.Pictogram) {
        val option = step.options.firstOrNull { it.pictogram == pictogram }
        if (option == null) notApplicable() else {
            submit(step, listOf(option.optionId), InputMethod.VOICE)
        }
    }

    private fun notApplicable() {
        _state.value = _state.value.copy(
            message = UiMessage.info(R.string.drill_voice_not_applicable),
        )
    }

    private fun submit(step: StepSpec, optionIds: List<String>, method: InputMethod) {
        val active = session ?: return

        when (val outcome = active.submit(step.stepId, optionIds, method)) {
            is SubmitOutcome.Accepted -> {
                controller?.cancelDwell()
                if (outcome.isLastStep) finish() else presentCurrentStep()
            }

            is SubmitOutcome.Ignored -> when (outcome.reason) {
                // A glove double-tap, or a voice command that landed just after the step closed. Explicitly
                // ignored rather than accidentally answering the next step in zero milliseconds — which is
                // how a scoring engine ends up certifying somebody who never saw the question.
                IgnoreReason.STALE_STEP -> Unit

                IgnoreReason.EMPTY_ANSWER -> _state.value = _state.value.copy(
                    message = UiMessage.info(R.string.drill_select_something),
                )

                IgnoreReason.NOT_RUNNING -> Unit
            }
        }
    }

    // -----------------------------------------------------------------------
    // Pause and finish
    // -----------------------------------------------------------------------

    fun pause(reason: UiMessage) {
        val active = session ?: return
        if (active.state != SessionState.RUNNING) return
        active.pause()
        voiceEngine.stopListening()
        _state.value = _state.value.copy(paused = true, pauseReason = reason)
    }

    fun resume() {
        val active = session ?: return
        if (active.state != SessionState.PAUSED) return
        active.resume()
        if (_state.value.voiceAvailable) voiceEngine.startListening()
        _state.value = _state.value.copy(paused = false, pauseReason = null)
    }

    /** Called when the app is backgrounded. Pauses immediately; long absences abort. */
    fun onBackgrounded() {
        backgroundedAtMs = System.currentTimeMillis()
        pause(UiMessage.info(R.string.drill_paused_backgrounded))
    }

    fun onForegrounded() {
        val away = System.currentTimeMillis() - backgroundedAtMs
        if (backgroundedAtMs > 0L && away > MAX_BACKGROUND_MS) {
            // Twenty minutes away is not an interruption, it is a different session. The partial run is
            // still scored and saved; it simply cannot certify.
            abort(AbortReason.APP_BACKGROUNDED_TOO_LONG)
            return
        }
        resume()
    }

    fun abort(reason: AbortReason) {
        val active = session ?: return
        active.abort(reason)
        finish()
    }

    private fun finish() {
        val active = session ?: return
        if (_state.value.finishedRunId != null) return

        tickJob?.cancel()
        voiceEngine.stopListening()
        narration.stop()
        controller?.clearMarkers()

        viewModelScope.launch {
            val result = active.finish()
            val saved = assessments.saveResult(
                result = result,
                workerId = workerId,
                siteId = siteId,
                siteScannedAr = siteScanned,
            )

            if (saved.certificatePending) {
                Log.i(TAG, "run ${result.runId} passed but no site key is enrolled yet")
            }
            (saved.certificate as? CertificateRepository.IssueResult.Issued)?.let {
                Log.i(TAG, "issued certificate at seq ${it.certificate.seq}")
            }

            _state.value = _state.value.copy(finishedRunId = result.runId)
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        tickJob?.cancel()
        voiceEngine.stopListening()
        narration.stop()
        controller?.detach()
        // A run that was still open when the screen went away is sealed as aborted rather than left
        // dangling. The steps already answered keep their measured latencies.
        session?.takeIf { it.state != SessionState.FINISHED }?.let { active ->
            active.abort(AbortReason.SESSION_EXPIRED)
            viewModelScope.launch {
                runCatching {
                    assessments.saveResult(active.finish(), workerId, siteId, siteScanned)
                }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "DrillViewModel"

        /** 100 ms. Fine enough for a visible countdown, coarse enough not to burn battery. */
        const val TICK_MS = 100L

        /** Beyond this, a backgrounded run is aborted rather than resumed. */
        const val MAX_BACKGROUND_MS = 20 * 60 * 1_000L

        /**
         * Whether zero-text mode also forces the flat drill.
         *
         * False: pictogram mode is about *text*, not about AR. A worker who cannot read still benefits from
         * pointing at the real exit, and taking AR away from them would be the wrong inference.
         */
        const val FLAT_FOR_PICTOGRAM_MODE = false

        /** Matches the key published by `ArCoreController` when the tracking coach gives up. */
        const val AR_GAVE_UP_KEY = "ar_error_tracking_gave_up"
    }
}
