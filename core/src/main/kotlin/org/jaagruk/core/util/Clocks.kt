package org.jaagruk.core.util

/**
 * Elapsed-time source for anything that is measured rather than dated.
 *
 * Decision latency is the single most important measurement in this platform, and it is
 * always taken from a monotonic source. Wall clocks on shared site phones get corrected
 * by NTP, changed by hand, and jump across timezone edits; a wall-clock delta can go
 * negative and would silently corrupt a worker's hesitation score.
 */
fun interface MonotonicTimeSource {
    fun elapsedMillis(): Long
}

/** Wall-clock source for anything that is dated (issuance, expiry, decay). */
fun interface WallClock {
    fun epochMillis(): Long

    fun epochSeconds(): Long = epochMillis() / 1_000L
}

object SystemMonotonicTimeSource : MonotonicTimeSource {
    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L
}

object SystemWallClock : WallClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
}

/** Deterministic monotonic source for tests and for replaying a recorded drill. */
class FixedMonotonicTimeSource(private var value: Long = 0L) : MonotonicTimeSource {
    override fun elapsedMillis(): Long = value

    fun advance(millis: Long) {
        require(millis >= 0) { "monotonic time cannot move backwards (asked for $millis)" }
        value += millis
    }

    fun set(millis: Long) {
        require(millis >= value) { "monotonic time cannot move backwards ($value -> $millis)" }
        value = millis
    }
}

/** Deterministic wall clock for tests. Unlike the monotonic source it *may* move backwards. */
class FixedWallClock(var millis: Long = 0L) : WallClock {
    override fun epochMillis(): Long = millis

    fun advanceDays(days: Double) {
        millis += (days * 86_400_000.0).toLong()
    }
}

object TimeUnits {
    const val MILLIS_PER_SECOND: Long = 1_000L
    const val SECONDS_PER_MINUTE: Long = 60L
    const val SECONDS_PER_DAY: Long = 86_400L
    const val MILLIS_PER_DAY: Long = 86_400_000L

    /** Epoch minutes, floored. The QR payload stores minutes to fit issuance time in 4 bytes. */
    fun epochSecondsToMinutes(epochSeconds: Long): Long {
        require(epochSeconds >= 0) { "epochSeconds must be non-negative, got $epochSeconds" }
        return epochSeconds / SECONDS_PER_MINUTE
    }

    fun epochMinutesToSeconds(epochMinutes: Long): Long {
        require(epochMinutes >= 0) { "epochMinutes must be non-negative, got $epochMinutes" }
        return epochMinutes * SECONDS_PER_MINUTE
    }
}
