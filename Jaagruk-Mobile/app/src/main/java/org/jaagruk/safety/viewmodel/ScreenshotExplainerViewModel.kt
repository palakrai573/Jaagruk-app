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

enum class ScreenshotAction(val label: String, val prompt: String) {
    EXPLAIN   ("Explain",                   "Explain clearly what this text/error means and why it happens."),
    SIMPLIFY  ("Simplify",                  "Rewrite the following in simple plain English anyone can understand."),
    FIX_ERROR ("Fix Error",                 "Identify the error in the following and provide a clear fix with explanation."),
    EXTRACT   ("Extract Key Info",          "Extract and list all the important information from the following text.")
}

class ScreenshotExplainerViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "ScreenshotVM" }

    private val repository  = AIRepository.getInstance(app)
    private val extractor    = OcrTextExtractor()
    private val libraryRepo  = LibraryRepository.getInstance(app)

    private val _showSavedBanner = MutableStateFlow(false)
    val showSavedBanner: StateFlow<Boolean> = _showSavedBanner.asStateFlow()

    val aiState: StateFlow<AIInferenceState> = repository.aiState

    private val _uiState       = MutableStateFlow<ScreenshotUiState>(ScreenshotUiState.Idle)
    val uiState: StateFlow<ScreenshotUiState> = _uiState.asStateFlow()

    private val _extractedText = MutableStateFlow("")
    val extractedText: StateFlow<String> = _extractedText.asStateFlow()

    private val _resultText    = MutableStateFlow("")
    val resultText: StateFlow<String> = _resultText.asStateFlow()

    private var job: Job? = null
    private var userStopped = false

    init { viewModelScope.launch(Dispatchers.IO) { repository.initialize() } }

    fun analyzeScreenshot(uri: Uri) {
        if (_uiState.value is ScreenshotUiState.Extracting) return
        job?.cancel()
        _extractedText.value = ""
        _resultText.value    = ""
        userStopped          = false

        job = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ScreenshotUiState.Extracting
            extractor.extract(getApplication(), uri).fold(
                onSuccess = { (text, _) ->
                    _extractedText.value = text
                    _uiState.value = ScreenshotUiState.TextReady(text)
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR failed", e)
                    _uiState.value = ScreenshotUiState.Error(e.message ?: "Failed to read screenshot")
                }
            )
        }
    }

    fun runAction(action: ScreenshotAction) {
        val text = _extractedText.value
        if (text.isBlank() || _uiState.value is ScreenshotUiState.Processing) return
        job?.cancel()
        _resultText.value = ""
        userStopped       = false

        job = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ScreenshotUiState.Processing(action)
            val prompt = AiTextProcessor.buildPrompt(action.prompt, text)
            AiTextProcessor.stream(
                repository  = repository,
                prompt      = prompt,
                outputFlow  = _resultText as MutableStateFlow<String>,
                userStopped = { userStopped },
                scope       = viewModelScope,
                onDone      = {
                    _uiState.value = ScreenshotUiState.Done(action)
                    viewModelScope.launch(Dispatchers.IO) { autoSave(action) }
                },
                onError     = { msg -> _uiState.value = ScreenshotUiState.Error(msg) }
            )
        }
    }

    private suspend fun autoSave(action: ScreenshotAction) {
        val text = _resultText.value
        if (text.isBlank()) return
        libraryRepo.save(EntryType.SCREENSHOT, text, title = "Screenshot – ${action.label}")
        _showSavedBanner.value = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_500)
            _showSavedBanner.value = false
        }
    }

    fun stop() {
        userStopped = true
        repository.stop()
        job?.cancel()
        val cur = _uiState.value
        if (cur is ScreenshotUiState.Processing) _uiState.value = ScreenshotUiState.Done(cur.action)
    }

    fun reset() {
        stop()
        _extractedText.value = ""
        _resultText.value    = ""
        _uiState.value       = ScreenshotUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        extractor.close()
        repository.stop()
        // Do not call repository.unload() — shared instance
    }
}

sealed class ScreenshotUiState {
    object Idle                                       : ScreenshotUiState()
    object Extracting                                 : ScreenshotUiState()
    data class TextReady(val text: String)            : ScreenshotUiState()
    data class Processing(val action: ScreenshotAction) : ScreenshotUiState()
    data class Done(val action: ScreenshotAction)     : ScreenshotUiState()
    data class Error(val message: String)             : ScreenshotUiState()
}
