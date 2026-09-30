package org.jaagruk.safety.circle

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.jaagruk.safety.ai.repository.AIRepository
import org.jaagruk.safety.data.library.EntryType
import org.jaagruk.safety.data.library.LibraryRepository
import org.jaagruk.safety.ocr.AiTextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CircleLearnViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "CircleLearnVM" }

    // Own AIRepository — isolated from ChatViewModel and all other feature VMs
    private val repository  = AIRepository.getInstance(app)
    private val libraryRepo = LibraryRepository.getInstance(app)
    private val processor   = CircleLearnProcessor()

    // ── State ──────────────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow<CircleUiState>(CircleUiState.Idle)
    val uiState: StateFlow<CircleUiState> = _uiState.asStateFlow()

    private val _ocrText    = MutableStateFlow("")
    val ocrText: StateFlow<String> = _ocrText.asStateFlow()

    private val _resultText = MutableStateFlow("")
    val resultText: StateFlow<String> = _resultText.asStateFlow()

    private val _detection  = MutableStateFlow<ContentTypeDetector.DetectionResult?>(null)
    val detection: StateFlow<ContentTypeDetector.DetectionResult?> = _detection.asStateFlow()

    private val _savedToVault = MutableStateFlow(false)
    val savedToVault: StateFlow<Boolean> = _savedToVault.asStateFlow()

    private var job: Job? = null
    private var userStopped = false

    init { viewModelScope.launch(Dispatchers.IO) { repository.initialize() } }

    // ── Phase 5+6: Crop bitmap + OCR ──────────────────────────────────────────

    fun processRegion(bitmap: Bitmap, region: Rect) {
        job?.cancel()
        _ocrText.value    = ""
        _resultText.value = ""
        _detection.value  = null
        userStopped       = false
        _savedToVault.value = false

        job = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = CircleUiState.Processing

            processor.process(bitmap, region).fold(
                onSuccess = { result ->
                    _ocrText.value   = result.text
                    _detection.value = result.detection
                    _uiState.value   = CircleUiState.OcrDone(result)
                    Log.i(TAG, "OCR done: ${result.text.length} chars, type=${result.detection.type}")
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR failed", e)
                    _uiState.value = CircleUiState.Error(e.message ?: "Failed to read selection")
                }
            )
        }
    }

    // ── Phase 7: Run AI action ─────────────────────────────────────────────────

    fun runAction(action: CircleAction) {
        if (action == CircleAction.SAVE_TO_VAULT) { saveToVault(); return }
        val text = _ocrText.value
        if (text.isBlank() || _uiState.value is CircleUiState.Generating) return

        job?.cancel()
        _resultText.value = ""
        userStopped       = false

        job = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = CircleUiState.Generating(action)
            val prompt = AiTextProcessor.buildPrompt(action.prompt, text)

            AiTextProcessor.stream(
                repository  = repository,
                prompt      = prompt,
                outputFlow  = _resultText as MutableStateFlow<String>,
                userStopped = { userStopped },
                scope       = viewModelScope,
                onDone      = {
                    _uiState.value = CircleUiState.Done(action)
                    viewModelScope.launch(Dispatchers.IO) { autoSave(action) }
                },
                onPartial   = {
                    _uiState.value = CircleUiState.Done(action)
                    viewModelScope.launch(Dispatchers.IO) { autoSave(action) }
                },
                onError     = { msg -> _uiState.value = CircleUiState.Error(msg) }
            )
        }
    }

    fun stop() {
        userStopped = true
        repository.stop()
        job?.cancel()
        val cur = _uiState.value
        if (cur is CircleUiState.Generating) _uiState.value = CircleUiState.Done(cur.action)
    }

    // ── Phase 9: Save to vault ─────────────────────────────────────────────────

    fun saveToVault() {
        val text = _resultText.value.ifBlank { _ocrText.value }
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepo.save(
                type       = EntryType.OCR,
                content    = text,
                title      = "Circle Learn – ${_detection.value?.type?.name ?: "Scan"}",
                sourceInfo = "Circle Learn"
            )
            _savedToVault.value = true
            kotlinx.coroutines.delay(2_500)
            _savedToVault.value = false
        }
    }

    private suspend fun autoSave(action: CircleAction) {
        val text = _resultText.value
        if (text.isBlank()) return
        val type = when (action) {
            CircleAction.NOTES, CircleAction.FLASHCARDS -> EntryType.NOTE
            CircleAction.QUIZ, CircleAction.PRACTICE_QUESTIONS,
            CircleAction.INTERVIEW_QUESTIONS            -> EntryType.QUIZ
            else                                        -> EntryType.OCR
        }
        libraryRepo.save(type, text,
            title      = "Circle: ${action.label}",
            sourceInfo = "Circle Learn"
        )
        Log.i(TAG, "Auto-saved to Library as $type")
    }

    fun reset() {
        stop()
        _ocrText.value      = ""
        _resultText.value   = ""
        _detection.value    = null
        _uiState.value      = CircleUiState.Idle
        _savedToVault.value = false
    }

    /** Called by service when screen capture fails before OCR starts. */
    fun setError(message: String) {
        _uiState.value = CircleUiState.Error(message)
    }

    override fun onCleared() {
        super.onCleared()
        processor.close()
        repository.stop()
    }
}

// ── UI state ───────────────────────────────────────────────────────────────────

sealed class CircleUiState {
    object Idle                                              : CircleUiState()
    object Processing                                        : CircleUiState()
    data class OcrDone(val result: CircleLearnProcessor.ProcessResult) : CircleUiState()
    data class Generating(val action: CircleAction)          : CircleUiState()
    data class Done(val action: CircleAction)                : CircleUiState()
    data class Error(val message: String)                    : CircleUiState()
}
