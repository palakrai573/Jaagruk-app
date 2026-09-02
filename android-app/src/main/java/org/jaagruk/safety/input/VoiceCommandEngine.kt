package org.jaagruk.safety.input

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaagruk.core.speech.KeywordSpotter
import org.jaagruk.core.speech.MfccConfig
import org.jaagruk.core.speech.MfccSequence
import org.jaagruk.core.speech.SpotResult
import org.jaagruk.core.speech.SpotterConfig
import org.jaagruk.core.speech.VoiceCommand
import kotlin.math.sqrt

/**
 * Listens for the enrolled command vocabulary while a drill runs.
 *
 * Built around one constraint that rules out every off-the-shelf option: a worker in a helmet and gloves,
 * standing next to a running conveyor, with no network. No cloud ASR (no network), no downloaded acoustic
 * model (no Santali model exists, and a 50 MB download at a mine gate is not happening), no wake word
 * service (they all need a model too).
 *
 * What is left is a fixed vocabulary matched against per-site recordings, and it works because the
 * problem is small: nineteen words, known in advance, and a wrong answer is allowed to be "say that
 * again" rather than a guess.
 *
 * Three properties keep it honest in a loud place:
 *
 *  * **Energy-based endpointing, not continuous recognition.** An utterance is only matched once silence
 *    follows it. Streaming recognition against conveyor noise produces a match every second or so.
 *  * **A margin requirement, not just a threshold.** If the best two candidates are close, nothing is
 *    accepted. Measured separation between commands is 2.38 and between recordings of the same command
 *    0.59 to 0.68, so a 0.15 margin is comfortably inside real acoustic distance rather than a guess.
 *  * **The allowed set is narrowed per step.** During a four-option step only "one" to "four" plus the
 *    always-available commands can win, which removes most of the ways a stray word can be misheard as an
 *    answer.
 */
