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
import org.jaagruk.safety.model.ChatMessage
import org.jaagruk.safety.pdf.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * PdfSummarizeViewModel
 *
 * Completely independent from ChatViewModel. Owns its own AIRepository instance.
 * Calls repository.generate() with an empty history and a summarization prompt —
 * the exact same API surface ChatViewModel uses. Zero changes to the AI pipeline.
 *
 * State machine:
 *   Idle → Extracting → Summarizing → Done
 *                                   → Error
 */
class PdfSummarizeViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "PdfSummarizeVM"
        // How long to wait for the FIRST token before giving up (prefill phase).
        // Qwen2.5-1.5B Q4 prefill on a large PDF can take 60-90s on low-end devices.
        private const val FIRST_TOKEN_TIMEOUT_MS  = 180_000L  // 3 minutes
        // How long to keep collecting tokens after generation starts.
        // 5 minutes allows even very long documents to finish completely.
        private const val TOTAL_GENERATION_TIMEOUT_MS = 300_000L  // 5 minutes
    }

    // ── Own repository instance — isolated from ChatViewModel ─────────────────
    private val repository  = AIRepository.getInstance(app)
    private val extractor    = PdfTextExtractor(app)
    private val libraryRepo  = LibraryRepository.getInstance(app)

    private val _showSavedBanner = MutableStateFlow(false)
    val showSavedBanner: StateFlow<Boolean> = _showSavedBanner.asStateFlow()

    // ── Public state ──────────────────────────────────────────────────────────
    val aiState: StateFlow<AIInferenceState> = repository.aiState

    private val _uiState = MutableStateFlow<PdfSummarizeUiState>(PdfSummarizeUiState.Idle)
    val uiState: StateFlow<PdfSummarizeUiState> = _uiState.asStateFlow()

    private val _summaryText = MutableStateFlow("")
    val summaryText: StateFlow<String> = _summaryText.asStateFlow()

    private val _statusLabel = MutableStateFlow("Analyzing document...")
    val statusLabel: StateFlow<String> = _statusLabel.asStateFlow()

    private val _tokenCount = MutableStateFlow(0)
    val tokenCount: StateFlow<Int> = _tokenCount.asStateFlow()

    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress.asStateFlow()

    private var generationJob: Job? = null
    private var userStopped = false

    // ── AI initialisation (same pattern as ChatViewModel.initializeAI) ────────
    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.initialize()
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    fun summarize(uri: Uri) {
        if (_uiState.value is PdfSummarizeUiState.Extracting ||
            _uiState.value is PdfSummarizeUiState.Summarizing) return

        generationJob?.cancel()
        _summaryText.value = ""
        userStopped = false

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            // ── 1. Extract text from PDF ──────────────────────────────────────
            _uiState.value = PdfSummarizeUiState.Extracting
            _extractionProgress.value = 0f

            val extractResult = extractor.extract(uri) { done, total ->
                _extractionProgress.value = if (total > 0) done.toFloat() / total else 0f
            }

            val pdfText = extractResult.getOrElse { e ->
                Log.e(TAG, "Extraction failed", e)
                _uiState.value = PdfSummarizeUiState.Error(e.message ?: "Failed to read PDF")
                return@launch
            }

            // ── 2. Check model readiness ──────────────────────────────────────
            val engineState = repository.aiState.value
            if (engineState is AIInferenceState.Error) {
                _uiState.value = PdfSummarizeUiState.Error("AI engine error. Please restart the app.")
                return@launch
            }
            if (engineState is AIInferenceState.Loading) {
                _uiState.value = PdfSummarizeUiState.Error("Model is still loading. Please wait and try again.")
                return@launch
            }

            // ── 3. Stream summary from AI ─────────────────────────────────────
            _uiState.value = PdfSummarizeUiState.Summarizing

            val prompt = buildSummaryPrompt(pdfText)
            Log.i(TAG, "Prompt chars: ${prompt.length}, est. tokens: ~${prompt.length / 4}")

            var firstTokenReceived = false
            var generationError: String? = null
            var tokenCount = 0

            Log.i(TAG, "Generation started — prompt ${prompt.length} chars, est ~${prompt.length / 4} tokens")
            _statusLabel.value = "Analyzing document..."
            _tokenCount.value  = 0

            // ── First-token watchdog ──────────────────────────────────────────
            // Cancelled immediately when first token arrives.
            // Does NOT cancel generation after first token — only guards prefill stall.
            val firstTokenWatchdog = viewModelScope.launch(Dispatchers.IO) {
                delay(FIRST_TOKEN_TIMEOUT_MS)
                if (!firstTokenReceived && !userStopped) {
                    Log.e(TAG, "First-token timeout after ${FIRST_TOKEN_TIMEOUT_MS}ms — prefill stalled")
                    repository.stop()
                    generationJob?.cancel()
                    _uiState.value = PdfSummarizeUiState.Error(
                        "Model did not respond. The document may be too large. Try a shorter PDF."
                    )
                }
            }

            // ── Total-generation watchdog ─────────────────────────────────────
            // Only fires if generation is still running after 5 minutes.
            // Preserves whatever text has been generated so far as Partial.
            val totalWatchdog = viewModelScope.launch(Dispatchers.IO) {
                delay(TOTAL_GENERATION_TIMEOUT_MS)
                if (!userStopped && _uiState.value is PdfSummarizeUiState.Summarizing) {
                    Log.w(TAG, "Total generation timeout after ${TOTAL_GENERATION_TIMEOUT_MS}ms — saving partial")
                    repository.stop()
                    generationJob?.cancel()
                    finishWithPartialOrDone(hasTokens = _summaryText.value.isNotBlank(), timedOut = true)
                }
            }

            try {
                repository.generate(emptyList<ChatMessage>(), prompt)
                    .catch { e ->
                        Log.e(TAG, "Generation error: ${e.message}")
                        generationError = e.message ?: "Unknown generation error"
                    }
                    .onCompletion { cause ->
                        firstTokenWatchdog.cancel()
                        totalWatchdog.cancel()
                        Log.i(TAG, "Generation finished — tokens=$tokenCount cause=$cause error=$generationError")
                        when {
                            userStopped -> {
                                _uiState.value = PdfSummarizeUiState.Done
                            }
                            firstTokenReceived -> {
                                if (generationError != null || cause != null) {
                                    Log.w(TAG, "Stream ended early after $tokenCount tokens")
                                    finishWithPartialOrDone(hasTokens = _summaryText.value.isNotBlank(), timedOut = false)
                                } else if (_summaryText.value.isBlank()) {
                                    _uiState.value = PdfSummarizeUiState.Error("No summary was generated. Please try again.")
                                } else {
                                    Log.i(TAG, "Generation complete — $tokenCount tokens")
                                    _uiState.value = PdfSummarizeUiState.Done
                                    autoSave(partial = false)
                                }
                            }
                            else -> {
                                val err = generationError
                                if (err != null && _uiState.value !is PdfSummarizeUiState.Error) {
                                    _uiState.value = PdfSummarizeUiState.Error("Generation failed: $err")
                                }
                            }
                        }
                    }
                    .collect { token ->
                        if (!firstTokenReceived) {
                            firstTokenReceived = true
                            firstTokenWatchdog.cancel() // disarm — no longer needed
                            _statusLabel.value = "Generating summary..."
                            Log.i(TAG, "First token received — watchdog disarmed")
                        }
                        tokenCount++
                        _tokenCount.value  = tokenCount
                        _summaryText.value += token
                        // Update status label at milestones
                        if (tokenCount == 50)  _statusLabel.value = "Generating summary..."
                        if (tokenCount == 150) _statusLabel.value = "Still working..."
                    }
            } catch (e: Exception) {
                firstTokenWatchdog.cancel()
                totalWatchdog.cancel()
                if (!firstTokenReceived && _uiState.value !is PdfSummarizeUiState.Error) {
                    Log.e(TAG, "Unexpected error before first token", e)
                    _uiState.value = PdfSummarizeUiState.Error("Unexpected error: ${e.message}")
                } else if (firstTokenReceived) {
                    Log.w(TAG, "Exception after $tokenCount tokens — preserving partial summary", e)
                    finishWithPartialOrDone(hasTokens = _summaryText.value.isNotBlank(), timedOut = false)
                }
            }
        }
    }

    fun stop() {
        userStopped = true
        repository.stop()
        generationJob?.cancel()
        if (_uiState.value is PdfSummarizeUiState.Summarizing) {
            _uiState.value = PdfSummarizeUiState.Done
        }
    }

    fun reset() {
        stop()
        _summaryText.value = ""
        _extractionProgress.value = 0f
        _uiState.value = PdfSummarizeUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        repository.stop()
        // Do not call repository.unload() — shared instance
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private fun finishWithPartialOrDone(hasTokens: Boolean, timedOut: Boolean) {
        if (hasTokens) {
            _uiState.value = PdfSummarizeUiState.Partial
            viewModelScope.launch(Dispatchers.IO) { autoSave(partial = true) }
        } else if (!timedOut) {
            _uiState.value = PdfSummarizeUiState.Error("No summary was generated. Please try again.")
        }
        // If timedOut and no tokens: watchdog already set Error, don't override
    }

    private suspend fun autoSave(partial: Boolean) {
        val text = _summaryText.value
        if (text.isBlank()) return
        libraryRepo.save(EntryType.PDF_SUMMARY, text)
        if (!partial) {
            _showSavedBanner.value = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(2_500)
                _showSavedBanner.value = false
            }
        }
    }

    private fun buildSummaryPrompt(pdfText: String): String =
        "Summarize the following document in 5 concise bullet points.\n\n$pdfText"
}

// ── UI state ──────────────────────────────────────────────────────────────────

sealed class PdfSummarizeUiState {
    object Idle        : PdfSummarizeUiState()
    object Extracting  : PdfSummarizeUiState()
    object Summarizing : PdfSummarizeUiState()
    object Done        : PdfSummarizeUiState()
    object Partial     : PdfSummarizeUiState()  // tokens received but stream ended early
    data class Error(val message: String) : PdfSummarizeUiState()
}
