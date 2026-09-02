package org.jaagruk.core.speech

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.util.CanonicalFormatException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic audio generators, so the voice pipeline is testable without any recorded assets. */
internal object Audio {

    const val SAMPLE_RATE: Int = 16_000

    /**
     * A linear frequency sweep with an amplitude envelope, bracketed by silence.
     *
     * A sweep rather than a steady tone: cepstral mean/variance normalisation needs genuine
     * spectral variation over time, and a constant tone has none. The leading and trailing
     * silence also exercises the endpoint trimming that real recordings depend on.
     */
    fun sweep(
        startHz: Double,
        endHz: Double,
        durationMs: Int,
        silenceMs: Int = 120,
        noiseAmplitude: Double = 0.0,
        seed: Long = 42L,
    ): ShortArray {
        val random = Random(seed)
        val silenceSamples = SAMPLE_RATE * silenceMs / 1_000
        val toneSamples = SAMPLE_RATE * durationMs / 1_000
        val total = silenceSamples * 2 + toneSamples
        val out = ShortArray(total)

        var phase = 0.0
        for (i in 0 until toneSamples) {
            val progress = i.toDouble() / toneSamples.toDouble()
            val frequency = startHz + (endHz - startHz) * progress
            phase += 2.0 * PI * frequency / SAMPLE_RATE
            // Hann-ish envelope so the onset and offset are not a click.
            val envelope = 0.5 * (1.0 - cos(2.0 * PI * progress))
            var value = sin(phase) * envelope * 0.6
            if (noiseAmplitude > 0.0) value += (random.nextDouble() - 0.5) * 2.0 * noiseAmplitude
            out[silenceSamples + i] = (value.coerceIn(-1.0, 1.0) * 32_000).toInt().toShort()
        }
        if (noiseAmplitude > 0.0) {
            for (i in 0 until silenceSamples) {
                val floor = (random.nextDouble() - 0.5) * 2.0 * noiseAmplitude * 0.2
                out[i] = (floor * 32_000).toInt().toShort()
                out[total - 1 - i] = (floor * 32_000).toInt().toShort()
            }
        }
        return out
    }

    fun whiteNoise(durationMs: Int, seed: Long = 7L): ShortArray {
        val random = Random(seed)
        val samples = SAMPLE_RATE * durationMs / 1_000
        return ShortArray(samples) { ((random.nextDouble() - 0.5) * 2.0 * 20_000).toInt().toShort() }
    }
}

class FftTest {

    @Test
    fun `power spectrum matches a naive dft`() {
        val random = Random(3L)
        val size = 64
        val signal = DoubleArray(size) { random.nextDouble() * 2.0 - 1.0 }

        val fast = Fft.powerSpectrum(signal, size)

        for (k in 0..size / 2) {
            var re = 0.0
            var im = 0.0
            for (n in 0 until size) {
                val angle = -2.0 * PI * k * n / size
                re += signal[n] * cos(angle)
                im += signal[n] * sin(angle)
            }
            assertThat(sqrt(fast[k])).isWithin(1e-6).of(sqrt(re * re + im * im))
        }
    }

    @Test
    fun `zero padding is applied for a short frame`() {
        val spectrum = Fft.powerSpectrum(DoubleArray(10) { 1.0 }, 64)
        assertThat(spectrum).hasLength(33)
        // DC bin equals the squared sum of a constant signal.
        assertThat(spectrum[0]).isWithin(1e-6).of(100.0)
    }

    @Test
    fun `a dc signal has all its energy in bin zero`() {
        val spectrum = Fft.powerSpectrum(DoubleArray(32) { 1.0 }, 32)
        assertThat(spectrum[0]).isWithin(1e-6).of(1_024.0)
        for (k in 1 until spectrum.size) {
            assertThat(spectrum[k]).isWithin(1e-6).of(0.0)
        }
    }

    @Test
    fun `power of two detection and rounding`() {
        assertThat(Fft.isPowerOfTwo(1)).isTrue()
        assertThat(Fft.isPowerOfTwo(512)).isTrue()
        assertThat(Fft.isPowerOfTwo(0)).isFalse()
        assertThat(Fft.isPowerOfTwo(-8)).isFalse()
        assertThat(Fft.isPowerOfTwo(100)).isFalse()
        assertThat(Fft.nextPowerOfTwo(1)).isEqualTo(1)
        assertThat(Fft.nextPowerOfTwo(400)).isEqualTo(512)
        assertThat(Fft.nextPowerOfTwo(512)).isEqualTo(512)
    }

