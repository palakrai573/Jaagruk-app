package org.jaagruk.safety.ar

import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource
import kotlin.math.sqrt

/**
 * Watches how far the worker physically moves during an AR drill, and stops them if it becomes unsafe.
 *
 * This is the part of AR safety training that is easy to forget while building AR safety training: the
 * trainee is a person walking around a live industrial site staring at a phone. A drill that rewards
 * moving towards a marker is actively encouraging that. So the drill is bounded.
 *
 * Two independent triggers, because they catch different failures:
 *
 *  * **Displacement from the start point.** The worker has walked out of the space the supervisor cleared
 *    for the drill. Bounded at a radius a person can see across.
 *  * **Sustained movement while the phone is up.** Distance can stay small while somebody walks in
 *    circles, and walking in circles in a haulage road is exactly as dangerous as walking in a line.
 *
 * On trigger the drill pauses rather than aborts. The worker is not penalised for the interruption — the
 * latency clock stops, they step back into the zone, and the step resumes. Aborting would teach people
 * to ignore the warning to avoid losing their run.
 *
 * Only meaningful on the ARCore path, which is the only one with a world position. The sensor and
 * pictogram paths do not move the scene when the worker walks, so there is nothing to watch and nothing
 * encouraging them to walk.
 */
class ZoneWatchdog(
    private val radiusMetres: Float = DEFAULT_RADIUS_M,
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
) {

    enum class Verdict {
        /** Inside the zone and not wandering. */
        OK,

        /** Approaching the boundary. Worth a gentle prompt, no interruption. */
        NEAR_BOUNDARY,

        /** Outside the zone. The drill pauses until the worker comes back. */
        OUT_OF_ZONE,

        /** Moving continuously for too long. The drill pauses and asks them to stand still. */
        WALKING_TOO_MUCH,
    }

    data class Status(
        val verdict: Verdict,
        val distanceMetres: Float,
        /** True while the drill should be held. */
        val shouldPause: Boolean,
    )

    private var originX = 0f
    private var originY = 0f
    private var originZ = 0f
    private var originSet = false

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastSampleAtMs = 0L

    private var movingSinceMs: Long? = null
    private var cumulativePathMetres = 0f

    /** Resets the zone to the worker's current position. Called when a scenario starts. */
    fun setOrigin(x: Float, y: Float, z: Float) {
        originX = x
        originY = y
        originZ = z
        lastX = x
        lastY = y
        lastZ = z
        originSet = true
        movingSinceMs = null
        cumulativePathMetres = 0f
        lastSampleAtMs = monotonic.elapsedMillis()
    }

    fun reset() {
        originSet = false
        movingSinceMs = null
        cumulativePathMetres = 0f
    }

    /** Feed the camera's world position each tracked frame. */
    fun onCameraPosition(x: Float, y: Float, z: Float): Status {
        if (!originSet) {
            setOrigin(x, y, z)
            return Status(Verdict.OK, 0f, shouldPause = false)
        }

        val now = monotonic.elapsedMillis()
        val stepDistance = distance(x, y, z, lastX, lastY, lastZ)

        // Only horizontal displacement counts. Crouching to look under a conveyor is exactly the posture
        // a machinery drill is trying to teach, and counting it as wandering would penalise the correct
        // behaviour.
        val fromOrigin = horizontalDistance(x, z, originX, originZ)

        if (stepDistance > MOVEMENT_EPSILON_M) {
            cumulativePathMetres += stepDistance
            if (movingSinceMs == null) movingSinceMs = now
        } else if (now - lastSampleAtMs > STILL_RESET_MS) {
            // Standing still long enough clears the walking timer. Someone who stops to think has not
            // been wandering.
            movingSinceMs = null
            cumulativePathMetres = 0f
        }

        lastX = x
        lastY = y
        lastZ = z
        lastSampleAtMs = now

        val walkingFor = movingSinceMs?.let { now - it } ?: 0L
        val walkingTooMuch = walkingFor >= WALKING_LIMIT_MS &&
            cumulativePathMetres >= WALKING_LIMIT_METRES

        val verdict = when {
            fromOrigin > radiusMetres -> Verdict.OUT_OF_ZONE
            walkingTooMuch -> Verdict.WALKING_TOO_MUCH
            fromOrigin > radiusMetres * NEAR_BOUNDARY_FRACTION -> Verdict.NEAR_BOUNDARY
            else -> Verdict.OK
        }

        return Status(
            verdict = verdict,
            distanceMetres = fromOrigin,
            shouldPause = verdict == Verdict.OUT_OF_ZONE || verdict == Verdict.WALKING_TOO_MUCH,
        )
    }

    /** Total ground covered this scenario. Recorded with the run, so a site can review drill conduct. */
    fun pathLengthMetres(): Float = cumulativePathMetres

    private fun distance(
        ax: Float,
        ay: Float,
        az: Float,
        bx: Float,
        by: Float,
        bz: Float,
    ): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun horizontalDistance(ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = ax - bx
        val dz = az - bz
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        /**
         * Four metres.
         *
         * Roughly the space a supervisor can clear and keep an eye on in a plant walkway, and small
         * enough that a worker inside it has not walked into anything new.
         */
        const val DEFAULT_RADIUS_M: Float = 4.0f

        /** Fraction of the radius at which the gentle prompt appears. */
        const val NEAR_BOUNDARY_FRACTION: Float = 0.75f

        /** Per-frame movement below this is tracking jitter, not walking. */
        const val MOVEMENT_EPSILON_M: Float = 0.02f

        /** Continuous movement for this long, covering this far, counts as wandering. */
        const val WALKING_LIMIT_MS: Long = 8_000L
        const val WALKING_LIMIT_METRES: Float = 6.0f

        /** Standing still for this long clears the walking timer. */
        const val STILL_RESET_MS: Long = 1_500L
    }
}
