package org.jaagruk.safety.input

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jaagruk.core.speech.EnrollmentAssessment
import org.jaagruk.core.speech.MfccCodec
import org.jaagruk.core.speech.MfccExtractor
import org.jaagruk.core.speech.MfccSequence
import org.jaagruk.core.speech.VoiceCommand
import org.jaagruk.core.speech.VoiceEnrollment
import org.jaagruk.core.speech.VoiceTemplate
import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.db.VoiceTemplateEntity
import java.util.UUID

/**
 * Stores and loads the per-site voice command templates.
 *
 * **Why enrolment exists at all.** There is no usable Santali acoustic model. Not a poor one — none.
 * Vosk, Whisper and Google's on-device ASR all have no Santali, and the language has around seven million
 * speakers concentrated exactly where this app is meant to run. Waiting for someone to build a corpus is
 * not a plan.
 *
 * So the vocabulary is fixed and tiny — nineteen commands — and a supervisor records it once per site in
 * whatever the workers there actually speak. Matching is MFCC plus DTW in `:core`, entirely offline, with
 * no model download. It generalises across speakers less well than an acoustic model would, which is why
 * the thresholds were measured rather than guessed and why a low-margin match asks the worker to repeat
 * instead of guessing.
 *
 * Enrolment quality is assessed, not assumed. Two recordings that do not match each other mean the
 * supervisor coughed, cut a word off, or said something different — and a bad template is worse than no
 * template, because it produces confident wrong answers during a drill.
 */
class VoiceTemplateRepository(
    private val database: JaagrukDatabase,
    private val clock: WallClock,
    private val extractor: MfccExtractor = MfccExtractor(),
) {

    private val templates = database.voiceTemplateDao()

    fun observeEnrolledCommandCount(languageTag: String): Flow<Int> =
        templates.observeForLanguage(languageTag).map { rows ->
            rows.map { it.commandKey }.distinct().size
        }

    suspend fun distinctCommandCount(languageTag: String): Int =
        templates.distinctCommandCount(languageTag)

    /**
     * Loads the templates a spotter should match against.
     *
     * A blob that will not decode is skipped with a log line rather than throwing. One corrupted row must
     * not disable voice input entirely — the other eighteen commands still work, and the supervisor can
     * re-record the one that broke.
     */
    suspend fun load(languageTag: String): List<VoiceTemplate> =
        templates.forLanguage(languageTag).mapNotNull { row ->
            val command = VoiceCommand.fromKey(row.commandKey)
            if (command == null) {
                Log.w(TAG, "stored template names unknown command '${row.commandKey}'")
                return@mapNotNull null
            }
            val sequence = try {
                MfccCodec.decode(row.mfccBlob)
            } catch (e: CanonicalFormatException) {
                Log.w(TAG, "template ${row.templateId} has a corrupt MFCC blob", e)
                return@mapNotNull null
            }
            VoiceTemplate(
                templateId = row.templateId,
                command = command,
                languageTag = row.languageTag,
                sequence = sequence,
                enrolledAtEpochSec = row.enrolledAtSec,
                siteId = row.siteId,
            )
        }

    /** Extracts features from raw 16 kHz mono PCM. Kept here so callers never touch the extractor. */
    fun featuresOf(pcm: ShortArray): MfccSequence = extractor.extract(pcm)

    /**
     * Checks a set of repetitions before anything is stored.
     *
     * Deliberately called before [saveEnrollment] rather than inside it. A supervisor who is told
     * "these two did not sound the same, record again" understands what to fix; one whose recording was
     * silently rejected will assume the app is broken, and one whose bad recording was silently accepted
     * will find out during a drill.
     */
    fun assess(repetitions: List<MfccSequence>): EnrollmentAssessment =
        VoiceEnrollment.assess(repetitions)

    /**
     * Replaces the templates for one command.
     *
     * Replaces rather than appends: re-recording is how a supervisor fixes a command that is not being
     * recognised, and leaving the old recordings in place would keep the bad match competing.
     */
    suspend fun saveEnrollment(
        command: VoiceCommand,
        languageTag: String,
        siteId: String?,
        repetitions: List<MfccSequence>,
    ): Int {
        templates.clearCommand(command.commandKey, languageTag)
        var stored = 0
        val nowSec = clock.epochSeconds()

        for (sequence in repetitions) {
            if (sequence.isEmpty) continue
            val blob = try {
                MfccCodec.encode(sequence)
            } catch (e: IllegalArgumentException) {
                // Over the codec's frame cap: someone held the button for ten seconds. Skipped rather
                // than truncated, because a truncated template matches the wrong things.
                Log.w(TAG, "recording for ${command.commandKey} is too long to store", e)
                continue
            }
            templates.upsert(
                VoiceTemplateEntity(
                    templateId = UUID.randomUUID().toString(),
                    commandKey = command.commandKey,
                    languageTag = languageTag,
                    siteId = siteId,
                    mfccBlob = blob,
                    frameCount = sequence.frameCount,
                    enrolledAtSec = nowSec,
                ),
            )
            stored++
        }
        return stored
    }

    suspend fun clearCommand(command: VoiceCommand, languageTag: String) =
        templates.clearCommand(command.commandKey, languageTag)

    suspend fun clearLanguage(languageTag: String) = templates.clearLanguage(languageTag)

    private companion object {
        const val TAG = "VoiceTemplateRepo"
    }
}