    @Test
    fun `rejects a non power of two size or an oversized frame`() {
        assertThrows<IllegalArgumentException> { Fft.powerSpectrum(DoubleArray(10), 100) }
        assertThrows<IllegalArgumentException> { Fft.powerSpectrum(DoubleArray(600), 512) }
        assertThrows<IllegalArgumentException> { Fft.transform(DoubleArray(3), DoubleArray(3)) }
        assertThrows<IllegalArgumentException> { Fft.transform(DoubleArray(4), DoubleArray(8)) }
    }
}

class MfccConfigTest {

    @Test
    fun `derives frame geometry from milliseconds`() {
        val config = MfccConfig.DEFAULT
        assertThat(config.frameLengthSamples).isEqualTo(400)
        assertThat(config.frameShiftSamples).isEqualTo(160)
        assertThat(config.fftSize).isEqualTo(512)
    }

    @Test
    fun `rejects an impossible configuration`() {
        assertThrows<IllegalArgumentException> { MfccConfig(sampleRate = 0) }
        assertThrows<IllegalArgumentException> { MfccConfig(frameShiftMs = 40, frameLengthMs = 25) }
        assertThrows<IllegalArgumentException> { MfccConfig(coefficientCount = 40, filterCount = 26) }
        // Above Nyquist.
        assertThrows<IllegalArgumentException> {
            MfccConfig(sampleRate = 8_000, highFrequencyHz = 7_600.0)
        }
        assertThrows<IllegalArgumentException> {
            MfccConfig(lowFrequencyHz = 5_000.0, highFrequencyHz = 4_000.0)
        }
        assertThrows<IllegalArgumentException> { MfccConfig(preEmphasis = 1.5) }
    }

    @Test
    fun `mel conversion round trips`() {
        for (hz in listOf(0.0, 100.0, 700.0, 1_000.0, 4_000.0, 8_000.0)) {
            val mel = MfccExtractor.hzToMel(hz)
            assertThat(MfccExtractor.melToHz(mel)).isWithin(1e-6).of(hz)
        }
    }
}

class MfccExtractorTest {

    private val extractor = MfccExtractor()

    @Test
    fun `extracts frames of the configured width`() {
        val sequence = extractor.extract(Audio.sweep(300.0, 900.0, 500))

        assertThat(sequence.isEmpty).isFalse()
        assertThat(sequence.coefficientCount).isEqualTo(13)
        assertThat(sequence.frames.all { it.size == 13 }).isTrue()
        assertThat(sequence.frameCount).isAtLeast(30)
    }

    @Test
    fun `extraction is deterministic`() {
        val pcm = Audio.sweep(300.0, 900.0, 500)
        val first = extractor.extract(pcm)
        val second = extractor.extract(pcm)

        assertThat(first.frameCount).isEqualTo(second.frameCount)
        for (i in 0 until first.frameCount) {
            for (d in 0 until first.coefficientCount) {
                assertThat(first.frames[i][d]).isWithin(1e-12).of(second.frames[i][d])
            }
        }
    }

    @Test
    fun `produces no NaN or infinity even from silence`() {
        val silent = ShortArray(Audio.SAMPLE_RATE / 2)
        val sequence = extractor.extract(silent)
        // A floored log keeps a fully silent band from producing -Infinity and poisoning every
        // downstream distance computation.
        sequence.frames.forEach { frame ->
            frame.forEach { assertThat(it.isFinite()).isTrue() }
        }
    }

    @Test
    fun `returns empty for input too short to frame`() {
        assertThat(extractor.extract(ShortArray(0)).isEmpty).isTrue()
        assertThat(extractor.extract(ShortArray(100)).isEmpty).isTrue()
    }