class VoiceCommandEngine(
    private val context: Context,
    private val templateRepository: VoiceTemplateRepository,
    private val scope: CoroutineScope,
) {

    enum class Availability {
        /** Enough commands enrolled and permission granted. */
        READY,

        /** Microphone permission has not been granted. */
        NO_PERMISSION,

        /** Fewer than the minimum usable commands are enrolled for this language. */
        NOT_ENROLLED,

        /** No usable microphone, or `AudioRecord` refused to initialise. */
        NO_MICROPHONE,
    }

    data class Level(
        /** 0.0..1.0 input level, for the microphone indicator. */
        val amplitude: Float = 0f,
        val listening: Boolean = false,
        val capturingUtterance: Boolean = false,
    )

    private val _availability = MutableStateFlow(Availability.NOT_ENROLLED)
    val availability: StateFlow<Availability> = _availability.asStateFlow()

    private val _level = MutableStateFlow(Level())
    val level: StateFlow<Level> = _level.asStateFlow()

    /**
     * Recognition outcomes, including rejections.
     *
     * Rejections are published deliberately: "I heard something but could not tell which command" is
     * information the worker needs, and swallowing it makes the app look deaf.
     */
    private val _results = MutableSharedFlow<SpotResult>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val results: SharedFlow<SpotResult> = _results.asSharedFlow()

    private val config = MfccConfig.DEFAULT

    @Volatile
    private var spotter: KeywordSpotter? = null

    @Volatile
    private var allowedCommands: Set<VoiceCommand>? = null

    @Volatile
    private var noisyEnvironment: Boolean = false

    private var captureJob: Job? = null

    /**
     * Loads templates for [languageTag] and reports whether voice input should be offered.
     *
     * The UI hides the microphone entirely when this is not [Availability.READY]. Offering a control that
     * cannot work is worse than not offering it: a worker who tries voice three times and is ignored will
     * not try the working input either.
     */
    suspend fun prepare(languageTag: String, noisy: Boolean = false): Availability {
        noisyEnvironment = noisy

        if (!hasPermission()) {
            _availability.value = Availability.NO_PERMISSION
            return Availability.NO_PERMISSION
        }

        val loaded = templateRepository.load(languageTag)
        val built = KeywordSpotter(
            templates = loaded,
            config = if (noisy) SpotterConfig.NOISY_ENVIRONMENT else SpotterConfig.DEFAULT,
        )
        spotter = built

        val availability = if (built.isUsable()) Availability.READY else Availability.NOT_ENROLLED
        _availability.value = availability
        if (availability != Availability.READY) {
            Log.i(
                TAG,
                "voice input unavailable: ${built.enrolledCommands.size} of " +
                    "${KeywordSpotter.MIN_USABLE_COMMANDS} required commands enrolled for $languageTag",
            )
        }
        return availability
    }

    /** Restricts what can be recognised for the current step. */
    fun setAllowedCommands(commands: Set<VoiceCommand>?) {
        allowedCommands = commands?.plus(VoiceCommand.ALWAYS_AVAILABLE)
    }

    fun startListening() {
        if (captureJob?.isActive == true) return
        if (_availability.value != Availability.READY) return

        captureJob = scope.launch(Dispatchers.Default) { captureLoop() }
    }

    fun stopListening() {
        captureJob?.cancel()
        captureJob = null
        _level.value = Level()
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * The capture loop: read, measure energy, endpoint, recognise.
     *
     * `VOICE_RECOGNITION` as the audio source, not `MIC`: it enables the platform's own noise suppression
     * and AGC on most devices, and skips the media-recording processing chain that colours the spectrum
     * MFCC extraction depends on.
     */
    @SuppressLint("MissingPermission")
    private suspend fun captureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            _availability.value = Availability.NO_MICROPHONE
            return
        }

        val bufferSize = maxOf(minBuffer * 2, config.sampleRate / 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not open the microphone", e)
            _availability.value = Availability.NO_MICROPHONE
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _availability.value = Availability.NO_MICROPHONE
            return
        }

        val chunk = ShortArray(CHUNK_SAMPLES)
        val utterance = ArrayList<Short>(config.sampleRate * MAX_UTTERANCE_SECONDS)
        var silentChunks = 0
        var capturing = false
        var noiseFloor = INITIAL_NOISE_FLOOR

        try {
            record.startRecording()
            _level.value = Level(listening = true)

            while (scope.isActive && captureJob?.isActive == true) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                val rms = rootMeanSquare(chunk, read)
                // The floor adapts slowly upward and quickly downward, so a conveyor starting up raises
                // the threshold within a second or two while a shout does not permanently deafen it.
                noiseFloor = if (rms > noiseFloor) {
                    noiseFloor * 0.98f + rms * 0.02f
                } else {
                    noiseFloor * 0.90f + rms * 0.10f
                }

                val speechThreshold = (noiseFloor * SPEECH_OVER_FLOOR).coerceAtLeast(MIN_SPEECH_RMS)
                val isSpeech = rms > speechThreshold

                _level.value = Level(
                    amplitude = (rms / FULL_SCALE_RMS).coerceIn(0f, 1f),
                    listening = true,
                    capturingUtterance = capturing,
                )

                if (isSpeech) {
                    capturing = true
                    silentChunks = 0
                    for (i in 0 until read) utterance.add(chunk[i])

                    if (utterance.size > config.sampleRate * MAX_UTTERANCE_SECONDS) {
                        // Somebody is talking, not commanding. Discarded rather than matched: a long
                        // utterance will DTW-match something eventually, and that something will be wrong.
                        utterance.clear()
                        capturing = false
                    }
                } else if (capturing) {
                    silentChunks++
                    // A short trailing silence still gets appended, because cutting a word's release
                    // changes its spectral tail and DTW is sensitive to exactly that.
                    for (i in 0 until read) utterance.add(chunk[i])

                    if (silentChunks >= SILENCE_CHUNKS_TO_END) {
                        val pcm = ShortArray(utterance.size) { utterance[it] }
                        utterance.clear()
                        capturing = false
                        silentChunks = 0
                        recognise(pcm)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio capture stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            _level.value = Level()
        }
    }

    private suspend fun recognise(pcm: ShortArray) {
        val active = spotter ?: return
        val features: MfccSequence = withContext(Dispatchers.Default) {
            templateRepository.featuresOf(pcm)
        }
        if (features.isEmpty) return

        val result = active.recognise(features, allowedCommands)
        _results.emit(result)

        if (result is SpotResult.Match) {
            Log.d(
                TAG,
                "recognised ${result.command.commandKey} " +
                    "(cost=${"%.3f".format(result.cost)}, margin=${"%.3f".format(result.margin)})",
            )
        }
    }

    private fun rootMeanSquare(buffer: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count).toFloat()
    }

    private companion object {
        const val TAG = "VoiceCommandEngine"

        /** 100 ms at 16 kHz. Small enough for responsive endpointing, large enough for stable RMS. */
        const val CHUNK_SAMPLES = 1_600

        /** Six chunks of silence — about 600 ms — ends an utterance. */
        const val SILENCE_CHUNKS_TO_END = 6

        /** Anything longer than this is conversation, not a command. */
        const val MAX_UTTERANCE_SECONDS = 3

        /** Speech must exceed the adaptive noise floor by this factor. */
        const val SPEECH_OVER_FLOOR = 2.2f

        /** Absolute floor so a silent room does not trigger on its own dither. */
        const val MIN_SPEECH_RMS = 450f

        const val INITIAL_NOISE_FLOOR = 300f

        /** RMS of a full-scale 16-bit sine, used to normalise the level meter. */
        const val FULL_SCALE_RMS = 23_170f
    }
}
