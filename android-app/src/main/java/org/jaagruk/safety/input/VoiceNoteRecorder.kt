package org.jaagruk.safety.input

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource
import org.jaagruk.safety.data.LocalMediaStore
import java.io.File

/**
 * Records the spoken half of a hazard report.
 *
 * The most important input path in the hazard flow, and the reason near-miss reporting works at all here. A
 * worker who cannot comfortably write will not type a description of a blocked exit. They will say it in
 * fifteen seconds, and a supervisor will understand it immediately.
 *
 * Deliberately capped at 60 seconds and AAC at 32 kbit/s mono. A voice note is a description, not a
 * statement, and a 40 kB file syncs over a mine-site uplink where a 2 MB one does not.
 */
class VoiceNoteRecorder(
    private val context: Context,
    private val mediaStore: LocalMediaStore,
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
) {

    data class State(
        val recording: Boolean = false,
        val elapsedMs: Long = 0L,
        /** 0.0..1.0 for the level meter, so the worker can see it is hearing them. */
        val amplitude: Float = 0f,
        val file: File? = null,
        val errorKey: String? = null,
    ) {
        val remainingMs: Long get() = (MAX_DURATION_MS - elapsedMs).coerceAtLeast(0L)
        val atLimit: Boolean get() = elapsedMs >= MAX_DURATION_MS
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAtMs: Long = 0L

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts recording to a scratch file.
     *
     * Scratch, not the hazard's final path, because the hazard id does not exist until the report is filed.
     * A cancelled report therefore leaves a scratch file that gets swept up, rather than a stray recording
     * named after a hazard that was never created.
     */
    fun start(): Boolean {
        if (recorder != null) return false
        if (!hasPermission()) {
            _state.value = State(errorKey = "voice_note_no_permission")
            return false
        }

        val file = mediaStore.scratchVoiceFile()
        val created = try {
            @Suppress("DEPRECATION")
            val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setMaxDuration(MAX_DURATION_MS.toInt())
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Microphone held by another app, or an OEM codec that refuses these parameters. Either way the
            // hazard report still goes ahead without audio; the text and photo are what an officer acts on.
            Log.w(TAG, "could not start the voice note recorder", e)
            runCatching { file.delete() }
            _state.value = State(errorKey = "voice_note_unavailable")
            return false
        }

        recorder = created
        target = file
        startedAtMs = monotonic.elapsedMillis()
        _state.value = State(recording = true, file = file)
        return true
    }

    /** Call on a UI tick to refresh elapsed time and level, and to enforce the cap. */
    fun tick(): Boolean {
        val active = recorder ?: return false
        val elapsed = monotonic.elapsedMillis() - startedAtMs
        val amplitude = try {
            (active.maxAmplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
        } catch (e: Exception) {
            0f
        }
        _state.value = _state.value.copy(elapsedMs = elapsed, amplitude = amplitude)

        if (elapsed >= MAX_DURATION_MS) {
            stop()
            return false
        }
        return true
    }

    /** Stops and returns the recording, or null when it was too short to be useful. */
    fun stop(): File? {
        val active = recorder ?: return null
        recorder = null

        val elapsed = monotonic.elapsedMillis() - startedAtMs
        try {
            active.stop()
        } catch (e: RuntimeException) {
            // Thrown when stop() lands before any frame was written — a tap rather than a hold. The output
            // file is invalid, so it is deleted rather than uploaded as a zero-length note.
            Log.i(TAG, "recording was too short to finalise")
            active.release()
            target?.delete()
            target = null
            _state.value = State(errorKey = "voice_note_too_short")
            return null
        }
        active.release()

        val file = target
        target = null

        if (elapsed < MIN_DURATION_MS || file == null || !file.exists() || file.length() < MIN_BYTES) {
            file?.delete()
            _state.value = State(errorKey = "voice_note_too_short")
            return null
        }

        _state.value = State(recording = false, elapsedMs = elapsed, file = file)
        return file
    }

    /** Abandons the recording and deletes the file. */
    fun cancel() {
        recorder?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        recorder = null
        target?.delete()
        target = null
        _state.value = State()
    }

    fun clearError() {
        _state.value = _state.value.copy(errorKey = null)
    }

    companion object {
        private const val TAG = "VoiceNoteRecorder"

        /** A description, not a statement. */
        const val MAX_DURATION_MS: Long = 60_000L

        /** Below this it is a mis-tap, not a note. */
        const val MIN_DURATION_MS: Long = 700L
        private const val MIN_BYTES = 512L

        /** 16 kHz mono AAC at 32 kbit/s: intelligible speech at roughly 4 kB per second. */
        private const val SAMPLE_RATE = 16_000
        private const val BIT_RATE = 32_000

        /** `MediaRecorder.getMaxAmplitude` is 16-bit full scale. */
        private const val MAX_AMPLITUDE = 32_767f
    }
}