    @Test
    fun `silence trimming shortens a padded recording`() {
        val padded = Audio.sweep(300.0, 900.0, 400, silenceMs = 800)
        val tight = Audio.sweep(300.0, 900.0, 400, silenceMs = 20)

        val trimmed = MfccExtractor(MfccConfig(trimSilence = true)).extract(padded)
        val untrimmed = MfccExtractor(MfccConfig(trimSilence = false)).extract(padded)

        assertThat(trimmed.frameCount).isLessThan(untrimmed.frameCount)
        // After trimming, a heavily padded recording aligns with a tightly cut one, which is what
        // lets a command spoken after a half-second pause still match its template.
        assertThat(trimmed.frameCount).isWithin(12).of(
            MfccExtractor(MfccConfig(trimSilence = true)).extract(tight).frameCount,
        )
    }

    @Test
    fun `duration reporting matches the frame shift`() {
        val sequence = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        assertThat(sequence.durationMs(MfccConfig.DEFAULT))
            .isEqualTo(sequence.frameCount * MfccConfig.DEFAULT.frameShiftMs)
        assertThat(MfccSequence.empty(13).durationMs(MfccConfig.DEFAULT)).isEqualTo(0)
    }

    @Test
    fun `peak amplitude is normalised`() {
        assertThat(MfccExtractor.peakAmplitude(ShortArray(0))).isEqualTo(0.0)
        assertThat(MfccExtractor.peakAmplitude(shortArrayOf(0, 16_384, -100)))
            .isWithin(1e-6).of(0.5)
    }

    @Test
    fun `sequence rejects ragged frames`() {
        assertThrows<IllegalArgumentException> {
            MfccSequence(listOf(DoubleArray(13), DoubleArray(12)), 13)
        }
    }
}

class DtwMatcherTest {

    private val extractor = MfccExtractor()

