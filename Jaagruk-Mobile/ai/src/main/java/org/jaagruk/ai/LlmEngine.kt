package org.jaagruk.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaagruk.ai.runtime.LlamaBridge
import org.jaagruk.ai.runtime.StopReason
import org.jaagruk.ai.runtime.TokenCallback
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.core.ai.AiLanguage
import org.jaagruk.core.ai.PromptBudget
import kotlin.coroutines.resume

/** What the engine is doing. Observed by the UI so a long load is visible rather than a freeze. */
sealed interface LlmState {
    data object Unloaded : LlmState
    data object Loading : LlmState
    data object Ready : LlmState
    data object Generating : LlmState
    class Failed(val message: String) : LlmState
}

/** Sampling settings. Greedy by default, deliberately. */
class SamplingParams(
    /**
     * Zero means greedy decoding.
     *
     * The default, because these answers are grounded in bundled safety text and checked afterwards
     * by `AnswerGuard`. Sampling would add variation nobody asked for and make a given prompt produce
     * different output run to run, which would make the guard's behaviour impossible to pin down in a
     * test or defend to a reviewer.
     */
    val temperature: Float = 0.0f,
    val topP: Float = 0.9f,
    val seed: Int = 7,
) {
    init {
        require(temperature >= 0.0f) { "temperature must be >= 0, got $temperature" }
        require(topP in 0.0f..1.0f) { "topP must be 0.0..1.0, got $topP" }
    }

    companion object {
        val GREEDY = SamplingParams()
    }
}

class GenerationResult(
    val text: String,
    val reason: StopReason,
    val tokenCount: Int,
    val elapsedMs: Long,
) {
    val tokensPerSecond: Double
        get() = if (elapsedMs <= 0L) 0.0 else tokenCount * 1000.0 / elapsedMs
}

/**
 * On-device text generation.
 *
 * An interface so the orchestration in [AiCoach] can be tested against a fake without an NDK build,
 * an emulator or a 769 MiB file — the same reason `:core` puts every clock behind an interface.
 */
interface LlmEngine {
    val state: StateFlow<LlmState>

    /** Whether generation can run right now, and if not, why not. */
    fun capability(language: AiLanguage?): AiCapability

    /** Loads the model if it is not already loaded. Cheap and idempotent once ready. */
    suspend fun ensureLoaded(): Boolean

    /**
     * Generates to completion.
     *
     * Returns the whole text rather than streaming it. That is a safety decision, not a simplifying
     * one: partial output cannot be checked, and showing a worker an ungrounded methane figure for two
     * seconds before it is replaced is the exact failure this design exists to prevent.
     * [onTokenCount] exists so the UI can show honest progress without showing unvalidated text.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        params: SamplingParams = SamplingParams.GREEDY,
        onTokenCount: (Int) -> Unit = {},
    ): Result<GenerationResult>

    fun stop()

    suspend fun unload()

    /** Context window the engine loads with, so [PromptBudget] and the engine cannot disagree. */
    val contextTokens: Int
}

/**
 * llama.cpp behind [LlmEngine].
 *
 * Loading is serialised by a mutex and is idempotent. Generation is serialised natively as well, so
 * two callers cannot share a KV cache.
 */
