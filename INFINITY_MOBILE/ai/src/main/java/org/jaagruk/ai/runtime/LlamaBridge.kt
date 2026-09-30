package org.jaagruk.ai.runtime

import android.util.Log

/**
 * Callback the native generation loop drives, one call per token.
 *
 * Public because C++ resolves these methods by name and signature through JNI, and because
 * `consumer-rules.pro` has to be able to name them. Not part of the surface anything outside this
 * module should use.
 */
interface TokenCallback {
    /** One piece of generated text. May be a partial word. */
    fun onToken(token: String)

    /** Generation ended. [reason] matches the ordinals of [StopReason]. */
    fun onComplete(reason: Int)

    fun onError(message: String)
}

/** Why generation ended. Ordinals are the JNI contract with `jaagruk_llm.cpp`. */
enum class StopReason {
    /** The model emitted its end-of-turn marker. The answer is complete. */
    END_OF_TURN,

    /** The token budget ran out. The answer may be cut off mid-sentence. */
    TOKEN_LIMIT,

    /** Cancelled from Kotlin, or the model was unloaded underneath the generation. */
    CANCELLED,
    ;

    companion object {
        fun fromOrdinal(value: Int): StopReason = entries.getOrElse(value) { CANCELLED }
    }
}

/**
 * The JNI surface.
 *
 * The native library is loaded lazily and the failure is caught, which is the one difference from
 * the usual `init { System.loadLibrary(...) }` idiom that matters here. This app ships an
 * `armeabi-v7a` APK with no `libjaagruk_llm.so` in it on purpose, so on those devices the load
 * *must* fail. Doing it in an initialiser turns that into `ExceptionInInitializerError` on first
 * touch of the class — an unrecoverable crash on a device the app is meant to work on. Doing it
 * here turns it into `AiCapability.UNSUPPORTED_DEVICE` and a sentence in the UI.
 */
object LlamaBridge {

    private const val TAG = "JaagrukLlm"
    private const val LIBRARY = "jaagruk_llm"

    enum class Availability { NOT_ATTEMPTED, AVAILABLE, UNAVAILABLE }

    @Volatile
    private var availability: Availability = Availability.NOT_ATTEMPTED

    /** Loads the native library once. False means this device has no engine, permanently. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        when (availability) {
            Availability.AVAILABLE -> return true
            Availability.UNAVAILABLE -> return false
            Availability.NOT_ATTEMPTED -> Unit
        }
        availability = try {
            System.loadLibrary(LIBRARY)
            Log.i(TAG, "native library loaded")
            Availability.AVAILABLE
        } catch (e: UnsatisfiedLinkError) {
            // Expected on an ABI with no vendored kernels. Not an error worth a stack trace.
            Log.w(TAG, "no native library for this ABI: ${e.message}")
            Availability.UNAVAILABLE
        } catch (e: SecurityException) {
            Log.e(TAG, "native library load refused", e)
            Availability.UNAVAILABLE
        }
        return availability == Availability.AVAILABLE
    }

    val isNativeAvailable: Boolean get() = ensureLoaded()

    // -----------------------------------------------------------------------
    // Native entry points. Never call these without ensureLoaded() first.
    // -----------------------------------------------------------------------

    external fun nativeLoadModel(modelPath: String, contextTokens: Int, threads: Int): Boolean

    external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        seed: Int,
        callback: TokenCallback,
    )

    external fun nativeStop()

    external fun nativeUnloadModel()

    external fun nativeIsLoaded(): Boolean

    external fun nativeContextTokens(): Int
}
