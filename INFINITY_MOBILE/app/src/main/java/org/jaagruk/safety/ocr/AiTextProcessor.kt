package org.jaagruk.safety.ocr

import android.util.Log
import org.jaagruk.safety.ai.repository.AIRepository
import org.jaagruk.safety.ai.state.AIInferenceState
import org.jaagruk.safety.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * AiTextProcessor
 *
 * Shared helper that runs a single prompt through AIRepository.generate()
 * with token-budget enforcement and first-token timeout.
 *
 * Used by OcrViewModel, ScreenshotExplainerViewModel, and QuizViewModel
 * so the safety logic is not duplicated.
 *
 * Caller supplies:
 *   - repository    : their own AIRepository instance
 *   - prompt        : already-built prompt string (caller enforces MAX_CHARS on input text)
 *   - outputFlow    : MutableStateFlow<String> to stream tokens into
 *   - onDone        : called on natural completion or user stop
 *   - onError       : called with message on any failure
 *   - userStopped   : lambda returning current userStopped flag
 */
object AiTextProcessor {

    private const val TAG = "AiTextProcessor"
    // First-token watchdog: fires only if zero tokens arrive before deadline.
    // Cancelled immediately on first token — never affects mid-stream generation.
    private const val FIRST_TOKEN_TIMEOUT_MS   = 180_000L  // 3 minutes
    // Total-generation watchdog: saves partial output if generation takes too long.
    private const val TOTAL_GENERATION_TIMEOUT_MS = 300_000L  // 5 minutes
    const val MAX_INPUT_CHARS = 800

    /**
     * Build a token-safe prompt:
     *   prefix (the instruction) + "\n\n" + text (capped at MAX_INPUT_CHARS).
     * Estimated total prompt tokens ≈ (prefix.length + text.length) / 4 + 134 overhead.
     */
    fun buildPrompt(prefix: String, text: String): String {
        val safeText = if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text
        val prompt   = "$prefix\n\n$safeText"
        Log.i(TAG, "Prompt: ${prompt.length} chars, est ~${prompt.length / 4 + 80} tokens")
        return prompt
    }

    /**
     * Stream [prompt] through [repository] into [outputFlow].
     * Two independent watchdogs guard prefill stall and total generation time.
     * If first token was received, partial text is ALWAYS preserved — never cleared.
     *
     * [scope]    : CoroutineScope for launching watchdog jobs (typically viewModelScope)
     * [onPartial]: called instead of onDone when stream ended early but tokens exist
     */
    suspend fun stream(
        repository  : AIRepository,
        prompt      : String,
        outputFlow  : MutableStateFlow<String>,
        userStopped : () -> Boolean,
        onDone      : () -> Unit,
        onError     : (String) -> Unit,
        scope       : CoroutineScope,
        onPartial   : (() -> Unit)? = null
    ) {
        when (repository.aiState.value) {
            is AIInferenceState.Error   -> { onError("AI engine error. Please restart the app."); return }
            is AIInferenceState.Loading -> { onError("Model is still loading. Please wait and try again."); return }
            else -> Unit
        }

        var firstTokenReceived = false
        var generationError: String?  = null
        var tokenCount = 0

        Log.i(TAG, "Generation started — prompt ${prompt.length} chars")

        // Watchdog 1: fires only if zero tokens arrive — guards prefill stall only
        val firstTokenWatchdog: Job = scope.launch(Dispatchers.IO) {
            delay(FIRST_TOKEN_TIMEOUT_MS)
            if (!firstTokenReceived && !userStopped()) {
                Log.e(TAG, "First-token timeout after ${FIRST_TOKEN_TIMEOUT_MS}ms")
                repository.stop()
                onError("Model did not respond. Please try again.")
            }
        }

        // Watchdog 2: saves partial output if still running after 5 minutes
        val totalWatchdog: Job = scope.launch(Dispatchers.IO) {
            delay(TOTAL_GENERATION_TIMEOUT_MS)
            if (!userStopped()) {
                Log.w(TAG, "Total timeout after ${TOTAL_GENERATION_TIMEOUT_MS}ms — $tokenCount tokens so far")
                repository.stop()
                if (outputFlow.value.isNotBlank()) {
                    (onPartial ?: onDone)()
                } else {
                    onError("Generation timed out. Please try again.")
                }
            }
        }

        try {
            repository.generate(emptyList<ChatMessage>(), prompt)
                .catch { e ->
                    Log.e(TAG, "Generation error: ${e.message}")
                    generationError = e.message ?: "Unknown error"
                }
                .onCompletion { cause ->
                    firstTokenWatchdog.cancel()
                    totalWatchdog.cancel()
                    Log.i(TAG, "Generation finished — tokens=$tokenCount cause=$cause error=$generationError")
                    when {
                        userStopped() -> onDone()
                        firstTokenReceived -> {
                            when {
                                generationError != null || cause != null -> {
                                    Log.w(TAG, "Stream ended early after $tokenCount tokens")
                                    if (outputFlow.value.isNotBlank()) (onPartial ?: onDone)()
                                    else onError("No response generated. Please try again.")
                                }
                                outputFlow.value.isBlank() ->
                                    onError("No response generated. Please try again.")
                                else -> {
                                    Log.i(TAG, "Generation complete — $tokenCount tokens")
                                    onDone()
                                }
                            }
                        }
                        else -> {
                            val err = generationError
                            if (err != null) onError("Generation failed: $err")
                        }
                    }
                }
                .collect { token ->
                    if (!firstTokenReceived) {
                        firstTokenReceived = true
                        firstTokenWatchdog.cancel()
                        Log.i(TAG, "First token received — watchdog disarmed")
                    }
                    tokenCount++
                    outputFlow.value += token
                }
        } catch (e: Exception) {
            firstTokenWatchdog.cancel()
            totalWatchdog.cancel()
            if (!firstTokenReceived) {
                Log.e(TAG, "Exception before first token", e)
                onError("Unexpected error: ${e.message}")
            } else {
                Log.w(TAG, "Exception after $tokenCount tokens — preserving partial", e)
                if (outputFlow.value.isNotBlank()) (onPartial ?: onDone)() else onDone()
            }
        }
    }
}
