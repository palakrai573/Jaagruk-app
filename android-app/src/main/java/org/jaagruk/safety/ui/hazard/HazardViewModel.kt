package org.jaagruk.safety.ui.hazard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.safety.R
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.hazard.HazardCategory
import org.jaagruk.safety.data.hazard.HazardSeverity
import org.jaagruk.safety.data.repo.HazardRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.input.VoiceNoteRecorder
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.ui.components.UiMessage
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HazardViewModel @Inject constructor(
    private val hazards: HazardRepository,
    private val workers: WorkerRepository,
    private val deviceProfile: DeviceProfile,
    private val recorder: VoiceNoteRecorder,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    data class State(
        val category: HazardCategory? = null,
        val severity: HazardSeverity = HazardSeverity.MEDIUM,
        val note: String = "",
        val zoneLabel: String = "",
        val recording: Boolean = false,
        val recordedSeconds: Int = 0,
        val hasVoiceNote: Boolean = false,
        val pictogramMode: Boolean = false,
        val submitting: Boolean = false,
        val filed: Boolean = false,
        val message: UiMessage? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var reporterWorkerId: String? = null
    private var siteId: String = ""
    private var voiceNote: File? = null
    private var recordingTicker: Job? = null

    fun load(workerId: String?) {
        reporterWorkerId = workerId?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            val worker = workerId?.let { workers.find(it) }
            siteId = worker?.siteId ?: deviceProfile.activeSiteId().orEmpty()
            _state.value = _state.value.copy(pictogramMode = worker?.pictogramMode == true)
        }
    }

    fun selectCategory(category: HazardCategory) {
        _state.value = _state.value.copy(category = category, message = null)
    }

    fun selectSeverity(severity: HazardSeverity) {
        _state.value = _state.value.copy(severity = severity)
    }

    fun setNote(value: String) {
        _state.value = _state.value.copy(note = value.take(HazardRepository.MAX_NOTE_LENGTH))
    }

    fun setZoneLabel(value: String) {
        _state.value = _state.value.copy(zoneLabel = value.take(MAX_ZONE_LABEL))
    }

    fun startRecording() {
        if (!recorder.hasPermission()) {
            _state.value = _state.value.copy(
                message = UiMessage.warning(R.string.hazard_mic_permission),
            )
            return
        }
        if (!recorder.start()) {
            _state.value = _state.value.copy(
                message = UiMessage.warning(R.string.hazard_recorder_unavailable),
            )
            return
        }

        _state.value = _state.value.copy(recording = true, recordedSeconds = 0, message = null)
        recordingTicker = viewModelScope.launch {
            while (recorder.tick()) {
                delay(TICK_MS)
                _state.value = _state.value.copy(
                    recordedSeconds = (recorder.state.value.elapsedMs / 1000).toInt(),
                )
            }
            // The recorder hit its own cap. Treated as a normal stop rather than an error.
            finishRecording()
        }
    }

    fun stopRecording() {
        recordingTicker?.cancel()
        finishRecording()
    }

    private fun finishRecording() {
        val file = recorder.stop()
        voiceNote = file
        _state.value = _state.value.copy(
            recording = false,
            hasVoiceNote = file != null,
            message = if (file == null) {
                UiMessage.info(R.string.hazard_voice_too_short)
            } else {
                null
            },
        )
    }

    fun submit() {
        val current = _state.value
        val category = current.category ?: return
        _state.value = current.copy(submitting = true, message = null)

        viewModelScope.launch {
            val result = hazards.report(
                siteId = siteId,
                reporterWorkerId = reporterWorkerId,
                category = category,
                severity = current.severity,
                note = current.note,
                // No coordinates. Underground there is no fix, and a wrong one is worse than none because it
                // puts a pin on a map somewhere the hazard is not.
                latitude = null,
                longitude = null,
                zoneLabel = current.zoneLabel,
                arAnchorId = null,
                photo = null,
                voiceNote = voiceNote,
            )

            when (result) {
                is HazardRepository.ReportResult.Filed -> {
                    // A severe hazard is worth trying to deliver immediately rather than waiting for the next
                    // sync window. A blocked escape route found at the start of a night shift is not something
                    // to learn about six hours later.
                    if (hazards.warrantsImmediateRelay(current.severity)) {
                        syncScheduler.requestSyncNow()
                    }
                    voiceNote = null
                    _state.value = _state.value.copy(submitting = false, filed = true)
                }

                is HazardRepository.ReportResult.RateLimited ->
                    _state.value = _state.value.copy(
                        submitting = false,
                        message = UiMessage.warning(
                            R.string.hazard_rate_limited,
                            result.recentCount,
                            result.secondsUntilNext / 60,
                        ),
                    )

                is HazardRepository.ReportResult.Invalid ->
                    _state.value = _state.value.copy(
                        submitting = false,
                        message = UiMessage.error(R.string.hazard_invalid, result.reason),
                    )
            }
        }
    }

    fun cancel() {
        recordingTicker?.cancel()
        recorder.cancel()
        voiceNote?.delete()
        voiceNote = null
    }

    override fun onCleared() {
        recordingTicker?.cancel()
        recorder.cancel()
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 250L
        const val MAX_ZONE_LABEL = 64
    }
}
