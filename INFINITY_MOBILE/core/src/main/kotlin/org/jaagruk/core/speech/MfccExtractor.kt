package org.jaagruk.core.speech

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Feature-extraction parameters. Defaults are standard 16 kHz telephony-style MFCC settings. */
class MfccConfig(
    val sampleRate: Int = 16_000,
    val frameLengthMs: Int = 25,
    val frameShiftMs: Int = 10,
    val filterCount: Int = 26,
    val coefficientCount: Int = 13,
    val lowFrequencyHz: Double = 80.0,
    val highFrequencyHz: Double = 7_600.0,
    val preEmphasis: Double = 0.97,
    /**
     * Per-utterance cepstral mean and variance normalisation.
     *
     * Not optional in practice. Templates get enrolled on a supervisor's phone and matched on a
     * worker's, and the two have different microphones, different gain and different casing
     * resonance. CMVN removes most of that channel difference, and without it cross-device
     * matching degrades badly.
     */
    val applyCmvn: Boolean = true,
    /** Trim leading and trailing silence before extraction. */
    val trimSilence: Boolean = true,
    /** Energy below this fraction of the frame-energy peak counts as silence. */
    val silenceEnergyRatio: Double = 0.02,
) {
    val frameLengthSamples: Int = sampleRate * frameLengthMs / 1_000
    val frameShiftSamples: Int = sampleRate * frameShiftMs / 1_000
    val fftSize: Int = Fft.nextPowerOfTwo(frameLengthSamples)

    init {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
        require(frameLengthMs > 0) { "frameLengthMs must be positive, got $frameLengthMs" }
        require(frameShiftMs > 0) { "frameShiftMs must be positive, got $frameShiftMs" }
        require(frameShiftMs <= frameLengthMs) {
            "frameShiftMs ($frameShiftMs) must not exceed frameLengthMs ($frameLengthMs)"
        }
        require(filterCount >= 2) { "filterCount must be >= 2, got $filterCount" }
        require(coefficientCount in 1..filterCount) {
            "coefficientCount must be 1..$filterCount, got $coefficientCount"
        }
        require(lowFrequencyHz >= 0.0) { "lowFrequencyHz must be >= 0, got $lowFrequencyHz" }
        require(highFrequencyHz > lowFrequencyHz) {
            "highFrequencyHz ($highFrequencyHz) must exceed lowFrequencyHz ($lowFrequencyHz)"
        }
        require(highFrequencyHz <= sampleRate / 2.0) {
            "highFrequencyHz ($highFrequencyHz) exceeds the Nyquist limit (${sampleRate / 2.0})"
        }
        require(preEmphasis in 0.0..1.0) { "preEmphasis must be 0.0..1.0, got $preEmphasis" }
        require(silenceEnergyRatio in 0.0..1.0) {
            "silenceEnergyRatio must be 0.0..1.0, got $silenceEnergyRatio"
        }
        require(frameLengthSamples >= 2) {
            "frameLengthMs $frameLengthMs at ${sampleRate}Hz gives only $frameLengthSamples samples"
        }
    }

    companion object {
        /** Matches Android `AudioRecord` 16 kHz mono PCM 16-bit, which is what the app records. */
        val DEFAULT: MfccConfig = MfccConfig()
    }
}

/**
 * A sequence of MFCC frames — the compact acoustic fingerprint of one spoken command.
 *
 * Roughly 100 frames of 13 floats for a one-second utterance: about 5 KB, small enough to keep
 * a full command vocabulary on-device and to gossip between phones over Bluetooth.
 */
class MfccSequence(val frames: List<DoubleArray>, val coefficientCount: Int) {

    init {
        require(coefficientCount > 0) { "coefficientCount must be positive" }
        require(frames.all { it.size == coefficientCount }) {
            "every frame must have $coefficientCount coefficients"
        }
    }

    val frameCount: Int get() = frames.size

    val isEmpty: Boolean get() = frames.isEmpty()

    /** Approximate duration, useful for rejecting an utterance that is obviously too short. */
    fun durationMs(config: MfccConfig): Int =
        if (frames.isEmpty()) 0 else frames.size * config.frameShiftMs

    override fun toString(): String = "MfccSequence(frames=$frameCount, dims=$coefficientCount)"

    companion object {
        fun empty(coefficientCount: Int): MfccSequence =
            MfccSequence(emptyList(), coefficientCount)
    }
}

