package org.jaagruk.safety.ai.repository

import android.content.Context
import android.util.Log
import org.jaagruk.safety.ai.prompts.PromptFormatter
import org.jaagruk.safety.ai.state.AIInferenceState
import org.jaagruk.safety.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jaagruk.ai.LlamaLlmEngine
import org.jaagruk.ai.LlmEngine
import org.jaagruk.ai.LlmSessionGuard
import org.jaagruk.ai.LlmState
import org.jaagruk.ai.ModelStore
import org.jaagruk.ai.SamplingParams
import org.jaagruk.ai.AiCoach
import org.jaagruk.ai.ExtractiveSafetyCoach
import org.jaagruk.ai.AiOutcome
import org.jaagruk.core.ai.AiLanguage
import org.jaagruk.core.ai.AiTask
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.core.ai.SafetyCorpus
import org.jaagruk.core.ai.SafetyDisplayPolicy
import org.jaagruk.core.ai.RetrievalResult

/**
 * The app's single door to the on-device model.
 *
 * ## What changed, and why the shape did not
 *
 * The internals are now `:ai` — the vendored llama.cpp build, [ModelStore], [LlamaLlmEngine] and the
 * [LlmSessionGuard] interlock. The public surface is unchanged so the five view models that depend on
 * it keep compiling: swapping the engine and rewriting eight call sites in one step would have meant
 * one error message for two unrelated mistakes.
 *
 * Three behavioural differences that callers can see, all deliberate:
 *
 *  1. **The model is no longer extracted from assets.** It is sideloaded once per handset to
 *     `files/models/gemma-3-1b-it-q4_k_m.gguf` and memory-mapped in place. An APK asset has to be
 *     copied to a real path before it can be mapped, so bundling cost 769 MiB twice. On a fresh
 *     install there is simply no model, and that is reported rather than worked around.
 *  2. **Generation does not stream.** [generate] emits the finished text as a single element. Partial
 *     output cannot be checked, and showing a worker an invented methane threshold for two seconds
 *     before it is replaced is the exact failure this design exists to prevent. [tokenCount] is there
 *     so progress can be honest without showing unvalidated text.
 *  3. **Decoding is greedy.** `temperature = 0`. One prompt gives one answer, which is what lets the
 *     output guard be pinned by a test.
 *
 * Scope, stated plainly: this is the *ungrounded* chat path inherited from the Infinity base. The
 * grounded, cited path Jaagruk certifies against is `:core`'s retrieval plus `AnswerGuard`, wired in
 * phase 9. Nothing here may touch a score, a certificate or the chain.
 */
class AIRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "AIRepository"

        /** Ceiling on a single answer. Roughly a screen of text; beyond it nobody reads. */
        private const val MAX_TOKENS = 512

        @Volatile
        private var INSTANCE: AIRepository? = null

        /**
         * One instance per process.
         *
         * Not merely an optimisation: two engines would mean two `llama_context` objects over the
         * same weights, and the native layer serialises generation globally, so the second caller
         * would block on a lock it has no way to observe.
         */
        fun getInstance(context: Context): AIRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AIRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val modelStore = ModelStore(context)

    /**
     * Exposed so an AR drill can claim the device and force the weights out of memory.
     *
     * Phase 6 hands this to the drill controller. Until an AR session exists nothing enters a drill,
     * so the guard is inert rather than absent — the wiring is in place before the caller is, which
     * is the point of an interlock.
     */
    val sessionGuard = LlmSessionGuard()

    private val engine: LlmEngine = LlamaLlmEngine(modelStore, sessionGuard)

    private val _aiState = MutableStateFlow<AIInferenceState>(AIInferenceState.Idle)
    val aiState: StateFlow<AIInferenceState> = _aiState.asStateFlow()

    private val _tokenCount = MutableStateFlow(0)

    /** Tokens produced so far in the current generation. Honest progress, no unvalidated text. */
    val tokenCount: StateFlow<Int> = _tokenCount.asStateFlow()

    private val initializationLock = Mutex()
    private val safetyLock = Mutex()

    /** Safety questions use retrieval and the output guard, never the generic chat prompt. */
    suspend fun askSafety(question: String, languageTag: String): AiOutcome = safetyLock.withLock {
        val language = AiLanguage.fromTagOrNull(languageTag)
            ?: return@withLock AiOutcome.Unavailable(AiCapability.LANGUAGE_UNSUPPORTED)
        require(question.isNotBlank() && question.length <= AiTask.SafetyQuestion.MAX_QUESTION_CHARS)
        _tokenCount.value = 0
        _aiState.value = AIInferenceState.Thinking
        try {
            val task = AiTask.SafetyQuestion(language, question)
            val outcome = ExtractiveSafetyCoach(engine).ask(question, language) { _tokenCount.value = it }
            val sources = (SafetyCorpus.retriever.retrieveForTask(task) as? RetrievalResult.Grounded)
                ?.passages?.map { it.passage }.orEmpty()
            if (outcome is AiOutcome.Answer && !SafetyDisplayPolicy.permits(outcome.text, outcome.truncated, sources)) {
                AiOutcome.Failed("generated paraphrase withheld by the safety display gate")
            } else outcome
        } finally {
            _aiState.value = AIInferenceState.Idle
        }
    }

    /**
     * Finds a model, puts it in place if it is not already, and loads it.
     *
     * Idempotent: [LlamaLlmEngine.ensureLoaded] is mutex-guarded and returns immediately once ready,
     * and [ModelStore.ensureAvailable] short-circuits on a model that is already resident.
     *
     * @param onExtractionProgress 0f..1f while a copy is in flight. On a `bundled` build's very first
     *   launch that copy is 769 MiB out of the APK and takes tens of seconds; on every later launch,
     *   and on every launch of a `lean` build with the model already resident, it fires once with 1f.
     */
    suspend fun initialize(onExtractionProgress: (Float) -> Unit = {}): Result<Unit> =
        initializationLock.withLock {
            // Resident, then the drop directory, then the asset bundled by the `bundled` flavour.
            // Cheapest first, so the normal case — already installed — costs one stat call.
            //
            // On a `bundled` build's first launch this extracts 769 MiB out of the APK, which takes
            // long enough that the progress fraction is the difference between a progress bar and an
            // apparently frozen app.
            _aiState.value = AIInferenceState.Loading
            val source = modelStore.ensureAvailable { written, total ->
                if (total > 0L) onExtractionProgress(written.toFloat() / total)
            }.getOrElse { error ->
                val message = error.message ?: "No model is available."
                Log.w(TAG, "$message store: ${modelStore.describe()}")
                _aiState.value = AIInferenceState.Error(message)
                return@withLock Result.failure(error)
            }
            Log.i(TAG, "model source: $source")

            onExtractionProgress(1f)

            if (!modelStore.hasEnoughMemory()) {
                val message = "This device does not have enough memory to run the model."
                Log.w(TAG, "$message store: ${modelStore.describe()}")
                _aiState.value = AIInferenceState.Error(message)
                return@withLock Result.failure(IllegalStateException(message))
            }

            Log.i(TAG, "loading: ${modelStore.describe()}")

            val loaded = engine.ensureLoaded()
            if (!loaded) {
                val message = (engine.state.value as? LlmState.Failed)?.message
                    ?: "The model could not be loaded."
                Log.e(TAG, "load failed: $message")
                _aiState.value = AIInferenceState.Error(message)
                return@withLock Result.failure(IllegalStateException(message))
            }

            Log.i(TAG, "model ready, context ${engine.contextTokens} tokens")
            _aiState.value = AIInferenceState.Idle
            Result.success(Unit)
        }

    /**
     * Generates an answer and emits it once, complete.
     *
     * A `Flow` rather than a suspend function only because the call sites collect one; the flow
     * deliberately has a single element. Callers that concatenate emissions still behave correctly.
     */
    fun generate(history: List<ChatMessage>, userInput: String): Flow<String> = flow {
        _tokenCount.value = 0
        _aiState.value = AIInferenceState.Thinking

        val prompt = PromptFormatter.buildPrompt(history, userInput)
        Log.d(TAG, "prompt is ${prompt.length} chars")

        val result = engine.generate(
            prompt = prompt,
            maxTokens = MAX_TOKENS,
            params = SamplingParams.GREEDY,
            onTokenCount = { count -> _tokenCount.value = count },
        )

        result.fold(
            onSuccess = { generation ->
                Log.i(
                    TAG,
                    "generated ${generation.tokenCount} tokens in ${generation.elapsedMs}ms " +
                        "(${"%.1f".format(generation.tokensPerSecond)} tok/s), stop=${generation.reason}",
                )
                val text = generation.text.trim()
                // Responding carries the finished text so any UI reading `partialText` shows the
                // answer rather than nothing. It is not partial, and it is never shown before the
                // generation has completed.
                _aiState.value = AIInferenceState.Responding(text)
                emit(text)
                _aiState.value = AIInferenceState.Idle
            },
            onFailure = { error ->
                val message = error.message ?: "Generation failed."
                Log.e(TAG, "generation failed: $message", error)
                _aiState.value = AIInferenceState.Error(message)
            },
        )
    }

    fun stop() {
        engine.stop()
        _aiState.value = AIInferenceState.Idle
    }

    /** Frees the weights. Safe to call when nothing is loaded. */
    suspend fun unload() {
        engine.unload()
        _tokenCount.value = 0
        _aiState.value = AIInferenceState.Idle
    }

    fun isReady(): Boolean = engine.state.value is LlmState.Ready

    /** True when a plausible GGUF is on disk — magic bytes and a size floor, not just existence. */
    fun isModelOnDisk(): Boolean = modelStore.isModelPresent()

    /** Diagnostics for the settings screen: path, presence, size, RAM, ABI, thread count. */
    fun describeModel(): String = modelStore.describe()
}
