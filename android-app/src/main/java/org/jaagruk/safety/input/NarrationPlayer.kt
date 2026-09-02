package org.jaagruk.safety.input

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
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
    private var mediaPlayer: MediaPlayer? = null
    private var languageTag: String = "en"

    /**
     * Initialises for [tag].
     *
     * `sat` deliberately never reaches the TTS engine. Some engines respond to an unknown locale by
     * silently falling back to English, so a Santali prompt would be read aloud in English — confidently,
     * and wrongly, to a worker who cannot tell that is what happened.
     */
    fun prepare(tag: String) {
        languageTag = tag

        if (tag == SANTALI) {
            _state.value = State(
                source = if (hasAnyRecording(tag)) Source.RECORDED else Source.UNAVAILABLE,
                languageTag = tag,
                ttsMissingForLanguage = true,
            )
            return
        }

        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) applyLanguage(tag) else reportUnavailable(tag)
            }.also { engine ->
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _state.value = _state.value.copy(speaking = true)
                        }

                        override fun onDone(utteranceId: String?) {
                            _state.value = _state.value.copy(speaking = false)
                        }

                        @Deprecated("Required by the platform interface")
                        override fun onError(utteranceId: String?) {
                            _state.value = _state.value.copy(speaking = false)
                        }
                    },
                )
            }
        } else {
            applyLanguage(tag)
        }
    }

    private fun applyLanguage(tag: String) {
        val engine = tts ?: return reportUnavailable(tag)
        val locale = when (tag) {
            HINDI -> Locale("hi", "IN")
            else -> Locale.ENGLISH
        }

        val result = engine.setLanguage(locale)
        val usable = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED

        // Slower than default. Synthesised Hindi at normal rate is hard to follow through a helmet, and a
        // safety instruction that has to be replayed twice has failed.
        engine.setSpeechRate(SPEECH_RATE)

        _state.value = State(
            source = when {
                hasAnyRecording(tag) -> Source.RECORDED
                usable -> Source.SYNTHESISED
                else -> Source.UNAVAILABLE
            },
            languageTag = tag,
            ttsMissingForLanguage = !usable,
        )
    }

    private fun reportUnavailable(tag: String) {
        _state.value = State(
            source = if (hasAnyRecording(tag)) Source.RECORDED else Source.UNAVAILABLE,
            languageTag = tag,
            ttsMissingForLanguage = true,
        )
    }

    /**
     * Speaks the prompt for [stringKey].
     *
     * [fallbackText] is the already-localised string, used when no recording exists and TTS is available.
     * Passing the key as well as the text is what lets the recording lookup happen at all — a recording is
     * addressed by key, not by matching text.
     */
    fun speak(stringKey: String, fallbackText: String) {
        stop()

        if (playRecording(stringKey)) return

        val engine = tts
        if (!ttsReady || engine == null || _state.value.source != Source.SYNTHESISED) {
            // Nothing to play. The caller keeps the text on screen, which it does anyway.
            return
        }

        engine.speak(
            fallbackText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            stringKey,
        )
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

        return try {
            mediaPlayer = MediaPlayer.create(context, resId)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // ASSISTANCE_SONIFICATION, not MEDIA: this must not be silenced by a media
                        // volume of zero, which is how most shared site phones are handed over.
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setOnCompletionListener {
                    _state.value = _state.value.copy(speaking = false)
                }
                start()
            } ?: return false
            _state.value = _state.value.copy(speaking = true, source = Source.RECORDED)
            true
        } catch (e: IOException) {
            Log.w(TAG, "could not play the recording for $stringKey", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "media player refused to start for $stringKey", e)
            false
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        _state.value = _state.value.copy(speaking = false)
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
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

        /** Present in every complete recording set, so its absence means the set is absent. */
        const val PROBE_KEY = "step_fire_detect_alarm_prompt"
    }
}