/**
 * Turns raw PCM into MFCC frames.
 *
 * This is the enabling piece for Santali voice input. There is no usable Santali acoustic model
 * or speech corpus, so general-purpose ASR is not an option and pretending otherwise would be
 * dishonest. What *is* achievable, and sufficient for this interface, is fixed-vocabulary
 * keyword spotting: a supervisor enrols about fourteen commands once per site, and matching runs
 * offline against those templates. It is a narrower claim than "Santali speech recognition", and
 * it is one that actually works on a mid-range phone with no network.
 */
class MfccExtractor(private val config: MfccConfig = MfccConfig.DEFAULT) {

    private val melFilters: Array<DoubleArray> = buildMelFilterBank()
    private val window: DoubleArray = buildHammingWindow(config.frameLengthSamples)
    private val dctBasis: Array<DoubleArray> = buildDctBasis()

    /** 16-bit PCM entry point. Samples are scaled to -1.0..1.0. */
    fun extract(pcm: ShortArray): MfccSequence {
        if (pcm.isEmpty()) return MfccSequence.empty(config.coefficientCount)
        val signal = DoubleArray(pcm.size) { pcm[it].toDouble() / 32_768.0 }
        return extract(signal)
    }

    fun extract(signal: DoubleArray): MfccSequence {
        if (signal.isEmpty()) return MfccSequence.empty(config.coefficientCount)

        val trimmed = if (config.trimSilence) trimSilence(signal) else signal
        if (trimmed.size < config.frameLengthSamples) {
            // Too short to make even one analysis frame: an accidental tap on the mic button.
            return MfccSequence.empty(config.coefficientCount)
        }

        val emphasised = preEmphasise(trimmed)
        val frames = mutableListOf<DoubleArray>()

        var offset = 0
        while (offset + config.frameLengthSamples <= emphasised.size) {
            val frame = DoubleArray(config.frameLengthSamples)
            for (i in frame.indices) {
                frame[i] = emphasised[offset + i] * window[i]
            }
            frames += cepstraFromFrame(frame)
            offset += config.frameShiftSamples
        }

        if (frames.isEmpty()) return MfccSequence.empty(config.coefficientCount)
        val normalised = if (config.applyCmvn) applyCmvn(frames) else frames
        return MfccSequence(normalised, config.coefficientCount)
    }

    // -----------------------------------------------------------------------
    // Pipeline stages
    // -----------------------------------------------------------------------

    private fun preEmphasise(signal: DoubleArray): DoubleArray {
        if (config.preEmphasis == 0.0) return signal
        val out = DoubleArray(signal.size)
        out[0] = signal[0]
        for (i in 1 until signal.size) {
            out[i] = signal[i] - config.preEmphasis * signal[i - 1]
        }
        return out
    }

    private fun cepstraFromFrame(frame: DoubleArray): DoubleArray {
        val power = Fft.powerSpectrum(frame, config.fftSize)

        val logMel = DoubleArray(config.filterCount)
        for (m in 0 until config.filterCount) {
            var acc = 0.0
            val filter = melFilters[m]
            for (bin in filter.indices) {
                if (filter[bin] != 0.0) acc += filter[bin] * power[bin]
            }
            // Floor before the log so a fully silent band cannot produce -Infinity and poison
            // every downstream distance computation.
            logMel[m] = ln(acc.coerceAtLeast(1e-10))
        }

        val cepstra = DoubleArray(config.coefficientCount)
        for (k in 0 until config.coefficientCount) {
            var acc = 0.0
            val basis = dctBasis[k]
            for (m in 0 until config.filterCount) {
                acc += logMel[m] * basis[m]
            }
            cepstra[k] = acc
        }
        return cepstra
    }

    /**
     * Zero-mean, unit-variance per coefficient across the utterance.
     *
     * This is what makes a template enrolled on one phone match speech captured on another.
     */
    private fun applyCmvn(frames: List<DoubleArray>): List<DoubleArray> {
        val dims = config.coefficientCount
        val means = DoubleArray(dims)
        for (frame in frames) {
            for (d in 0 until dims) means[d] += frame[d]
        }
        for (d in 0 until dims) means[d] /= frames.size

        val stdDevs = DoubleArray(dims)
        for (frame in frames) {
            for (d in 0 until dims) {
                val diff = frame[d] - means[d]
                stdDevs[d] += diff * diff
            }
        }
        for (d in 0 until dims) {
            stdDevs[d] = sqrt(stdDevs[d] / frames.size).coerceAtLeast(1e-8)
        }

        return frames.map { frame ->
            DoubleArray(dims) { d -> (frame[d] - means[d]) / stdDevs[d] }
        }
    }

