package org.jaagruk.safety.ui.supervisor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaagruk.core.speech.EnrollmentVerdict
import org.jaagruk.core.speech.KeywordSpotter
import org.jaagruk.core.speech.MfccConfig
import org.jaagruk.core.speech.MfccSequence
import org.jaagruk.core.speech.VoiceCommand
import org.jaagruk.core.speech.VoiceEnrollment
import org.jaagruk.safety.R
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.input.VoiceTemplateRepository
import org.jaagruk.safety.ui.LocaleManager
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.UiMessage
import org.jaagruk.safety.ui.components.catalogString
import javax.inject.Inject

/**
 * Voice enrolment: recording the command vocabulary for a site.
 *
 * This exists because there is no usable Santali acoustic model — not a poor one, none. Vosk, Whisper and
 * Google's on-device ASR all lack it, and the language has around seven million speakers concentrated exactly
 * where this app is meant to run. Waiting for someone to build a corpus is not a plan.
 *
 * So a supervisor records nineteen fixed commands once per site, in whatever the workers there actually speak,
 * and matching runs offline as MFCC plus DTW. Two repetitions per command, and the two are checked against
 * each other before anything is stored: a bad template is worse than no template, because it produces
 * confident wrong answers during a drill.
 */
@Composable
fun VoiceEnrollScreen(
    onBack: () -> Unit,
    viewModel: VoiceEnrollViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.voiceenroll_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.voiceenroll_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocaleManager.supported.forEach { tag ->
                        if (tag == state.languageTag) {
                            GloveButton(
                                text = LocaleManager.endonym(tag),
                                onClick = { viewModel.setLanguage(tag) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            GloveOutlinedButton(
                                text = LocaleManager.endonym(tag),
                                onClick = { viewModel.setLanguage(tag) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.voiceenroll_progress,
                        state.enrolledCount,
                        KeywordSpotter.MIN_USABLE_COMMANDS,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (!micGranted) {
            item {
                SectionCard {
                    Text(
                        text = stringResource(R.string.voiceenroll_mic_needed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    GloveButton(
                        text = stringResource(R.string.action_allow_microphone),
                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (state.message != null) {
            item { MessageBanner(state.message, stringResource(R.string.cd_info)) }
        }

        items(state.commands, key = { it.command.name }) { row ->
            SectionCard {
                Text(
                    text = catalogString(row.command.labelKey),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.voiceenroll_recordings,
                        row.storedRepetitions,
                        VoiceEnrollment.REQUIRED_REPETITIONS,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                GloveButton(
                    text = stringResource(
                        if (row.storedRepetitions > 0) {
                            R.string.voiceenroll_rerecord
                        } else {
                            R.string.voiceenroll_record
                        },
                    ),
                    onClick = { viewModel.recordCommand(row.command) },
                    enabled = micGranted && !state.recording,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Column {
                if (state.enrolledCount in 1 until KeywordSpotter.MIN_USABLE_COMMANDS) {
                    // Hidden rather than offered broken. A worker who tries voice three times and is ignored
                    // will stop using the working input too.
                    StatusBanner(
                        text = stringResource(
                            R.string.voiceenroll_not_yet_usable,
                            KeywordSpotter.MIN_USABLE_COMMANDS,
                        ),
                        tone = BannerTone.WARNING,
                        pictogramDescription = stringResource(R.string.cd_warning),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                GloveOutlinedButton(
                    text = stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@HiltViewModel
class VoiceEnrollViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val templates: VoiceTemplateRepository,
    private val deviceProfile: DeviceProfile,
) : ViewModel() {

    data class CommandRow(
        val command: VoiceCommand,
        val storedRepetitions: Int,
    )

    data class State(
        val languageTag: String = LocaleManager.SANTALI,
        val commands: List<CommandRow> = emptyList(),
        val enrolledCount: Int = 0,
        val recording: Boolean = false,
        val currentCommand: VoiceCommand? = null,
        val message: UiMessage? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setLanguage(tag: String) {
        _state.value = _state.value.copy(languageTag = tag)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val tag = _state.value.languageTag
            val loaded = templates.load(tag)
            val counts = loaded.groupingBy { it.command }.eachCount()

            _state.value = _state.value.copy(
                commands = VoiceCommand.entries.map { command ->
                    CommandRow(command, counts[command] ?: 0)
                },
                enrolledCount = counts.keys.size,
            )
        }
    }

    /**
     * Records the required repetitions for one command, back to back.
     *
     * Each take is a fixed window rather than voice-activated. A supervisor recording nineteen commands wants a
     * predictable rhythm — press, say it, press, say it — and an endpointer that cuts a short word off would
     * produce exactly the inconsistent template this flow exists to reject.
     */
    fun recordCommand(command: VoiceCommand) {
        if (_state.value.recording) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                recording = true,
                currentCommand = command,
                message = UiMessage.info(R.string.voiceenroll_speak_now),
            )

            val takes = mutableListOf<MfccSequence>()
            repeat(VoiceEnrollment.REQUIRED_REPETITIONS) { index ->
                _state.value = _state.value.copy(
                    message = UiMessage.info(R.string.voiceenroll_take, index + 1),
                )
                val pcm = withContext(Dispatchers.IO) { recordWindow() }
                if (pcm != null) takes += templates.featuresOf(pcm)
            }

            if (takes.size < VoiceEnrollment.REQUIRED_REPETITIONS) {
                _state.value = _state.value.copy(
                    recording = false,
                    currentCommand = null,
                    message = UiMessage.error(R.string.voiceenroll_mic_failed),
                )
                return@launch
            }

            // Assessed before anything is stored. Two takes that do not match each other mean the supervisor
            // coughed, cut a word off, or said something different — and storing that would put confident
            // wrong answers into a live drill.
            val assessment = templates.assess(takes)
            if (!assessment.isAcceptable) {
                _state.value = _state.value.copy(
                    recording = false,
                    currentCommand = null,
                    message = UiMessage.error(verdictLabel(assessment.verdict)),
                )
                return@launch
            }

            val siteId = deviceProfile.activeSiteId()
            val stored = templates.saveEnrollment(
                command = command,
                languageTag = _state.value.languageTag,
                siteId = siteId,
                repetitions = takes,
            )

            _state.value = _state.value.copy(
                recording = false,
                currentCommand = null,
                message = UiMessage.success(R.string.voiceenroll_saved, stored),
            )
            refresh()
        }
    }

    /**
     * Captures one fixed-length window of 16 kHz mono PCM.
     *
     * Same format the drill's recogniser uses, so a template and a live utterance go through an identical
     * feature pipeline. Recording at a different rate here and resampling later is how a matcher ends up
     * comparing spectra that were never comparable.
     */
    @SuppressLint("MissingPermission")
    private fun recordWindow(): ShortArray? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val config = MfccConfig.DEFAULT
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, config.sampleRate),
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not open the microphone for enrolment", e)
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        val totalSamples = config.sampleRate * WINDOW_SECONDS
        val buffer = ShortArray(totalSamples)
        var offset = 0

        return try {
            record.startRecording()
            while (offset < totalSamples) {
                val read = record.read(buffer, offset, totalSamples - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset == 0) null else buffer.copyOf(offset)
        } catch (e: Exception) {
            Log.w(TAG, "enrolment recording failed", e)
            null
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun verdictLabel(verdict: EnrollmentVerdict): Int = when (verdict) {
        EnrollmentVerdict.ACCEPTED -> R.string.voiceenroll_accepted
        EnrollmentVerdict.INCONSISTENT -> R.string.voiceenroll_inconsistent
        EnrollmentVerdict.TOO_SHORT -> R.string.voiceenroll_too_short
        EnrollmentVerdict.NOT_ENOUGH_REPETITIONS -> R.string.voiceenroll_not_enough
    }

    private companion object {
        const val TAG = "VoiceEnrollViewModel"

        /** Two seconds per take. Long enough for any of the nineteen words, short enough to stay brisk. */
        const val WINDOW_SECONDS = 2
    }
}
