package org.jaagruk.core.speech

import org.jaagruk.core.util.Base64Url
import org.jaagruk.core.util.CanonicalFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How a recognised command should be applied. */
enum class VoiceCommandKind {
    /** Selects the nth visible option. */
    OPTION_INDEX,

    /** Selects a spatial option by direction. */
    DIRECTION,

    /** Confirms or cancels. */
    CONFIRM,

    /** Moves through the drill. */
    NAVIGATION,

    /** Names a hazard or item directly, for steps where that is unambiguous. */
    KEYWORD,

    /** Acts on the UI rather than the drill. */
    UTILITY,
}

/**
 * The fixed voice vocabulary.
 *
 * Fixed, and small, on purpose. Open-ended speech recognition is not achievable here — there is
 * no Santali acoustic model, and offline Hindi recognition is unreliable across OEM firmware.
 * Eighteen short, acoustically distinct words that a supervisor can enrol in about four minutes
 * *is* achievable, works with no network, and covers every input a drill needs.
 *
 * [commandKey] is the stable storage key and must never change: enrolled templates on deployed
 * devices are keyed by it.
 */
enum class VoiceCommand(
    val commandKey: String,
    val labelKey: String,
    val kind: VoiceCommandKind,
    /** 1-based option position for [VoiceCommandKind.OPTION_INDEX]. */
    val optionIndex: Int? = null,
) {
    ONE("one", "voice_cmd_one", VoiceCommandKind.OPTION_INDEX, 1),
    TWO("two", "voice_cmd_two", VoiceCommandKind.OPTION_INDEX, 2),
    THREE("three", "voice_cmd_three", VoiceCommandKind.OPTION_INDEX, 3),
    FOUR("four", "voice_cmd_four", VoiceCommandKind.OPTION_INDEX, 4),

    LEFT("left", "voice_cmd_left", VoiceCommandKind.DIRECTION),
    RIGHT("right", "voice_cmd_right", VoiceCommandKind.DIRECTION),
    STRAIGHT("straight", "voice_cmd_straight", VoiceCommandKind.DIRECTION),

    YES("yes", "voice_cmd_yes", VoiceCommandKind.CONFIRM),
    NO("no", "voice_cmd_no", VoiceCommandKind.CONFIRM),

    BACK("back", "voice_cmd_back", VoiceCommandKind.NAVIGATION),
    NEXT("next", "voice_cmd_next", VoiceCommandKind.NAVIGATION),
    STOP("stop", "voice_cmd_stop", VoiceCommandKind.NAVIGATION),

    EXIT("exit", "voice_cmd_exit", VoiceCommandKind.KEYWORD),
    FIRE("fire", "voice_cmd_fire", VoiceCommandKind.KEYWORD),
    GAS("gas", "voice_cmd_gas", VoiceCommandKind.KEYWORD),
    MASK("mask", "voice_cmd_mask", VoiceCommandKind.KEYWORD),
    BUDDY("buddy", "voice_cmd_buddy", VoiceCommandKind.KEYWORD),

    HELP("help", "voice_cmd_help", VoiceCommandKind.UTILITY),
    REPEAT("repeat", "voice_cmd_repeat", VoiceCommandKind.UTILITY),
    ;

    companion object {
        private val byKey: Map<String, VoiceCommand> = entries.associateBy { it.commandKey }

        fun fromKey(commandKey: String): VoiceCommand? = byKey[commandKey.lowercase()]

        /** Commands every step accepts regardless of its options. */
        val ALWAYS_AVAILABLE: Set<VoiceCommand> = setOf(HELP, REPEAT, STOP, BACK)

        val OPTION_SELECTORS: List<VoiceCommand> = listOf(ONE, TWO, THREE, FOUR)
    }
}

/**
 * One enrolled acoustic template.
 *
 * Templates are per site and per language, because they are recorded by a person who actually
 * speaks that language. That also sidesteps the dialect problem: Santali as spoken around
 * Dumka is not identical to Santali around Jamshedpur, and a template enrolled locally matches
 * local speech.
 */
class VoiceTemplate(
    val templateId: String,
    val command: VoiceCommand,
    val languageTag: String,
    val sequence: MfccSequence,
    val enrolledAtEpochSec: Long,
    val siteId: String? = null,
) {
    init {
        require(templateId.isNotBlank()) { "templateId must not be blank" }
        require(languageTag.isNotBlank()) { "languageTag must not be blank for $templateId" }
        require(!sequence.isEmpty) { "template $templateId has no MFCC frames" }
        require(enrolledAtEpochSec >= 0) { "enrolledAtEpochSec must be >= 0" }
    }

    override fun toString(): String =
        "VoiceTemplate($templateId, ${command.commandKey}, $languageTag, ${sequence.frameCount} frames)"
}