    /**
     * Drops leading and trailing silence.
     *
     * Without this, DTW spends most of its budget aligning the pause before the worker speaks,
     * and a command spoken after a half-second hesitation stops matching a template recorded
     * immediately. Relative to the utterance's own peak energy rather than an absolute
     * threshold, so it holds across microphones and background noise levels.
     */
    private fun trimSilence(signal: DoubleArray): DoubleArray {
        val frameSize = config.frameLengthSamples
        val shift = config.frameShiftSamples
        if (signal.size < frameSize) return signal

        val energies = mutableListOf<Pair<Int, Double>>()
        var offset = 0
        while (offset + frameSize <= signal.size) {
            var energy = 0.0
            for (i in 0 until frameSize) {
                val s = signal[offset + i]
                energy += s * s
            }
            energies += offset to (energy / frameSize)
            offset += shift
        }
        if (energies.isEmpty()) return signal

        val peak = energies.maxOf { it.second }
        if (peak <= 0.0) return signal
        val threshold = peak * config.silenceEnergyRatio

        val firstVoiced = energies.firstOrNull { it.second >= threshold }?.first
        val lastVoiced = energies.lastOrNull { it.second >= threshold }?.first

        if (firstVoiced == null || lastVoiced == null) return signal

        // Keep one frame of context on each side; abrupt cuts create spectral artefacts.
        val start = (firstVoiced - shift).coerceAtLeast(0)
        val end = (lastVoiced + frameSize + shift).coerceAtMost(signal.size)
        if (end - start < frameSize) return signal
        return signal.copyOfRange(start, end)
    }

    // -----------------------------------------------------------------------
    // Precomputed tables
    // -----------------------------------------------------------------------

    private fun buildHammingWindow(length: Int): DoubleArray =
        DoubleArray(length) { 0.54 - 0.46 * cos(2.0 * Math.PI * it / (length - 1).toDouble()) }

    private fun buildDctBasis(): Array<DoubleArray> = Array(config.coefficientCount) { k ->
        DoubleArray(config.filterCount) { m ->
            cos(Math.PI * k * (m + 0.5) / config.filterCount.toDouble())
        }
    }

    private fun buildMelFilterBank(): Array<DoubleArray> {
        val bins = config.fftSize / 2 + 1
        val lowMel = hzToMel(config.lowFrequencyHz)
        val highMel = hzToMel(config.highFrequencyHz)

        val points = DoubleArray(config.filterCount + 2) { i ->
            melToHz(lowMel + (highMel - lowMel) * i / (config.filterCount + 1).toDouble())
        }
        val binIndices = IntArray(points.size) { i ->
            ((points[i] / config.sampleRate) * config.fftSize).toInt().coerceIn(0, bins - 1)
        }

        return Array(config.filterCount) { m ->
            val filter = DoubleArray(bins)
            val left = binIndices[m]
            val centre = binIndices[m + 1]
            val right = binIndices[m + 2]

            // A degenerate (zero-width) filter is possible when filterCount is high relative to
            // the FFT resolution. Place a single unit tap rather than dividing by zero.
            if (centre <= left || right <= centre) {
                filter[centre] = 1.0
                return@Array filter
            }
            for (bin in left until centre) {
                filter[bin] = (bin - left).toDouble() / (centre - left).toDouble()
            }
            for (bin in centre until right) {
                filter[bin] = (right - bin).toDouble() / (right - centre).toDouble()
            }
            filter[centre] = 1.0
            filter
        }
    }

    companion object {
        fun hzToMel(hz: Double): Double = 2_595.0 * log10(1.0 + hz / 700.0)

        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2_595.0) - 1.0)

        /** Peak absolute amplitude, 0.0..1.0. Drives the recording level meter. */
        fun peakAmplitude(pcm: ShortArray): Double {
            if (pcm.isEmpty()) return 0.0
            var peak = 0
            for (s in pcm) {
                val a = abs(s.toInt())
                if (a > peak) peak = a
            }
            return peak / 32_768.0
        }
    }
}
