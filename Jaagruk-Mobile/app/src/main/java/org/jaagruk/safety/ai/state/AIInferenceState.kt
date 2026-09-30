package org.jaagruk.safety.ai.state

/**
 * AIInferenceState represents every possible state of the local AI engine.
 *
 * This flows from:
 *   LlamaEngine → AIRepository → ChatViewModel → Compose UI + AiBodyOrb
 *
 * The orb animation reacts to each state differently.
 */
sealed class AIInferenceState {

    /** Model is loaded and ready. Orb: slow breathing. */
    object Idle : AIInferenceState()

    /** Model is being copied from assets or loaded into RAM. Orb: soft pulse. */
    object Loading : AIInferenceState()

    /** Prompt is being processed (prefill phase). Orb: rotating energy rings. */
    object Thinking : AIInferenceState()

    /** Tokens are being generated and streamed. Orb: waveform activity. */
    data class Responding(val partialText: String = "") : AIInferenceState()

    /** Something went wrong. Orb: unstable flicker. */
    data class Error(val message: String) : AIInferenceState()
}
