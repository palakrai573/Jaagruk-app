package org.jaagruk.core.speech

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Dynamic time warping between two MFCC sequences.
 *
 * DTW rather than a neural model because it needs no training data. A supervisor records a
 * command two or three times and matching works immediately — which is the only workable answer
 * for Santali, where no acoustic corpus exists to train against. It also tolerates the thing
 * that breaks naive template matching: the same word spoken at different speeds.
 *
 * Memory is two rows rather than a full matrix, so a 300 × 300 alignment costs a few kilobytes
 * and runs in single-digit milliseconds on a mid-range phone.
 */
object DtwMatcher {

    /** Sakoe–Chiba band width as a fraction of the longer sequence. */
    const val DEFAULT_BAND_RATIO: Double = 0.30

    /** Returned when the band makes alignment impossible. */
    const val NO_ALIGNMENT: Double = Double.MAX_VALUE

    /**
     * Length-normalised alignment cost. Lower is more similar; 0.0 is identical.
     *
     * @param bandRatio Sakoe–Chiba constraint. Bounds how far the alignment may stray from the
     *   diagonal, which both speeds matching up and stops a short utterance from being warped
     *   into a spurious match with a long one.
     */
    fun distance(
        a: MfccSequence,
        b: MfccSequence,
        bandRatio: Double = DEFAULT_BAND_RATIO,
    ): Double {
        require(bandRatio > 0.0 && bandRatio <= 1.0) {
            "bandRatio must be in (0.0, 1.0], got $bandRatio"
        }
        if (a.isEmpty || b.isEmpty) return NO_ALIGNMENT
        require(a.coefficientCount == b.coefficientCount) {
            "cannot align ${a.coefficientCount}-dim against ${b.coefficientCount}-dim features"
        }

        val n = a.frameCount
        val m = b.frameCount

        // A band narrower than the length difference can never reach the far corner.
        val band = max((max(n, m) * bandRatio).toInt(), abs(n - m) + 1)

        var previous = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var current = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        previous[0] = 0.0

        for (i in 1..n) {
            current.fill(Double.POSITIVE_INFINITY)
            val from = max(1, i - band)
            val to = minOf(m, i + band)
            for (j in from..to) {
                val cost = frameDistance(a.frames[i - 1], b.frames[j - 1])
                val best = minOf(previous[j], previous[j - 1], current[j - 1])
                current[j] = if (best.isFinite()) cost + best else Double.POSITIVE_INFINITY
            }
            val swap = previous
            previous = current
            current = swap
        }

        val total = previous[m]
        if (!total.isFinite()) return NO_ALIGNMENT
        // Normalising by n + m keeps the cost comparable across utterance lengths, which matters
        // because the score is thresholded against a fixed acceptance value.
        return total / (n + m).toDouble()
    }

    /** Euclidean distance between two feature frames. */
    private fun frameDistance(x: DoubleArray, y: DoubleArray): Double {
        var acc = 0.0
        for (d in x.indices) {
            val diff = x[d] - y[d]
            acc += diff * diff
        }
        return sqrt(acc)
    }

    /**
     * Ratio of the two sequence lengths, 0.0..1.0 (1.0 = identical length).
     *
     * Used as a cheap pre-filter: rejecting a candidate whose duration is wildly different
     * before running DTW at all avoids both the work and a class of false positives where a
     * long utterance warps onto a short template.
     */
    fun lengthCompatibility(a: MfccSequence, b: MfccSequence): Double {
        if (a.isEmpty || b.isEmpty) return 0.0
        val shorter = minOf(a.frameCount, b.frameCount).toDouble()
        val longer = maxOf(a.frameCount, b.frameCount).toDouble()
        return shorter / longer
    }
}
