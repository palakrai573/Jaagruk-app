package org.jaagruk.safety.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.jaagruk.safety.ai.repository.AIRepository
import org.jaagruk.safety.ai.state.AIInferenceState
import org.jaagruk.safety.data.library.EntryType
import org.jaagruk.safety.data.library.LibraryRepository
import org.jaagruk.safety.ocr.AiTextProcessor
import org.jaagruk.safety.ocr.OcrTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OcrAction(val label: String, val prompt: String) {
    SUMMARIZE  ("Summarize",       "Summarize the following text in 5 concise bullet points:"),
    KEY_POINTS ("Key Points",      "List the 5 most important key points from the following text."),
    EXPLAIN    ("Explain",         "Explain the following text in simple, clear language."),
    TO_NOTES   ("Convert to Notes","Convert the following text into organized study notes with headings.")
}

class OcrViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "OcrViewModel" }

    private val repository = AIRepository.getInstance(app)
    private val extractor  = OcrTextExtractor()
    private val libraryRepo = LibraryRepository.getInstance(app)

    private val _showSavedBanner = MutableStateFlow(false)
    val showSavedBanner: StateFlow<Boolean> = _showSavedBanner.asStateFlow()

    private val _truncationNotice = MutableStateFlow(false)
    val truncationNotice: StateFlow<Boolean> = _truncationNotice.asStateFlow()

    private val _statusLabel = MutableStateFlow("Processing...")
    val statusLabel: StateFlow<String> = _statusLabel.asStateFlow()

    private val _tokenCount = MutableStateFlow(0)
    val tokenCount: StateFlow<Int> = _tokenCount.asStateFlow()

    val aiState: StateFlow<AIInferenceState> = repository.aiState

    private val _uiState    = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    private val _extractedText = MutableStateFlow("")
    val extractedText: StateFlow<String> = _extractedText.asStateFlow()

    private val _resultText = MutableStateFlow("")
    val resultText: StateFlow<String> = _resultText.asStateFlow()

    private var job: Job? = null
    private var userStopped = false

    init { viewModelScope.launch(Dispatchers.IO) { repository.initialize() } }

    fun extractText(uri: Uri) {
        if (_uiState.value is OcrUiState.Extracting) return
        job?.cancel()
        _extractedText.value = ""
        _resultText.value    = ""
        userStopped          = false

        job = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = OcrUiState.Extracting
            extractor.extract(getApplication(), uri).fold(
                onSuccess = { (text, wasTruncated) ->
                    _extractedText.value    = text
                    _truncationNotice.value = wasTruncated
                    _uiState.value          = OcrUiState.TextReady(text)
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR failed", e)
                    _uiState.value = OcrUiState.Error(e.message ?: "Failed to read image")
                }
            )
        }
    }

    fun runAction(action: OcrAction) {
        val text = _extractedText.value
        if (text.isBlank()) return
        if (_uiState.value is OcrUiState.Processing) return
        job?.cancel()
        _resultText.value = ""
        userStopped       = false

        job = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = OcrUiState.Processing(action)
            _statusLabel.value = "Analyzing text..."
            _tokenCount.value  = 0
            val prompt = AiTextProcessor.buildPrompt(action.prompt, text)
            AiTextProcessor.stream(
                repository  = repository,
                prompt      = prompt,
                outputFlow  = _resultText as MutableStateFlow<String>,
                userStopped = { userStopped },
                scope       = viewModelScope,
                onDone      = {
                    _uiState.value = OcrUiState.Done(action)
                    viewModelScope.launch(Dispatchers.IO) { autoSave(action, partial = false) }
                },
                onPartial   = {
                    _uiState.value = OcrUiState.Partial(action)
                    viewModelScope.launch(Dispatchers.IO) { autoSave(action, partial = true) }
                },
                onError     = { msg -> _uiState.value = OcrUiState.Error(msg) }
            )
        }
    }

    fun stop() {
        userStopped = true
        repository.stop()
        job?.cancel()
        val cur = _uiState.value
        if (cur is OcrUiState.Processing) _uiState.value = OcrUiState.Done(cur.action)
    }

    private suspend fun autoSave(action: OcrAction, partial: Boolean = false) {
        val text = _resultText.value
        if (text.isBlank()) return
        libraryRepo.save(EntryType.OCR, text, title = "OCR – ${action.label}")
        if (!partial) {
            _showSavedBanner.value = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(2_500)
                _showSavedBanner.value = false
            }
        }
    }

    fun reset() {
        stop()
        _extractedText.value = ""
        _resultText.value    = ""
        _uiState.value       = OcrUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        extractor.close()
        repository.stop()
        // Do not call repository.unload() — shared instance, ChatViewModel owns lifecycle
    }
}

sealed class OcrUiState {
    object Idle                                      : OcrUiState()
    object Extracting                                : OcrUiState()
    data class TextReady(val text: String)           : OcrUiState()
    data class Processing(val action: OcrAction)     : OcrUiState()
    data class Done(val action: OcrAction)           : OcrUiState()
    data class Partial(val action: OcrAction)        : OcrUiState()  // tokens received, stream ended early
    data class Error(val message: String)            : OcrUiState()
}
