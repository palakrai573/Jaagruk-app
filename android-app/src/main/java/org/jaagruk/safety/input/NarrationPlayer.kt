package org.jaagruk.safety.input

import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jaagruk.safety.ui.components.CatalogStrings
import java.util.Locale

/**
 * Reads prompts aloud.
 *
 * Literacy is the reason this exists, not convenience. A substantial share of the workforce this app is for
 * cannot comfortably read a paragraph, and the pictogram mode alone cannot carry a nine-step scenario. The
 * combination that works is a pictogram to identify the option and audio to explain the question.
 *
 * Three tiers, because Android's TTS coverage does not match the languages needed:
 *
 *  1. **Bundled audio.** Recorded per language and looked up by string key. This is the only tier that
 *     works for Santali — Android has no Santali voice and no engine ships one, so synthesis is not an
 *     option at any price.
 *  2. **Platform TTS.** Hindi and English are well covered, and a synthesised prompt is better than
 *     silence for the many UI strings that are not worth recording.
 *  3. **Silence, reported honestly.** [state] says when narration is unavailable so the UI can keep the
 *     text visible rather than showing a speaker button that does nothing.
 *
 * Bundled audio wins over TTS wherever it exists, even for Hindi. A recorded prompt from a speaker the
 * workers recognise is understood better than a synthesised one, and safety instructions are worth the
 * recording effort.
 */
class NarrationPlayer(private val context: Context) {

    enum class Source {
        /** Bundled recording. */
        RECORDED,

        /** Platform speech synthesis. */
        SYNTHESISED,

        /** Nothing available for this language. */
        UNAVAILABLE,
    }

