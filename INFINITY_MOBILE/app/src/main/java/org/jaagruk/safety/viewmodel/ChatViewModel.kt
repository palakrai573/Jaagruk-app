package org.jaagruk.safety.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.jaagruk.safety.ai.repository.AIRepository
import org.jaagruk.safety.ai.state.AIInferenceState
import org.jaagruk.safety.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import org.jaagruk.safety.R

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val repository = AIRepository.getInstance(app)

    // ── Chat state ─────────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _showSuggestions = MutableStateFlow(true)
    val showSuggestions: StateFlow<Boolean> = _showSuggestions.asStateFlow()

    // ── AI engine state (forwarded from repository) ────────────────────────────
    val aiState: StateFlow<AIInferenceState> = repository.aiState

    // ── Extraction progress (first launch only) ────────────────────────────────
    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    // ── Generation job ─────────────────────────────────────────────────────────
    private var generationJob: Job? = null
    private var messageIdCounter = 0L
    private fun nextId() = ++messageIdCounter

    // Bug fix: track whether stop was user-initiated so onCompletion doesn't
    // replace a valid partial response with an error message.
    private var userStoppedGeneration = false

    init {
        initializeAI()
    }

    // ── AI Initialization ──────────────────────────────────────────────────────

    private fun initializeAI() {
        viewModelScope.launch(Dispatchers.IO) {
            _isExtracting.value = !repository.isModelOnDisk()
            repository.initialize { progress ->
                _extractionProgress.value = progress
            }
            _isExtracting.value = false
        }
    }

    // ── Input handling ─────────────────────────────────────────────────────────

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun sendMessage() {
        val text = _input.value.trim()
        if (text.isBlank()) return
        if (aiState.value is AIInferenceState.Thinking ||
            aiState.value is AIInferenceState.Responding) return

        if (aiState.value is AIInferenceState.Loading ||
            aiState.value is AIInferenceState.Error) {
            val userMsg = ChatMessage(nextId(), text, isUser = true)
            val errMsg  = ChatMessage(nextId(),
                if (aiState.value is AIInferenceState.Loading)
                    "Model is still loading, please wait a moment and try again."
                else
                    "AI engine error. Please restart the app.",
                isUser = false)
            _messages.value = _messages.value + userMsg + errMsg
            _input.value = ""
            _showSuggestions.value = false
            return
        }

        _showSuggestions.value = false
        _input.value = ""

        val userMsg = ChatMessage(nextId(), text, isUser = true)
        _messages.value = _messages.value + userMsg

        generateReply(text)
    }

    fun startFromSuggestion(prompt: String) {
        _showSuggestions.value = false
        val welcome = ChatMessage(nextId(), getApplication<Application>().getString(R.string.chat_welcome), isUser = false)
        val userMsg = ChatMessage(nextId(), prompt, isUser = true)
        _messages.value = listOf(welcome, userMsg)
        if (aiState.value is AIInferenceState.Loading ||
            aiState.value is AIInferenceState.Error) {
            val errMsg = ChatMessage(nextId(),
                if (aiState.value is AIInferenceState.Loading)
                    "Model is still loading, please wait a moment and try again."
                else
                    "AI engine error. Please restart the app.",
                isUser = false)
            _messages.value = _messages.value + errMsg
            return
        }
        generateReply(prompt)
    }

    fun stopGeneration() {
        userStoppedGeneration = true
        // Bug fix: stop C++ thread FIRST, then cancel the coroutine job.
        // Previously cancel() fired awaitClose (which called stopGeneration) then
        // repository.stop() called it again — harmless but wrong order semantically.
        // More importantly: stopping C++ first reduces the window where a new
        // generation could start while the old thread is still running.
        repository.stop()
        generationJob?.cancel()
    }

    fun clearChat() {
        stopGeneration()
        _messages.value = emptyList()
        _input.value = ""
        _showSuggestions.value = true
    }

    // ── Streaming generation ───────────────────────────────────────────────────

    private fun generateReply(userInput: String) {
        val aiMsgId = nextId()
        val placeholder = ChatMessage(aiMsgId, "", isUser = false)
        _messages.value = _messages.value + placeholder

        // Stop previous generation before starting a new one
        userStoppedGeneration = false
        repository.stop()
        generationJob?.cancel()

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            val historyForPrompt = _messages.value
                .dropLast(1)
                .takeLast(20)

            try {
                repository.generate(historyForPrompt, userInput)
                    .catch { e ->
                        Log.e(TAG, "Generation failed: ${e.message}", e)
                        updateMessage(aiMsgId, "Sorry, I encountered an error: ${e.message}")
                    }
                    .onCompletion { cause ->
                        if (cause != null) {
                            Log.e(TAG, "Generation cancelled or failed", cause)
                        }
                        // Bug fix: only replace blank message with error if the stop was NOT
                        // user-initiated. A user-stopped generation may have a valid partial
                        // response that should be preserved.
                        if (!userStoppedGeneration) {
                            val current = _messages.value.find { it.id == aiMsgId }
                            if (current?.text.isNullOrBlank()) {
                                updateMessage(aiMsgId, "I couldn't generate a response. Please try again.")
                            }
                        }
                    }
                    .collect { token -> appendToken(aiMsgId, token) }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during generation", e)
                updateMessage(aiMsgId, "An unexpected error occurred. Please try again.")
            }
        }
    }

    private fun appendToken(msgId: Long, token: String) {
        // Bug fix: removed `if (updated !== current)` reference-equality guard.
        // List.map{} always returns a new object so the check was always true —
        // a misleading no-op. Just assign directly.
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) msg.copy(text = msg.text + token) else msg
        }
    }

    private fun updateMessage(msgId: Long, text: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) msg.copy(text = text) else msg
        }
    }

    // ── Lifecycle cleanup ──────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopGeneration()
        // Deliberately does NOT unload the model.
        //
        // AIRepository is a process-wide singleton shared with the OCR, PDF, Circle Learn and
        // screenshot view models — all of which already carry a "do not unload, shared instance"
        // note. Unloading here meant that navigating away from Chat silently pulled the weights out
        // from under whichever other screen was mid-answer, and the next one to ask paid a full
        // reload.
        //
        // Releasing the model is now the AR interlock's job (LlmSessionGuard), because the reason to
        // release it is an ARCore session competing for memory, not a screen going away.
    }
}