class LlamaLlmEngine(
    private val modelStore: ModelStore,
    private val sessionGuard: LlmSessionGuard,
    override val contextTokens: Int = PromptBudget.DEFAULT_CONTEXT_TOKENS,
) : LlmEngine {

    private companion object {
        const val TAG = "JaagrukLlm"
    }

    private val _state = MutableStateFlow<LlmState>(LlmState.Unloaded)
    override val state: StateFlow<LlmState> = _state.asStateFlow()

    private val loadLock = Mutex()
    private val generateLock = Mutex()

    init {
        // An AR drill takes the camera, the GL surface and the tracking loop. Holding a 1B model
        // resident alongside them thermally throttles the handset, and a throttled frame loop
        // corrupts the decision latency the whole platform is built on. So the model is released for
        // the duration of a drill, by interlock rather than by remembering to.
        sessionGuard.addListener {
            if (LlamaBridge.isNativeAvailable && LlamaBridge.nativeIsLoaded()) {
                Log.i(TAG, "releasing the model for a drill")
                unload()
            }
        }
    }

    override fun capability(language: AiLanguage?): AiCapability = when {
        !LlamaBridge.isNativeAvailable -> AiCapability.UNSUPPORTED_DEVICE
        !modelStore.hasEnoughMemory() -> AiCapability.UNSUPPORTED_DEVICE
        !modelStore.isModelPresent() -> AiCapability.MODEL_MISSING
        sessionGuard.isInDrill -> AiCapability.BUSY_IN_DRILL
        language == null -> AiCapability.LANGUAGE_UNSUPPORTED
        else -> AiCapability.READY
    }

    override suspend fun ensureLoaded(): Boolean = loadLock.withLock {
        if (!LlamaBridge.isNativeAvailable) {
            _state.value = LlmState.Failed("this device has no native inference library")
            return@withLock false
        }
        if (LlamaBridge.nativeIsLoaded()) {
            _state.value = LlmState.Ready
            return@withLock true
        }
        if (!modelStore.isModelPresent()) {
            _state.value = LlmState.Failed("no model file is installed")
            return@withLock false
        }
        if (sessionGuard.isInDrill) {
            _state.value = LlmState.Failed("a drill is in progress")
            return@withLock false
        }

        _state.value = LlmState.Loading
        val loaded = withContext(Dispatchers.IO) {
            LlamaBridge.nativeLoadModel(
                modelStore.modelPath,
                contextTokens,
                modelStore.recommendedThreads(),
            )
        }
        _state.value = if (loaded) LlmState.Ready else LlmState.Failed("the model could not be loaded")
        loaded
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        params: SamplingParams,
        onTokenCount: (Int) -> Unit,
    ): Result<GenerationResult> {
        require(maxTokens > 0) { "maxTokens must be positive, got $maxTokens" }
        if (sessionGuard.isInDrill) {
            return Result.failure(IllegalStateException("a drill is in progress"))
        }
        if (!ensureLoaded()) {
            val message = (_state.value as? LlmState.Failed)?.message ?: "the engine is not ready"
            return Result.failure(IllegalStateException(message))
        }

        return generateLock.withLock {
            _state.value = LlmState.Generating
            val startedAt = System.nanoTime()
            try {
                val result = awaitGeneration(prompt, maxTokens, params, onTokenCount, startedAt)
                _state.value = LlmState.Ready
                result
            } catch (e: CancellationException) {
                LlamaBridge.nativeStop()
                _state.value = LlmState.Ready
                throw e
            } catch (e: Exception) {
                _state.value = LlmState.Failed(e.message ?: "generation failed")
                Result.failure(e)
            }
        }
    }

    private suspend fun awaitGeneration(
        prompt: String,
        maxTokens: Int,
        params: SamplingParams,
        onTokenCount: (Int) -> Unit,
        startedAt: Long,
    ): Result<GenerationResult> = suspendCancellableCoroutine { continuation ->
        // Only the single native generation thread writes to these, and the continuation is resumed
        // from that same thread, so no additional synchronisation is needed.
        val text = StringBuilder()
        var tokens = 0

        continuation.invokeOnCancellation { LlamaBridge.nativeStop() }

        LlamaBridge.nativeGenerate(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            seed = params.seed,
            callback = object : TokenCallback {
                override fun onToken(token: String) {
                    text.append(token)
                    tokens++
                    onTokenCount(tokens)
                }

                override fun onComplete(reason: Int) {
                    if (!continuation.isActive) return
                    val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                    val stop = StopReason.fromOrdinal(reason)
                    Log.i(TAG, "generated $tokens tokens in ${elapsed}ms, stop=$stop")
                    continuation.resume(
                        Result.success(
                            GenerationResult(
                                text = text.toString(),
                                reason = stop,
                                tokenCount = tokens,
                                elapsedMs = elapsed,
                            ),
                        ),
                    )
                }

                override fun onError(message: String) {
                    if (!continuation.isActive) return
                    Log.e(TAG, "generation failed: $message")
                    continuation.resume(Result.failure(IllegalStateException(message)))
                }
            },
        )
    }

    override fun stop() {
        if (LlamaBridge.isNativeAvailable) LlamaBridge.nativeStop()
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        if (LlamaBridge.isNativeAvailable) LlamaBridge.nativeUnloadModel()
        _state.value = LlmState.Unloaded
    }
}

/**
 * An engine that does nothing, for devices and builds without one.
 *
 * Bound instead of [LlamaLlmEngine] when `:ai` is excluded from the build, so every call site
 * compiles and behaves identically — it just always reports unavailable.
 */
class NoopLlmEngine(
    private val reason: AiCapability = AiCapability.UNSUPPORTED_DEVICE,
) : LlmEngine {
    override val state: StateFlow<LlmState> = MutableStateFlow(LlmState.Unloaded).asStateFlow()
    override val contextTokens: Int = 0
    override fun capability(language: AiLanguage?): AiCapability = reason
    override suspend fun ensureLoaded(): Boolean = false
    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        params: SamplingParams,
        onTokenCount: (Int) -> Unit,
    ): Result<GenerationResult> =
        Result.failure(IllegalStateException("no inference engine in this build"))

    override fun stop() = Unit
    override suspend fun unload() = Unit
}