    data class State(
        val source: Source = Source.UNAVAILABLE,
        val speaking: Boolean = false,
        val languageTag: String = "en",
        /** True when the platform has no voice for the requested language. */
        val ttsMissingForLanguage: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsUsable = false
    private var initialising = false
    private var engineGeneration = 0L
    private var utteranceSequence = 0L
    private var activeUtterance: String? = null
    private var pendingPrompt: Pair<String, String>? = null
    private var completion: (() -> Unit)? = null
    private var completionTimeout: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var languageTag: String = "en"

    /**
     * Initialises for [tag].
     *
     * `sat` deliberately never reaches the TTS engine. Some engines respond to an unknown locale by
     * silently falling back to English, so a Santali prompt would be read aloud in English — confidently,
     * and wrongly, to a worker who cannot tell that is what happened.
     */
    @Synchronized
    fun prepare(tag: String) {
        stop()
        languageTag = tag
        ttsUsable = false
        reportUnavailable(tag)

        if (tag == SANTALI) {
            _state.value = State(
                source = if (hasAnyRecording(tag)) Source.RECORDED else Source.UNAVAILABLE,
                languageTag = tag,
                ttsMissingForLanguage = true,
            )
            return
        }

        if (tts == null) {
            initialising = true
            val generation = ++engineGeneration
            tts = TextToSpeech(context) { status ->
                // Post even synchronous failures until the constructor has assigned the engine.
                mainHandler.post {
                    synchronized(this@NarrationPlayer) {
                        if (generation != engineGeneration) return@post
                        initialising = false
                        ttsReady = status == TextToSpeech.SUCCESS
                        if (languageTag != SANTALI) {
                            if (ttsReady) applyLanguage(languageTag) else reportUnavailable(languageTag)
                        }
                        val pending = pendingPrompt
                        pendingPrompt = null
                        if (pending != null) speak(pending.first, pending.second, completion)
                    }
                }
            }.also { engine ->
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            updateUtterance(utteranceId, speaking = true)
                        }

                        override fun onDone(utteranceId: String?) {
                            updateUtterance(utteranceId, speaking = false)
                        }

                        @Deprecated("Required by the platform interface")
                        override fun onError(utteranceId: String?) {
                            updateUtterance(utteranceId, speaking = false)
                        }
                    },
                )
            }
        } else if (ttsReady) {
            applyLanguage(tag)
        }
    }

    private fun updateUtterance(id: String?, speaking: Boolean) {
        mainHandler.post {
            synchronized(this@NarrationPlayer) {
                if (id == null || id != activeUtterance) return@post
                _state.value = _state.value.copy(speaking = speaking)
                if (!speaking) {
                    activeUtterance = null
                    completeNarration()
                }
            }
        }
    }

    private fun applyLanguage(tag: String) {
        if (tag == SANTALI || !ttsReady) return
        val engine = tts ?: return reportUnavailable(tag)
        val locale = when (tag) {
            HINDI -> Locale("hi", "IN")
            else -> Locale.forLanguageTag(tag)
        }

        val result = engine.setLanguage(locale)
        ttsUsable = result >= TextToSpeech.LANG_AVAILABLE

        // Slower than default. Synthesised Hindi at normal rate is hard to follow through a helmet, and a
        // safety instruction that has to be replayed twice has failed.
        engine.setSpeechRate(SPEECH_RATE)

        _state.value = _state.value.copy(
            source = when {
                _state.value.speaking -> _state.value.source
                hasAnyRecording(tag) -> Source.RECORDED
                ttsUsable -> Source.SYNTHESISED
                else -> Source.UNAVAILABLE
            },
            languageTag = tag,
            ttsMissingForLanguage = !ttsUsable,
        )
    }

    private fun reportUnavailable(tag: String) {
        ttsUsable = false
        _state.value = _state.value.copy(
            source = if (hasAnyRecording(tag)) Source.RECORDED else Source.UNAVAILABLE,
            languageTag = tag,
            ttsMissingForLanguage = true,
        )
    }

    /**
     * Speaks the prompt for [stringKey].
     *
     * [fallbackText], when supplied, is already localised. Otherwise the string resource is resolved
     * using the prepared language, including on devices where the application context is not localised.
     * Passing the key as well as the text is what lets the recording lookup happen at all — a recording is
     * addressed by key, not by matching text.
     */
    @Synchronized
    fun speak(stringKey: String, fallbackText: String? = null, onComplete: (() -> Unit)? = null) {
        stop()
        completion = onComplete
        // A broken engine must not hold an assessment paused indefinitely.
        completionTimeout = Runnable {
            synchronized(this@NarrationPlayer) {
                val callback = completion
                release()
                _state.value = _state.value.copy(source = Source.UNAVAILABLE)
                callback?.invoke()
            }
        }.also { mainHandler.postDelayed(it, MAX_NARRATION_MS) }

        if (playRecording(stringKey)) return

        _state.value = _state.value.copy(source = Source.UNAVAILABLE)
        if (languageTag == SANTALI) { completeNarration(); return }

        val text = fallbackText ?: run {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(languageTag))
            val localised = context.createConfigurationContext(configuration)
            val id = CatalogStrings.resourceId(localised, stringKey)
            if (id == 0) "" else localised.getString(id)
        }
        if (text.isBlank()) { completeNarration(); return }
        if (initialising) {
            pendingPrompt = stringKey to text
            return
        }

        val engine = tts
        if (!ttsReady || !ttsUsable || engine == null) {
            // Nothing to play. The caller keeps the text on screen, which it does anyway.
            completeNarration()
            return
        }

        val utteranceId = "narration-${++utteranceSequence}"
        activeUtterance = utteranceId
        _state.value = _state.value.copy(source = Source.SYNTHESISED)
        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (result == TextToSpeech.ERROR) {
            activeUtterance = null
            _state.value = _state.value.copy(source = Source.UNAVAILABLE, speaking = false)
            completeNarration()
        }
    }

    private fun completeNarration() {
        completionTimeout?.let(mainHandler::removeCallbacks)
        completionTimeout = null
        val callback = completion
        completion = null
        callback?.invoke()
    }

    /**
     * Plays a bundled recording if one exists.
     *
     * Resolved through `resources.getIdentifier`, which is normally a smell. It is right here: prompt keys
     * come from the scenario catalog at runtime, so there is no compile-time symbol to reference, and the
     * alternative is a hand-maintained map of 222 keys that would drift the moment somebody adds a
     * scenario option.
     */
    private fun playRecording(stringKey: String): Boolean {
        val resourceName = "${languageTag}_$stringKey"
        val resId = context.resources.getIdentifier(resourceName, "raw", context.packageName)
        if (resId == 0) return false

        var created: MediaPlayer? = null
        return try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val player = MediaPlayer.create(context, resId, attributes, 0) ?: return false
            created = player
            mediaPlayer = player
            player.setOnCompletionListener { completed -> finishRecording(completed, failed = false) }
            player.setOnErrorListener { failed, _, _ ->
                finishRecording(failed, failed = true)
                true
            }
            _state.value = _state.value.copy(speaking = true, source = Source.RECORDED)
            player.start()
            true
        } catch (e: RuntimeException) {
            if (mediaPlayer === created) mediaPlayer = null
            runCatching { created?.release() }
            _state.value = _state.value.copy(speaking = false)
            Log.w(TAG, "media player refused to start for $stringKey", e)
            false
        }
    }

    @Synchronized
    private fun finishRecording(player: MediaPlayer, failed: Boolean) {
        if (mediaPlayer !== player) return
        mediaPlayer = null
        runCatching { player.release() }
        _state.value = _state.value.copy(
            speaking = false,
            source = if (failed) Source.UNAVAILABLE else Source.RECORDED,
        )
        completeNarration()
    }

    @Synchronized
    fun stop() {
        completionTimeout?.let(mainHandler::removeCallbacks)
        completionTimeout = null
        completion = null
        pendingPrompt = null
        activeUtterance = null
        runCatching { tts?.stop() }
        val recording = mediaPlayer
        mediaPlayer = null
        recording?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        _state.value = _state.value.copy(speaking = false)
    }

    @Synchronized
    fun release() {
        stop()
        engineGeneration++
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        ttsUsable = false
        initialising = false
    }

    /**
     * Whether any recording exists for a language.
     *
     * Probes one known key rather than enumerating resources. Enumeration means reflecting over the R
     * class, which R8 is entitled to strip, and a capability check that stops working in release builds is
     * worse than a slightly narrow probe.
     */
    private fun hasAnyRecording(tag: String): Boolean =
        context.resources.getIdentifier("${tag}_$PROBE_KEY", "raw", context.packageName) != 0

    private companion object {
        const val TAG = "NarrationPlayer"

        const val HINDI = "hi"
        const val SANTALI = "sat"

        const val SPEECH_RATE = 0.9f
        const val MAX_NARRATION_MS = 60_000L

        /** Present in every complete recording set, so its absence means the set is absent. */
        const val PROBE_KEY = "step_fire_detect_alarm_prompt"
    }
}
