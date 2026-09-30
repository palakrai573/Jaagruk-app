package org.jaagruk.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jaagruk.ai.runtime.StopReason
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.core.ai.AiLanguage

/**
 * An engine that returns whatever a test tells it to.
 *
 * The point of putting [LlmEngine] behind an interface. With this, the whole pipeline — capability
 * gate, retrieval, prompt construction, guarding, and every one of the six [AiOutcome] shapes — is
 * exercised on a plain JVM with no NDK build, no emulator and no 769 MiB model file. A test suite that
 * needed any of those would be a test suite nobody runs.
 */
class FakeLlmEngine(
    /** What the model "says". Set per test. */
    var response: String = "Withdraw from the area.",
    private var reportedCapability: AiCapability = AiCapability.READY,
    var stopReason: StopReason = StopReason.END_OF_TURN,
    var failWith: String? = null,
    override val contextTokens: Int = 4_096,
) : LlmEngine {

    private val _state = MutableStateFlow<LlmState>(LlmState.Ready)
    override val state: StateFlow<LlmState> = _state.asStateFlow()

    /** Every prompt this engine was handed, so a test can assert what the model actually saw. */
    val prompts = mutableListOf<String>()

    var loadCount = 0
        private set
    var unloadCount = 0
        private set
    var stopCount = 0
        private set

    fun setCapability(capability: AiCapability) {
        reportedCapability = capability
    }

    override fun capability(language: AiLanguage?): AiCapability =
        if (language == null) AiCapability.LANGUAGE_UNSUPPORTED else reportedCapability

    override suspend fun ensureLoaded(): Boolean {
        loadCount++
        return reportedCapability.canGenerate
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        params: SamplingParams,
        onTokenCount: (Int) -> Unit,
    ): Result<GenerationResult> {
        prompts += prompt
        failWith?.let { return Result.failure(IllegalStateException(it)) }
        // Report progress the way the real engine does, so a caller that depends on it is exercised.
        val tokens = response.split(" ").size
        for (index in 1..tokens) onTokenCount(index)
        return Result.success(
            GenerationResult(
                text = response,
                reason = stopReason,
                tokenCount = tokens,
                elapsedMs = 1_200L,
            ),
        )
    }

    override fun stop() {
        stopCount++
    }

    override suspend fun unload() {
        unloadCount++
        _state.value = LlmState.Unloaded
    }
}