class SpotterConfig(
    /**
     * Normalised DTW cost at or below which a match is accepted.
     *
     * Derived from the measured separation profile in `DtwSeparationTest`, which the build prints:
     * on 13-dimensional CMVN-normalised MFCC features, the same command re-recorded lands around
     * 0.59–0.68 and a different command lands above 2.3. 1.20 sits inside that gap, closer to the
     * "same command" side so a legitimate command is not rejected for being said differently.
     *
     * These are starting points measured against synthetic signals. `docs/CALIBRATION.md` sets out
     * how to retune them against real recorded Santali and Hindi commands, which is required
     * before a field pilot.
     */
    val acceptCost: Double = 1.20,
    /**
     * Minimum gap to the best *different* command.
     *
     * Without this, "one" and "no" — genuinely close in several Indian languages — would trade
     * places under noise. Requiring a clear winner turns an ambiguous utterance into a "say that
     * again" prompt instead of a wrong answer that gets scored.
     */
    val minMargin: Double = 0.15,
    /** Reject candidates whose duration is too different before running DTW. */
    val minLengthCompatibility: Double = 0.45,
    /** Utterances shorter than this many frames are noise, not speech. */
    val minFrames: Int = 8,
) {
    init {
        require(acceptCost > 0.0) { "acceptCost must be positive, got $acceptCost" }
        require(minMargin >= 0.0) { "minMargin must be >= 0, got $minMargin" }
        require(minLengthCompatibility in 0.0..1.0) {
            "minLengthCompatibility must be 0.0..1.0, got $minLengthCompatibility"
        }
        require(minFrames > 0) { "minFrames must be positive, got $minFrames" }
    }

    companion object {
        val DEFAULT: SpotterConfig = SpotterConfig()

        /**
         * Looser acceptance for a crusher house or a fan drift, with a wider required margin to
         * compensate: under noise it is better to demand a clearer winner than to accept a vague
         * one.
         */
        val NOISY_ENVIRONMENT: SpotterConfig = SpotterConfig(acceptCost = 1.60, minMargin = 0.25)
    }
}

enum class RejectReason {
    NO_TEMPLATES_ENROLLED,
    UTTERANCE_TOO_SHORT,
    NO_CANDIDATE_IN_RANGE,
    COST_ABOVE_THRESHOLD,
    AMBIGUOUS,
}

sealed interface SpotResult {
    class Match(
        val command: VoiceCommand,
        val cost: Double,
        val margin: Double,
        val templateId: String,
        /**
         * 0.0..1.0 for the UI indicator, expressed relative to the acceptance threshold rather
         * than to the raw cost. The raw DTW cost has no fixed upper bound, so `1 - cost` would go
         * negative for a perfectly acceptable match.
         */
        val confidence: Double,
    ) : SpotResult

    class Rejected(
        val reason: RejectReason,
        val bestCost: Double? = null,
        val bestCommand: VoiceCommand? = null,
    ) : SpotResult
}

/**
 * Matches an utterance against enrolled templates, entirely on-device.
 *
 * Recognition never throws and never guesses. An unrecognised utterance produces
 * [SpotResult.Rejected] with a reason the UI turns into "say that again" — and touch and gesture
 * input stay live throughout, so a worker is never stuck because the microphone did not
 * cooperate.
 */
