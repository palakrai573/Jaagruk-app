package org.jaagruk.core.speech

/**
 * Minimal in-place radix-2 Cooley–Tukey FFT.
 *
 * Hand-rolled rather than pulled from a DSP library on purpose: this runs inside `:core`,
 * which must stay a plain Kotlin/JVM module with no Android or native dependencies so the
 * whole voice path is unit-testable on a laptop. It is also about 60 lines, which is less risk
 * than a transitive native library that may not have an arm64 build.
 */
internal object Fft {

    /** @return true if [n] is a positive power of two. */
    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    fun nextPowerOfTwo(n: Int): Int {
        require(n > 0) { "n must be positive, got $n" }
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /**
     * Power spectrum of a real signal.
     *
     * @param frame time-domain samples; shorter than [fftSize] is zero-padded, longer is an error
     * @return `fftSize / 2 + 1` non-negative magnitudes squared
     */
    fun powerSpectrum(frame: DoubleArray, fftSize: Int): DoubleArray {
        require(isPowerOfTwo(fftSize)) { "fftSize must be a power of two, got $fftSize" }
        require(frame.size <= fftSize) {
            "frame of ${frame.size} samples does not fit in an FFT of size $fftSize"
        }

        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        frame.copyInto(re, 0, 0, frame.size)

        transform(re, im)

        val bins = fftSize / 2 + 1
        val power = DoubleArray(bins)
        for (i in 0 until bins) {
            power[i] = re[i] * re[i] + im[i] * im[i]
        }
        return power
    }

    /** In-place complex FFT. [re] and [im] must be the same power-of-two length. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "re/im length mismatch: ${re.size} vs ${im.size}" }
        require(isPowerOfTwo(n)) { "FFT length must be a power of two, got $n" }
        if (n == 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // Butterflies.
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wRe = Math.cos(angle)
            val wIm = Math.sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
