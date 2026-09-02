package org.jaagruk.safety.ar

import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource

/**
 * Turns tracking failures into something a worker can act on, and decides when to stop asking.
 *
 * Two problems this solves, both learned from how AR apps fail in practice rather than in a demo room:
 *
 *  1. **A raw tracking-failure reason is useless to a worker.** "INSUFFICIENT_FEATURES" means nothing
 *     underground. "Point at the wall, not the floor" does.
 *  2. **Prompt thrash is worse than no prompt.** ARCore flips between LIMITED and TRACKING several
 *     times a second in a dusty haulage road. Rendering each transition produces a strobing overlay
 *     that a worker will learn to ignore, so hints are held for a minimum dwell and only escalate.
 *
 * Crucially, the coach also decides when the drill must **pause**. A worker cannot be scored on
 * decision latency while the scene is frozen, and quietly letting the clock run during a tracking loss
 * would corrupt the one measurement this platform exists to make.
 */
class TrackingCoach(
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
) {

    /** What the UI should do right now. */
    data class Advice(
        val hint: TrackingHint,
        /** True when the assessment session should be paused. */
        val shouldPauseDrill: Boolean,
        /** True when the coach recommends offering the torch. */
        val suggestTorch: Boolean,
        /** True once the coach has given up on AR and the drill should fall back to 2D. */
        val recommendFallback: Boolean,
    )

    private var currentHint: TrackingHint = TrackingHint.NONE
    private var hintSetAtMs: Long = 0L
    private var limitedSinceMs: Long? = null
    private var lostSinceMs: Long? = null
    private var lastGoodTrackingMs: Long = 0L
    private var consecutiveLossEpisodes: Int = 0
    private var wasLost: Boolean = false

    fun onFrame(quality: ArTrackingQuality, rawHint: TrackingHint): Advice {
        val now = monotonic.elapsedMillis()

        when (quality) {
            ArTrackingQuality.TRACKING -> {
                lastGoodTrackingMs = now
                limitedSinceMs = null
                lostSinceMs = null
                wasLost = false
                setHint(TrackingHint.NONE, now)
            }

            ArTrackingQuality.LIMITED -> {
                if (limitedSinceMs == null) limitedSinceMs = now
                lostSinceMs = null
                // A momentary LIMITED while a worker turns their head is normal and not worth a prompt.
                if (now - (limitedSinceMs ?: now) >= LIMITED_GRACE_MS) {
                    setHint(rawHint.orDefault(TrackingHint.LOOK_AROUND_MORE), now)
                }
            }

            ArTrackingQuality.LOST -> {
                if (lostSinceMs == null) {
                    lostSinceMs = now
                    if (!wasLost) {
                        consecutiveLossEpisodes++
                        wasLost = true
                    }
                }
                setHint(TrackingHint.RECOVERING, now)
            }

            ArTrackingQuality.INITIALISING -> setHint(TrackingHint.LOOK_AROUND_MORE, now)

            ArTrackingQuality.UNAVAILABLE -> setHint(TrackingHint.CAMERA_BLOCKED, now)
        }

        val lostFor = lostSinceMs?.let { now - it } ?: 0L
        val limitedFor = limitedSinceMs?.let { now - it } ?: 0L

        return Advice(
            hint = currentHint,
            // Paused only once the loss is sustained. Pausing on every flicker would make the drill
            // stutter; not pausing at all would score a worker on a frozen scene.
            shouldPauseDrill = quality == ArTrackingQuality.UNAVAILABLE ||
                lostFor >= PAUSE_AFTER_LOST_MS,
            suggestTorch = currentHint == TrackingHint.TOO_DARK && limitedFor >= TORCH_AFTER_MS,
            // Repeated episodes, not one long one: a single dark stretch is recoverable, but a scene
            // that keeps collapsing is not going to hold together for a nine-step drill, and finishing
            // in 2D beats abandoning a worker halfway.
            recommendFallback = consecutiveLossEpisodes >= FALLBACK_AFTER_EPISODES ||
                lostFor >= FALLBACK_AFTER_LOST_MS,
        )
    }

    /** Milliseconds since tracking was last good. Used to decide whether a hit test is trustworthy. */
    fun millisSinceGoodTracking(): Long {
        if (lastGoodTrackingMs == 0L) return Long.MAX_VALUE
        return (monotonic.elapsedMillis() - lastGoodTrackingMs).coerceAtLeast(0L)
    }

    /** True when a tap should be accepted. A hit against a stale pose is not an answer. */
    fun acceptsInput(): Boolean = millisSinceGoodTracking() <= STALE_POSE_MS

    fun reset() {
        currentHint = TrackingHint.NONE
        hintSetAtMs = 0L
        limitedSinceMs = null
        lostSinceMs = null
        lastGoodTrackingMs = 0L
        consecutiveLossEpisodes = 0
        wasLost = false
    }

    private fun setHint(hint: TrackingHint, nowMs: Long) {
        if (hint == currentHint) return
        // Clearing a hint is immediate; replacing one waits out the dwell. That asymmetry is what stops
        // the overlay strobing between two competing prompts while still letting it disappear the
        // instant tracking recovers.
        if (hint != TrackingHint.NONE && nowMs - hintSetAtMs < MIN_HINT_DWELL_MS) return
        currentHint = hint
        hintSetAtMs = nowMs
    }

    private fun TrackingHint.orDefault(fallback: TrackingHint): TrackingHint =
        if (this == TrackingHint.NONE) fallback else this

    private companion object {
        /** How long LIMITED must persist before the worker is told anything. */
        const val LIMITED_GRACE_MS = 700L

        /** Minimum time a hint stays on screen before a different one may replace it. */
        const val MIN_HINT_DWELL_MS = 1_500L

        const val TORCH_AFTER_MS = 2_000L

        /** Sustained loss that pauses the latency clock. */
        const val PAUSE_AFTER_LOST_MS = 1_200L

        /** A single loss this long means the scene is not coming back. */
        const val FALLBACK_AFTER_LOST_MS = 15_000L

        /** Repeated collapses that mean AR is not viable in this location today. */
        const val FALLBACK_AFTER_EPISODES = 4

        /** A pose older than this is not used for hit testing. */
        const val STALE_POSE_MS = 400L
    }
}