class KeywordSpotter(
    templates: List<VoiceTemplate>,
    private val config: SpotterConfig = SpotterConfig.DEFAULT,
    private val extractor: MfccExtractor = MfccExtractor(),
) {

    private val templates: List<VoiceTemplate> = templates.toList()

    val templateCount: Int get() = templates.size

    val enrolledCommands: Set<VoiceCommand> get() = templates.map { it.command }.toSet()

    fun hasTemplatesFor(command: VoiceCommand): Boolean = templates.any { it.command == command }

    /** True when enough of the vocabulary is enrolled for voice input to be worth offering. */
    fun isUsable(): Boolean = enrolledCommands.size >= MIN_USABLE_COMMANDS

    fun recognise(pcm: ShortArray, allowed: Set<VoiceCommand>? = null): SpotResult =
        recognise(extractor.extract(pcm), allowed)

    fun recognise(utterance: MfccSequence, allowed: Set<VoiceCommand>? = null): SpotResult {
        if (templates.isEmpty()) {
            return SpotResult.Rejected(RejectReason.NO_TEMPLATES_ENROLLED)
        }
        if (utterance.isEmpty || utterance.frameCount < config.minFrames) {
            return SpotResult.Rejected(RejectReason.UTTERANCE_TOO_SHORT)
        }

        val candidates = if (allowed == null) {
            templates
        } else {
            val permitted = allowed + VoiceCommand.ALWAYS_AVAILABLE
            templates.filter { it.command in permitted }
        }
        if (candidates.isEmpty()) {
            return SpotResult.Rejected(RejectReason.NO_TEMPLATES_ENROLLED)
        }

        // Best cost per command, so a command with three enrolled repetitions is not unfairly
        // advantaged over one with a single recording. The winning template id is tracked in the
        // same pass rather than recomputed, since DTW is the expensive part.
        val bestPerCommand = HashMap<VoiceCommand, Double>()
        val bestTemplateIdPerCommand = HashMap<VoiceCommand, String>()

        for (template in candidates) {
            if (DtwMatcher.lengthCompatibility(utterance, template.sequence) <
                config.minLengthCompatibility
            ) {
                continue
            }
            val cost = DtwMatcher.distance(utterance, template.sequence)
            if (cost == DtwMatcher.NO_ALIGNMENT) continue

            val previous = bestPerCommand[template.command]
            if (previous == null || cost < previous) {
                bestPerCommand[template.command] = cost
                bestTemplateIdPerCommand[template.command] = template.templateId
            }
        }

        if (bestPerCommand.isEmpty()) {
            return SpotResult.Rejected(RejectReason.NO_CANDIDATE_IN_RANGE)
        }

        val ranked = bestPerCommand.entries.sortedBy { it.value }
        val winner = ranked.first()
        val runnerUpCost = ranked.getOrNull(1)?.value
        val bestTemplateId = bestTemplateIdPerCommand[winner.key]

        if (winner.value > config.acceptCost) {
            return SpotResult.Rejected(
                reason = RejectReason.COST_ABOVE_THRESHOLD,
                bestCost = winner.value,
                bestCommand = winner.key,
            )
        }

        val margin = if (runnerUpCost == null) Double.MAX_VALUE else runnerUpCost - winner.value
        if (margin < config.minMargin) {
            return SpotResult.Rejected(
                reason = RejectReason.AMBIGUOUS,
                bestCost = winner.value,
                bestCommand = winner.key,
            )
        }

        return SpotResult.Match(
            command = winner.key,
            cost = winner.value,
            margin = if (margin == Double.MAX_VALUE) config.acceptCost else margin,
            templateId = bestTemplateId ?: "unknown",
            confidence = (1.0 - winner.value / config.acceptCost).coerceIn(0.0, 1.0),
        )
    }

    companion object {
        /** Below this many enrolled commands, voice input is hidden rather than offered broken. */
        const val MIN_USABLE_COMMANDS: Int = 6
    }
}

// ---------------------------------------------------------------------------
// Enrollment
// ---------------------------------------------------------------------------

enum class EnrollmentVerdict {
    /** Repetitions are consistent; the template set is usable. */
    ACCEPTED,

    /** Repetitions disagree — likely a different word, or noise on one take. */
    INCONSISTENT,

    /** At least one recording produced too few frames. */
    TOO_SHORT,

    /** Fewer repetitions than required. */
    NOT_ENOUGH_REPETITIONS,
}

class EnrollmentAssessment(
    val verdict: EnrollmentVerdict,
    val worstPairwiseCost: Double?,
    val acceptedRepetitionCount: Int,
) {
    val isAcceptable: Boolean get() = verdict == EnrollmentVerdict.ACCEPTED

    override fun toString(): String =
        "EnrollmentAssessment($verdict, worstCost=$worstPairwiseCost, reps=$acceptedRepetitionCount)"
}

/**
 * Quality gate for supervisor enrollment.
 *
 * Checked at record time rather than discovered later, because a bad template is worse than no
 * template: it makes voice input silently unreliable for every worker at that site. Requiring
 * repetitions to agree with each other catches the common mistakes — a mistimed tap that cuts
 * the word off, a passing truck, saying the wrong word.
 */
object VoiceEnrollment {

    const val REQUIRED_REPETITIONS: Int = 2

    /**
     * Tighter than [SpotterConfig.acceptCost] on purpose: two takes recorded back to back by the
     * same person into the same microphone should agree far more closely than a template has to
     * agree with a different worker's speech months later. Sits just above the measured
     * same-speaker band (0.59–0.68) so honest variation passes and a fumbled take does not.
     * See `docs/CALIBRATION.md`.
     */
    const val MAX_PAIRWISE_COST: Double = 0.90
    const val MIN_FRAMES: Int = 8

