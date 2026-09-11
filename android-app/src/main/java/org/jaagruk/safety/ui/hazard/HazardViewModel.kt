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
import org.jaagruk.ai.AiCoach
import org.jaagruk.core.ai.AiLanguage
import org.jaagruk.core.ai.AiTask
import org.jaagruk.core.ai.Bm25Index
import org.jaagruk.core.ai.CorpusPassage
import org.jaagruk.core.ai.PassageScope
import org.jaagruk.safety.R
import org.jaagruk.safety.ai.CatalogResolver
import org.jaagruk.safety.ai.currentAiLanguage
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.LocalMediaStore
import org.jaagruk.safety.data.hazard.HazardCategory
import org.jaagruk.safety.data.hazard.HazardSeverity
import org.jaagruk.safety.data.repo.HazardRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.input.VoiceNoteRecorder
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.ui.components.AiPanelState
import org.jaagruk.safety.ui.components.UiMessage
import org.jaagruk.safety.ui.components.toPanelState
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HazardViewModel @Inject constructor(
    private val hazards: HazardRepository,
    private val workers: WorkerRepository,
    private val deviceProfile: DeviceProfile,
    private val recorder: VoiceNoteRecorder,
    private val media: LocalMediaStore,
    private val syncScheduler: SyncScheduler,
    private val aiCoach: AiCoach,
    private val catalogResolver: CatalogResolver,
) : ViewModel() {

    data class State(
        val category: HazardCategory? = null,
        val severity: HazardSeverity = HazardSeverity.MEDIUM,
        val note: String = "",
        val zoneLabel: String = "",
        val recording: Boolean = false,
        val recordedSeconds: Int = 0,
        val hasVoiceNote: Boolean = false,
        val hasPhoto: Boolean = false,
        val pictogramMode: Boolean = false,
        val submitting: Boolean = false,
        val filed: Boolean = false,
        val message: UiMessage? = null,
        /**
         * The advisory summary for the safety officer's worklist.
         *
         * Advisory in the strict sense: it is never stored, never uploaded, and never touches the
         * category or the severity the worker chose. Those are the worker's judgement and they are what
         * syncs. This exists because a note typed on a phone in a haulage road is often three words,
         * and a queue of three-word notes is a queue nobody triages.
         */
        val summary: AiPanelState = AiPanelState.Idle,
        /** A recent report at this site that looks like the same problem. */
        val possibleDuplicate: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var reporterWorkerId: String? = null
    private var siteId: String = ""
    private var voiceNote: File? = null
    private var photo: File? = null
    private var recordingTicker: Job? = null

    // -----------------------------------------------------------------------
    // Photo
    // -----------------------------------------------------------------------

    /**
     * Allocates the file the camera will write into, and returns it so the screen can wrap it in a
     * `FileProvider` URI.
     *
     * The file rather than the URI, because building a content URI needs a `Context` and a view model
     * that holds one is a view model that leaks an Activity. The screen owns the URI; this owns the
     * lifecycle of the bytes.
     *
     * Any previous attempt is discarded first, so retaking a photo cannot leave the first one behind.
     */
    fun preparePhotoTarget(): File {
        photo?.delete()
        val target = media.scratchPhotoFile()
        photo = target
        _state.value = _state.value.copy(hasPhoto = false, message = null)
        return target
    }

    /**
     * Records what the camera actually produced.
     *
     * A cancelled capture still returns to the app, often having created an empty file. Treating
     * "the file exists" as success would attach a zero-byte photo to the report and then spend a
     * mine-site uplink uploading it.
     */
    fun onPhotoCaptured(succeeded: Boolean) {
        val file = photo
        val usable = succeeded && file != null && file.exists() && file.length() > 0L
        if (!usable) {
            file?.delete()
            photo = null
            _state.value = _state.value.copy(
                hasPhoto = false,
                // Silent on an explicit cancel; only a failed capture is worth a message.
                message = if (succeeded) {
                    UiMessage.warning(R.string.hazard_photo_failed)
                } else {
                    _state.value.message
                },
            )
            return
        }
        _state.value = _state.value.copy(hasPhoto = true, message = null)
    }

    fun discardPhoto() {
        photo?.delete()
        photo = null
        _state.value = _state.value.copy(hasPhoto = false, message = null)
    }

    fun onCameraPermissionDenied() {
        _state.value = _state.value.copy(
            message = UiMessage.warning(R.string.hazard_camera_permission),
        )
    }

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

    // -----------------------------------------------------------------------
    // Advisory summary
    // -----------------------------------------------------------------------

    /**
     * Drafts a one-line summary for the safety officer, and flags a likely duplicate.
     *
     * Nothing here changes what gets filed. The category, the severity, the note, the photo and the
     * voice note are the worker's report and they sync exactly as entered. This only produces something
     * a safety officer reads alongside them, which is why it is safe to offer at all: the worst case is
     * a summary that is unhelpful, not a report that is wrong.
     *
     * The duplicate check is deliberately lexical and local. It compares this note against recent
     * reports from the same site using the same BM25 index the safety corpus uses, so it costs no model
     * time and works with the radio off. A false positive is harmless: the copy says "possibly the same
     * as", and filing anyway is one tap.
     */
    fun draftSummary() {
        val current = _state.value
        val category = current.category
        if (category == null || current.note.isBlank()) {
            _state.value = current.copy(
                summary = AiPanelState.NoAnswer(UiMessage.info(R.string.hazard_needs_note)),
            )
            return
        }
        val language = currentAiLanguage()
        if (language == null) {
            _state.value = current.copy(
                summary = AiPanelState.Unavailable(UiMessage.info(R.string.ai_unavailable_language)),
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(summary = AiPanelState.Working())
            val duplicate = findPossibleDuplicate(current.note)
            val outcome = aiCoach.run(
                AiTask.HazardSummary(
                    language = language,
                    categoryLabel = catalogResolver.resolve(category.labelKey)
                        .ifBlank { category.wireName },
                    severityLabel = catalogResolver.resolve(current.severity.labelKey)
                        .ifBlank { current.severity.wireName },
                    note = current.note,
                    zoneLabel = current.zoneLabel.takeIf { it.isNotBlank() },
                ),
            ) { words ->
                _state.value = _state.value.copy(summary = AiPanelState.Working(words))
            }
            _state.value = _state.value.copy(
                summary = outcome.toPanelState(),
                possibleDuplicate = duplicate,
            )
        }
    }

    /**
     * A recent report at this site whose note overlaps this one.
     *
     * Built as a one-off index rather than kept warm: a worker files a hazard rarely, and indexing thirty
     * short notes costs microseconds. Reusing [org.jaagruk.core.ai.Bm25Index] rather than writing a
     * similarity check means the Devanagari tokenisation and the stemming are the ones already covered by
     * tests.
     */
    private suspend fun findPossibleDuplicate(note: String): String? {
        if (siteId.isBlank()) return null
        val since = System.currentTimeMillis() / 1_000L - DUPLICATE_LOOKBACK_SECONDS
        val recent = hazards.recentForSite(siteId, since, DUPLICATE_CANDIDATES)
            .filter { !it.note.isNullOrBlank() }
        if (recent.isEmpty()) return null

        val language = currentAiLanguage() ?: AiLanguage.ENGLISH
        val passages = recent.mapIndexed { index, row ->
            CorpusPassage(
                passageId = row.hazardId,
                title = catalogResolver.resolve(row.category).ifBlank { row.category },
                body = row.note.orEmpty(),
                language = language,
                scope = PassageScope.GENERAL,
                sourceLabel = "hazard report ${index + 1}",
            )
        }
        val hit = Bm25Index(passages).search(note, limit = 1).firstOrNull() ?: return null
        return if (hit.matchedTermRatio >= DUPLICATE_RATIO) hit.passage.body else null
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
                photo = photo,
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
                    // Both handles released, never deleted: the repository has moved the files into
                    // managed storage under the hazard id and the row now points at them.
                    voiceNote = null
                    photo = null
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
        discardUnsubmittedMedia()
    }

    /**
     * Deletes media captured for a report that was never filed.
     *
     * Scratch files are not named after a hazard id, so `pruneTo` will never consider them deletable
     * however full the handset gets. Anything missed here stays on a shared site phone indefinitely,
     * and it is a photograph of a colleague.
     */
    private fun discardUnsubmittedMedia() {
        voiceNote?.delete()
        voiceNote = null
        photo?.delete()
        photo = null
    }

    override fun onCleared() {
        recordingTicker?.cancel()
        recorder.cancel()
        // Backing out of the screen without submitting also has to clean up. `filed` nulls both
        // handles first, so a successful report is never touched here.
        discardUnsubmittedMedia()
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 250L
        const val MAX_ZONE_LABEL = 64

        /** 14 days. Beyond that a similar report is a separate event, not a duplicate. */
        const val DUPLICATE_LOOKBACK_SECONDS = 14L * 24 * 60 * 60

        const val DUPLICATE_CANDIDATES = 30

        /**
         * Term overlap at which two notes are called possibly the same.
         *
         * Higher than the corpus relevance floor of 0.34, because the cost of being wrong runs the
         * other way here: a missed duplicate is a second report a safety officer merges in a moment,
         * while a false one tells a worker their report already exists when it does not, and a worker
         * who believes that stops reporting.
         */
        const val DUPLICATE_RATIO = 0.5
    }
}
