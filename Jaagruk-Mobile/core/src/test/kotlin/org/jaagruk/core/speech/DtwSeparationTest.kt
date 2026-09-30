package org.jaagruk.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Characterises the DTW distance function across increasingly different utterances.
 *
 * This is the test that justifies the threshold constants. Absolute thresholds for real speech
 * cannot honestly be derived from synthetic chirps — `docs/CALIBRATION.md` sets out how to tune
 * them against recorded Santali and Hindi commands. What *can* be established here, and what
 * actually matters for the algorithm to work at all, is that the distance increases
 * monotonically with how different two utterances are, with a wide separation between "the same
 * command said again" and "a different command".
 *
 * The measured values are printed so a reviewer can see the margins rather than take them on
 * trust.
 */
class DtwSeparationTest {

    private val extractor = MfccExtractor()

    @Test
    fun `distance increases monotonically with acoustic difference`() {
        val reference = extractor.extract(Audio.sweep(280.0, 820.0, 520))

        val identical = DtwMatcher.distance(
            reference,
            extractor.extract(Audio.sweep(280.0, 820.0, 520)),
        )
        val noiseOnly = DtwMatcher.distance(
            reference,
            extractor.extract(Audio.sweep(280.0, 820.0, 520, noiseAmplitude = 0.004, seed = 99L)),
        )
        val slightlySlower = DtwMatcher.distance(
            reference,
            extractor.extract(Audio.sweep(280.0, 820.0, 560, noiseAmplitude = 0.004, seed = 99L)),
        )
        val muchSlower = DtwMatcher.distance(
            reference,
            extractor.extract(Audio.sweep(280.0, 820.0, 760, noiseAmplitude = 0.004, seed = 99L)),
        )
        val differentCommand = DtwMatcher.distance(
            reference,
            extractor.extract(Audio.sweep(2_400.0, 1_400.0, 520)),
        )
        val noise = DtwMatcher.distance(reference, extractor.extract(Audio.whiteNoise(600)))

        println(
            """
            |[jaagruk] DTW separation profile (synthetic, 13-dim MFCC + CMVN)
            |  identical           = ${"%.4f".format(identical)}
            |  same + mic noise    = ${"%.4f".format(noiseOnly)}
            |  same, 8% slower     = ${"%.4f".format(slightlySlower)}
            |  same, 46% slower    = ${"%.4f".format(muchSlower)}
            |  different command   = ${"%.4f".format(differentCommand)}
            |  white noise         = ${"%.4f".format(noise)}
            |  acceptCost default  = ${SpotterConfig.DEFAULT.acceptCost}
            |  enrollment max cost = ${VoiceEnrollment.MAX_PAIRWISE_COST}
            """.trimMargin(),
        )

        assertThat(identical).isWithin(1e-9).of(0.0)
        assertThat(noiseOnly).isGreaterThan(identical)
        assertThat(slightlySlower).isGreaterThan(noiseOnly)
        assertThat(differentCommand).isGreaterThan(slightlySlower)
        assertThat(noise).isGreaterThan(slightlySlower)
    }

    @Test
    fun `the same command stays comfortably closer than a different one`() {
        // The separation ratio is what makes the margin check in KeywordSpotter meaningful.
        val reference = extractor.extract(Audio.sweep(280.0, 820.0, 520))
        val sameCommand = DtwMatcher.distance(
            reference,
            extractor.extract(Audio.sweep(285.0, 830.0, 545, noiseAmplitude = 0.004, seed = 7L)),
        )
        val otherCommand = DtwMatcher.distance(
            reference,
            extractor.extract(Audio.sweep(2_400.0, 1_400.0, 545)),
        )

        println(
            "[jaagruk] separation ratio = %.2fx (same=%.4f, other=%.4f)"
                .format(otherCommand / sameCommand, sameCommand, otherCommand),
        )
        assertThat(otherCommand).isGreaterThan(sameCommand * 1.5)
    }

    @Test
    fun `thresholds are ordered so enrollment is stricter than recognition`() {
        assertThat(VoiceEnrollment.MAX_PAIRWISE_COST)
            .isLessThan(SpotterConfig.DEFAULT.acceptCost)
        assertThat(SpotterConfig.DEFAULT.acceptCost)
            .isLessThan(SpotterConfig.NOISY_ENVIRONMENT.acceptCost)
    }
}