    @Test
    fun `identical sequences align at zero cost`() {
        val sequence = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        assertThat(DtwMatcher.distance(sequence, sequence)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a near identical utterance costs far less than an unrelated one`() {
        val template = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        val similar = extractor.extract(Audio.sweep(300.0, 900.0, 530, noiseAmplitude = 0.004))
        val different = extractor.extract(Audio.sweep(2_400.0, 1_500.0, 500))

        val similarCost = DtwMatcher.distance(template, similar)
        val differentCost = DtwMatcher.distance(template, different)

        // Separation first: it is the property the algorithm has to have. The absolute threshold
        // is a measured constant -- see DtwSeparationTest and docs/CALIBRATION.md.
        assertThat(similarCost).isLessThan(differentCost)
        assertThat(similarCost).isLessThan(SpotterConfig.DEFAULT.acceptCost)
    }

    @Test
    fun `a re-recording of the same utterance costs almost nothing`() {
        // Same duration, different microphone noise: the realistic case of one worker's template
        // being matched against the same worker speaking again.
        val template = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        val reRecorded = extractor.extract(
            Audio.sweep(300.0, 900.0, 500, noiseAmplitude = 0.004, seed = 555L),
        )
        assertThat(DtwMatcher.distance(template, reRecorded))
            .isLessThan(SpotterConfig.DEFAULT.acceptCost)
    }

    @Test
    fun `tolerates a change in speaking speed`() {
        // The reason DTW is used at all: the same word said faster must still match.
        val slow = extractor.extract(Audio.sweep(300.0, 900.0, 700))
        val fast = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        assertThat(DtwMatcher.distance(slow, fast))
            .isLessThan(DtwMatcher.distance(slow, extractor.extract(Audio.whiteNoise(600))))
    }

    @Test
    fun `an empty sequence yields no alignment`() {
        val sequence = extractor.extract(Audio.sweep(300.0, 900.0, 400))
        assertThat(DtwMatcher.distance(MfccSequence.empty(13), sequence))
            .isEqualTo(DtwMatcher.NO_ALIGNMENT)
        assertThat(DtwMatcher.distance(sequence, MfccSequence.empty(13)))
            .isEqualTo(DtwMatcher.NO_ALIGNMENT)
    }

    @Test
    fun `distance is symmetric`() {
        val a = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        val b = extractor.extract(Audio.sweep(400.0, 1_100.0, 520))
        assertThat(DtwMatcher.distance(a, b)).isWithin(1e-9).of(DtwMatcher.distance(b, a))
    }

    @Test
    fun `the band always permits reaching the far corner`() {
        // Even a very narrow band must not make alignment impossible for uneven lengths, or a
        // legitimate command would be silently unmatchable.
        val short = extractor.extract(Audio.sweep(300.0, 900.0, 250))
        val long = extractor.extract(Audio.sweep(300.0, 900.0, 1_200))
        assertThat(DtwMatcher.distance(short, long, bandRatio = 0.01))
            .isNotEqualTo(DtwMatcher.NO_ALIGNMENT)
    }

    @Test
    fun `rejects mismatched feature dimensions`() {
        val thirteen = MfccSequence(listOf(DoubleArray(13)), 13)
        val twenty = MfccSequence(listOf(DoubleArray(20)), 20)
        assertThrows<IllegalArgumentException> { DtwMatcher.distance(thirteen, twenty) }
    }

    @Test
    fun `rejects an invalid band ratio`() {
        val sequence = MfccSequence(listOf(DoubleArray(13)), 13)
        assertThrows<IllegalArgumentException> {
            DtwMatcher.distance(sequence, sequence, bandRatio = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            DtwMatcher.distance(sequence, sequence, bandRatio = 1.5)
        }
    }

    @Test
    fun `length compatibility is a ratio`() {
        val short = extractor.extract(Audio.sweep(300.0, 900.0, 300))
        val long = extractor.extract(Audio.sweep(300.0, 900.0, 900))

        assertThat(DtwMatcher.lengthCompatibility(short, short)).isWithin(1e-9).of(1.0)
        assertThat(DtwMatcher.lengthCompatibility(short, long)).isLessThan(0.6)
        assertThat(DtwMatcher.lengthCompatibility(MfccSequence.empty(13), short)).isEqualTo(0.0)
    }
}

class KeywordSpotterTest {

    private val extractor = MfccExtractor()

    private fun template(
        command: VoiceCommand,
        pcm: ShortArray,
        id: String = command.commandKey,
        languageTag: String = "sat",
    ): VoiceTemplate = VoiceTemplate(
        templateId = id,
        command = command,
        languageTag = languageTag,
        sequence = extractor.extract(pcm),
        enrolledAtEpochSec = 1_760_000_000L,
    )

    private val leftAudio = Audio.sweep(280.0, 820.0, 520)
    private val rightAudio = Audio.sweep(2_400.0, 1_400.0, 520)
    private val yesAudio = Audio.sweep(900.0, 1_900.0, 480)

    private fun spotter(vararg templates: VoiceTemplate) = KeywordSpotter(templates.toList())

    @Test
    fun `matches an exact repeat of an enrolled command`() {
        val spotter = spotter(
            template(VoiceCommand.LEFT, leftAudio),
            template(VoiceCommand.RIGHT, rightAudio),
        )
        val result = spotter.recognise(leftAudio)

        assertThat(result).isInstanceOf(SpotResult.Match::class.java)
        val match = result as SpotResult.Match
        assertThat(match.command).isEqualTo(VoiceCommand.LEFT)
        assertThat(match.cost).isWithin(1e-6).of(0.0)
        assertThat(match.confidence).isWithin(1e-6).of(1.0)
        assertThat(match.templateId).isEqualTo("left")
    }

    @Test
    fun `matches a re-recording of the same command`() {
        val spotter = spotter(
            template(VoiceCommand.LEFT, leftAudio),
            template(VoiceCommand.RIGHT, rightAudio),
        )
        val spoken = Audio.sweep(280.0, 820.0, 520, noiseAmplitude = 0.004, seed = 99L)
        val result = spotter.recognise(spoken)

        assertThat(result).isInstanceOf(SpotResult.Match::class.java)
        assertThat((result as SpotResult.Match).command).isEqualTo(VoiceCommand.LEFT)
    }

    @Test
    fun `picks the right command even when the delivery differs`() {
        // Same word, spoken 5 % slower and slightly higher. Thresholds are relaxed here on
        // purpose: this test is about the ranking and template-selection logic, not about the
        // absolute acceptance threshold, which only real recorded speech can calibrate.
        val spotter = KeywordSpotter(
            listOf(template(VoiceCommand.LEFT, leftAudio), template(VoiceCommand.RIGHT, rightAudio)),
            SpotterConfig(acceptCost = 1.5, minMargin = 0.02, minLengthCompatibility = 0.4),
        )
        val spoken = Audio.sweep(295.0, 860.0, 548, noiseAmplitude = 0.004, seed = 31L)
        val result = spotter.recognise(spoken)

        assertThat(result).isInstanceOf(SpotResult.Match::class.java)
        assertThat((result as SpotResult.Match).command).isEqualTo(VoiceCommand.LEFT)
    }

    @Test
    fun `rejects an unrelated utterance rather than guessing`() {
        val spotter = spotter(template(VoiceCommand.LEFT, leftAudio))
        val result = spotter.recognise(Audio.whiteNoise(600))

        assertThat(result).isInstanceOf(SpotResult.Rejected::class.java)
        assertThat((result as SpotResult.Rejected).reason).isAnyOf(
            RejectReason.COST_ABOVE_THRESHOLD,
            RejectReason.NO_CANDIDATE_IN_RANGE,
        )
    }

    @Test
    fun `rejects an ambiguous utterance instead of picking one`() {
        // "one" and "no" are genuinely close in several Indian languages. Requiring a clear
        // winner turns that into a "say it again" prompt rather than a wrong answer that scores.
        val spotter = spotter(
            template(VoiceCommand.ONE, leftAudio, id = "one"),
            template(VoiceCommand.NO, leftAudio, id = "no"),
        )
        val result = spotter.recognise(leftAudio)

        assertThat(result).isInstanceOf(SpotResult.Rejected::class.java)
        assertThat((result as SpotResult.Rejected).reason).isEqualTo(RejectReason.AMBIGUOUS)
    }

    @Test
    fun `rejects an utterance that is too short`() {
        val spotter = spotter(template(VoiceCommand.LEFT, leftAudio))
        val result = spotter.recognise(ShortArray(200))

        assertThat((result as SpotResult.Rejected).reason)
            .isEqualTo(RejectReason.UTTERANCE_TOO_SHORT)
    }

    @Test
    fun `reports when nothing is enrolled`() {
        val result = KeywordSpotter(emptyList()).recognise(leftAudio)
        assertThat((result as SpotResult.Rejected).reason)
            .isEqualTo(RejectReason.NO_TEMPLATES_ENROLLED)
    }

    @Test
    fun `restricts recognition to the commands a step accepts`() {
        // A step about fire should not be answerable by shouting "gas".
        val spotter = spotter(
            template(VoiceCommand.LEFT, leftAudio),
            template(VoiceCommand.GAS, rightAudio),
        )
        val result = spotter.recognise(rightAudio, allowed = setOf(VoiceCommand.LEFT))
        assertThat(result).isInstanceOf(SpotResult.Rejected::class.java)

        val allowed = spotter.recognise(rightAudio, allowed = setOf(VoiceCommand.GAS))
        assertThat((allowed as SpotResult.Match).command).isEqualTo(VoiceCommand.GAS)
    }

    @Test
    fun `always available commands survive filtering`() {
        val spotter = spotter(
            template(VoiceCommand.LEFT, leftAudio),
            template(VoiceCommand.HELP, yesAudio),
        )
        val result = spotter.recognise(yesAudio, allowed = setOf(VoiceCommand.LEFT))
        assertThat((result as SpotResult.Match).command).isEqualTo(VoiceCommand.HELP)
    }

    @Test
    fun `multiple repetitions of one command do not skew the ranking`() {
        val spotter = spotter(
            template(VoiceCommand.LEFT, leftAudio, id = "left-1"),
            template(VoiceCommand.LEFT, Audio.sweep(285.0, 830.0, 540), id = "left-2"),
            template(VoiceCommand.RIGHT, rightAudio),
        )
        assertThat((spotter.recognise(rightAudio) as SpotResult.Match).command)
            .isEqualTo(VoiceCommand.RIGHT)
    }

    @Test
    fun `reports enrolled coverage and usability`() {
        val sparse = spotter(template(VoiceCommand.LEFT, leftAudio))
        assertThat(sparse.templateCount).isEqualTo(1)
        assertThat(sparse.hasTemplatesFor(VoiceCommand.LEFT)).isTrue()
        assertThat(sparse.hasTemplatesFor(VoiceCommand.RIGHT)).isFalse()
        // Voice input is hidden rather than offered broken when coverage is too thin.
        assertThat(sparse.isUsable()).isFalse()

        val commands = listOf(
            VoiceCommand.ONE, VoiceCommand.TWO, VoiceCommand.THREE,
            VoiceCommand.FOUR, VoiceCommand.YES, VoiceCommand.NO,
        )
        val full = KeywordSpotter(
            commands.mapIndexed { index, command ->
                template(command, Audio.sweep(250.0 + index * 260, 700.0 + index * 260, 500), id = "t$index")
            },
        )
        assertThat(full.isUsable()).isTrue()
        assertThat(full.enrolledCommands).hasSize(6)
    }

    @Test
    fun `template rejects empty features or blank metadata`() {
        assertThrows<IllegalArgumentException> {
            VoiceTemplate("t", VoiceCommand.LEFT, "sat", MfccSequence.empty(13), 0L)
        }
        assertThrows<IllegalArgumentException> {
            VoiceTemplate(" ", VoiceCommand.LEFT, "sat", extractor.extract(leftAudio), 0L)
        }
        assertThrows<IllegalArgumentException> {
            VoiceTemplate("t", VoiceCommand.LEFT, " ", extractor.extract(leftAudio), 0L)
        }
    }

    @Test
    fun `spotter config rejects nonsense`() {
        assertThrows<IllegalArgumentException> { SpotterConfig(acceptCost = 0.0) }
        assertThrows<IllegalArgumentException> { SpotterConfig(minMargin = -0.1) }
        assertThrows<IllegalArgumentException> { SpotterConfig(minLengthCompatibility = 1.5) }
        assertThrows<IllegalArgumentException> { SpotterConfig(minFrames = 0) }
    }
}

class VoiceCommandTest {

    @Test
    fun `command keys are unique and lower case`() {
        val keys = VoiceCommand.entries.map { it.commandKey }
        assertThat(keys).containsNoDuplicates()
        assertThat(keys.all { it == it.lowercase() }).isTrue()
    }

    @Test
    fun `label keys are unique`() {
        assertThat(VoiceCommand.entries.map { it.labelKey }).containsNoDuplicates()
    }

    @Test
    fun `lookup by key is case insensitive`() {
        assertThat(VoiceCommand.fromKey("left")).isEqualTo(VoiceCommand.LEFT)
        assertThat(VoiceCommand.fromKey("LEFT")).isEqualTo(VoiceCommand.LEFT)
        assertThat(VoiceCommand.fromKey("nope")).isNull()
    }

    @Test
    fun `option selectors cover positions one to four in order`() {
        assertThat(VoiceCommand.OPTION_SELECTORS.map { it.optionIndex })
            .containsExactly(1, 2, 3, 4).inOrder()
        assertThat(VoiceCommand.OPTION_SELECTORS.all { it.kind == VoiceCommandKind.OPTION_INDEX })
            .isTrue()
    }

    @Test
    fun `only option selectors carry an index`() {
        VoiceCommand.entries.forEach { command ->
            if (command.kind == VoiceCommandKind.OPTION_INDEX) {
                assertThat(command.optionIndex).isNotNull()
            } else {
                assertThat(command.optionIndex).isNull()
            }
        }
    }
}

class VoiceEnrollmentTest {

    private val extractor = MfccExtractor()

    @Test
    fun `accepts consistent repetitions`() {
        val assessment = VoiceEnrollment.assess(
            listOf(
                extractor.extract(Audio.sweep(300.0, 900.0, 500)),
                extractor.extract(Audio.sweep(300.0, 900.0, 500, noiseAmplitude = 0.003, seed = 12L)),
            ),
        )
        assertThat(assessment.verdict).isEqualTo(EnrollmentVerdict.ACCEPTED)
        assertThat(assessment.isAcceptable).isTrue()
        assertThat(assessment.acceptedRepetitionCount).isEqualTo(2)
    }

    @Test
    fun `the consistency limit is overridable per site`() {
        val takes = listOf(
            extractor.extract(Audio.sweep(300.0, 900.0, 500)),
            extractor.extract(Audio.sweep(305.0, 910.0, 545, noiseAmplitude = 0.006, seed = 3L)),
        )
        // A site with a noisy enrollment room can be loosened deliberately and visibly, which is
        // preferable to a supervisor re-recording until a fixed limit happens to let them past.
        assertThat(VoiceEnrollment.assess(takes, maxPairwiseCost = 0.01).verdict)
            .isEqualTo(EnrollmentVerdict.INCONSISTENT)
        assertThat(VoiceEnrollment.assess(takes, maxPairwiseCost = 3.0).verdict)
            .isEqualTo(EnrollmentVerdict.ACCEPTED)
    }

    @Test
    fun `rejects a non positive cost limit`() {
        assertThrows<IllegalArgumentException> {
            VoiceEnrollment.assess(emptyList(), maxPairwiseCost = 0.0)
        }
    }

    @Test
    fun `rejects repetitions that disagree`() {
        // A bad template is worse than none: it makes voice input silently unreliable for every
        // worker at that site.
        val assessment = VoiceEnrollment.assess(
            listOf(
                extractor.extract(Audio.sweep(300.0, 900.0, 500)),
                extractor.extract(Audio.sweep(2_600.0, 1_300.0, 500)),
            ),
        )
        assertThat(assessment.verdict).isEqualTo(EnrollmentVerdict.INCONSISTENT)
        assertThat(assessment.isAcceptable).isFalse()
    }

    @Test
    fun `rejects too few repetitions`() {
        val assessment = VoiceEnrollment.assess(
            listOf(extractor.extract(Audio.sweep(300.0, 900.0, 500))),
        )
        assertThat(assessment.verdict).isEqualTo(EnrollmentVerdict.NOT_ENOUGH_REPETITIONS)
    }

    @Test
    fun `rejects recordings that are too short`() {
        val assessment = VoiceEnrollment.assess(
            listOf(MfccSequence.empty(13), MfccSequence.empty(13)),
        )
        assertThat(assessment.verdict).isEqualTo(EnrollmentVerdict.TOO_SHORT)
    }

    @Test
    fun `handles an empty repetition list`() {
        assertThat(VoiceEnrollment.assess(emptyList()).verdict)
            .isEqualTo(EnrollmentVerdict.NOT_ENOUGH_REPETITIONS)
    }
}

class MfccCodecTest {

    private val extractor = MfccExtractor()

    @Test
    fun `round trips a template`() {
        val original = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        val decoded = MfccCodec.decode(MfccCodec.encode(original))

        assertThat(decoded.frameCount).isEqualTo(original.frameCount)
        assertThat(decoded.coefficientCount).isEqualTo(original.coefficientCount)
        for (i in 0 until original.frameCount) {
            for (d in 0 until original.coefficientCount) {
                // float32 storage: about seven significant digits, plenty for DTW.
                assertThat(decoded.frames[i][d]).isWithin(1e-4).of(original.frames[i][d])
            }
        }
    }

    @Test
    fun `a decoded template still matches its source`() {
        val original = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        val decoded = MfccCodec.decode(MfccCodec.encode(original))
        assertThat(DtwMatcher.distance(original, decoded)).isLessThan(0.01)
    }

    @Test
    fun `encoding is stable`() {
        val sequence = extractor.extract(Audio.sweep(300.0, 900.0, 500))
        assertThat(MfccCodec.encode(sequence)).isEqualTo(MfccCodec.encode(sequence))
    }

    @Test
    fun `round trips an empty sequence`() {
        val decoded = MfccCodec.decode(MfccCodec.encode(MfccSequence.empty(13)))
        assertThat(decoded.isEmpty).isTrue()
        assertThat(decoded.coefficientCount).isEqualTo(13)
    }

    @Test
    fun `rejects a malformed blob`() {
        assertThrows<CanonicalFormatException> { MfccCodec.decode("not-base64!!") }
        assertThrows<CanonicalFormatException> { MfccCodec.decode("AAAA") }

        val valid = MfccCodec.encode(extractor.extract(Audio.sweep(300.0, 900.0, 400)))
        assertThrows<CanonicalFormatException> { MfccCodec.decode(valid.substring(0, 20)) }
    }

    @Test
    fun `is materially smaller than a text encoding would be`() {
        val sequence = extractor.extract(Audio.sweep(300.0, 900.0, 1_000))
        val encoded = MfccCodec.encode(sequence)
        // 4 bytes per coefficient plus base64 overhead: roughly 5.4 bytes of text per value.
        assertThat(encoded.length).isLessThan(sequence.frameCount * sequence.coefficientCount * 6)
    }
}