    /**
     * @param maxPairwiseCost overridable so a site with a noisy enrollment environment can be
     *   loosened deliberately, rather than supervisors working around a fixed limit by
     *   re-recording until it happens to pass.
     */
    fun assess(
        repetitions: List<MfccSequence>,
        maxPairwiseCost: Double = MAX_PAIRWISE_COST,
    ): EnrollmentAssessment {
        require(maxPairwiseCost > 0.0) { "maxPairwiseCost must be positive, got $maxPairwiseCost" }
        val usable = repetitions.filter { !it.isEmpty && it.frameCount >= MIN_FRAMES }

        if (usable.size < repetitions.size && usable.size < REQUIRED_REPETITIONS) {
            return EnrollmentAssessment(EnrollmentVerdict.TOO_SHORT, null, usable.size)
        }
        if (usable.size < REQUIRED_REPETITIONS) {
            return EnrollmentAssessment(
                EnrollmentVerdict.NOT_ENOUGH_REPETITIONS,
                null,
                usable.size,
            )
        }

        var worst = 0.0
        for (i in usable.indices) {
            for (j in i + 1 until usable.size) {
                val cost = DtwMatcher.distance(usable[i], usable[j])
                if (cost == DtwMatcher.NO_ALIGNMENT) {
                    return EnrollmentAssessment(EnrollmentVerdict.INCONSISTENT, null, usable.size)
                }
                if (cost > worst) worst = cost
            }
        }

        return if (worst <= maxPairwiseCost) {
            EnrollmentAssessment(EnrollmentVerdict.ACCEPTED, worst, usable.size)
        } else {
            EnrollmentAssessment(EnrollmentVerdict.INCONSISTENT, worst, usable.size)
        }
    }
}

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

/**
 * Compact serialisation for storing templates in Room and gossiping them between phones.
 *
 * `float32` rather than text: about 5 KB per one-second template versus roughly 12 KB of JSON,
 * with no locale-dependent decimal formatting to get wrong. Enrolled vocabularies travel between
 * devices over Bluetooth, so size is not incidental.
 */
object MfccCodec {

    private const val MAGIC: String = "MF1"
    private const val MAX_FRAMES: Int = 2_000
    private const val MAX_COEFFICIENTS: Int = 64

    fun encode(sequence: MfccSequence): String {
        require(sequence.frameCount <= MAX_FRAMES) {
            "MFCC sequence has ${sequence.frameCount} frames, over the $MAX_FRAMES cap"
        }
        require(sequence.coefficientCount <= MAX_COEFFICIENTS) {
            "MFCC sequence has ${sequence.coefficientCount} coefficients, over the $MAX_COEFFICIENTS cap"
        }

        val header = MAGIC.toByteArray(Charsets.US_ASCII)
        val payloadSize = header.size + 1 + 2 + sequence.frameCount * sequence.coefficientCount * 4
        val buffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(header)
        buffer.put(sequence.coefficientCount.toByte())
        buffer.putShort(sequence.frameCount.toShort())
        for (frame in sequence.frames) {
            for (value in frame) {
                buffer.putFloat(value.toFloat())
            }
        }
        return Base64Url.encode(buffer.array())
    }

    /** @throws CanonicalFormatException if the blob is not a well-formed template. */
    fun decode(text: String): MfccSequence {
        val bytes = Base64Url.decode(text)
        val header = MAGIC.toByteArray(Charsets.US_ASCII)
        if (bytes.size < header.size + 3) {
            throw CanonicalFormatException("MFCC blob is truncated (${bytes.size} bytes)")
        }
        for (i in header.indices) {
            if (bytes[i] != header[i]) {
                throw CanonicalFormatException("MFCC blob has a bad magic marker")
            }
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(header.size)
        val coefficientCount = buffer.get().toInt() and 0xFF
        val frameCount = buffer.short.toInt() and 0xFFFF

        if (coefficientCount !in 1..MAX_COEFFICIENTS) {
            throw CanonicalFormatException("MFCC blob declares $coefficientCount coefficients")
        }
        if (frameCount > MAX_FRAMES) {
            throw CanonicalFormatException("MFCC blob declares $frameCount frames")
        }
        val expected = header.size + 3 + frameCount * coefficientCount * 4
        if (bytes.size != expected) {
            throw CanonicalFormatException(
                "MFCC blob is $expected bytes short or long (declared ${frameCount}x$coefficientCount, " +
                    "expected $expected, got ${bytes.size})",
            )
        }

        val frames = ArrayList<DoubleArray>(frameCount)
        repeat(frameCount) {
            val frame = DoubleArray(coefficientCount)
            for (d in 0 until coefficientCount) {
                frame[d] = buffer.float.toDouble()
            }
            frames += frame
        }
        return MfccSequence(frames, coefficientCount)
    }
}
