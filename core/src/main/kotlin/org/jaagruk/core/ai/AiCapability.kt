package org.jaagruk.core.ai

/**
 * Whether on-device assistance can run right now, and if not, why not.
 *
 * Every state here is a state the UI has to be able to say out loud. The pattern is the same one
 * `GestureRecognizerSource.Availability` and `NarrationPlayer` already use in the Android layer: a
 * feature that cannot work hides itself and states the reason, because an affordance that silently
 * does nothing teaches a worker to stop trusting the working ones too.
 */
enum class AiCapability {
    /** A model is loaded and generation is permitted. */
    READY,

    /**
     * No GGUF model file present.
     *
     * The model is deliberately not bundled in the APK. It is 769 MiB, which would end the
     * 27 MB download this app is built around, and every feature that uses it has a working
     * non-AI path. Absent the file, those paths are what runs.
     */
    MODEL_MISSING,

    /** Not enough memory, or an ABI with no native build. Stated, not silently degraded. */
    UNSUPPORTED_DEVICE,

    /**
     * The interface language has no supported generation path.
     *
     * Santali is the case this exists for. No language model generates Ol Chiki, and a plausible
     * paragraph of wrong Santali in front of a worker who cannot cross-check it is worse than
     * nothing. Santali speakers get the authored translations, pictograms and per-site voice
     * recordings, which are all real; they do not get generated prose.
     */
    LANGUAGE_UNSUPPORTED,

    /**
     * An AR drill holds the camera, the GL surface and the tracking loop.
     *
     * A 1B model at Q4 needs roughly 900 MiB resident: 769 MiB of weights plus its KV cache. On the 4 GB
     * handsets this platform targets, holding that alongside an ARCore session thermally throttles
     * the device and risks the allocator killing one of the two. Decision latency measured on a
     * throttled frame loop measures the phone, not the worker. So the model is unloaded for the
     * duration of a drill, by interlock rather than by convention.
     */
    BUSY_IN_DRILL,

    /** Turned off for this site or this device. */
    DISABLED_BY_POLICY,
    ;

    val canGenerate: Boolean get() = this == READY

    /** True when the reason is transient and worth re-checking rather than reporting as final. */
    val isTransient: Boolean get() = this == BUSY_IN_DRILL
}

/**
 * Languages generation is permitted in.
 *
 * Deliberately narrower than the app's three locales. The app is fully translated into English,
 * Hindi and Santali by hand; only two of those have a model behind them.
 */
enum class AiLanguage(val tag: String, val endonym: String) {
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी"),
    ;

    companion object {
        /**
         * Resolves a BCP-47-ish tag, or null when generation is not supported for it.
         *
         * Returning null for `sat` is the whole point of this function: the caller is expected to
         * turn that into [AiCapability.LANGUAGE_UNSUPPORTED] rather than quietly falling back to
         * English, which would put text a worker cannot read where an answer should be.
         */
        fun fromTagOrNull(tag: String): AiLanguage? {
            val primary = tag.trim().lowercase().substringBefore('-').substringBefore('_')
            return entries.firstOrNull { it.tag == primary }
        }
    }
}
