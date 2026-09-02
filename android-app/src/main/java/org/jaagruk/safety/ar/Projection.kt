package org.jaagruk.safety.ar

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Projects world-space marker positions into screen space.
 *
 * This exists because markers are drawn as a Compose overlay rather than as GL geometry. The camera
 * background needs OpenGL — ARCore will only hand its camera image to a GL texture — but the markers
 * themselves do not, and drawing them in Compose buys three things that matter more here than a fancier
 * renderer would:
 *
 *  * **Real accessibility.** A GL quad has no semantics. A Compose marker has a content description, so
 *    TalkBack can read out the options and `ContentDescription` can stay a fatal lint check.
 *  * **Real text rendering.** Devanagari and Ol Chiki shaping through a hand-rolled GL text pipeline is
 *    a project in itself, and getting it subtly wrong means a Santali label renders as boxes.
 *  * **A frame budget that stays free.** The measurement this platform is built on is decision latency;
 *    spending milliseconds on a glTF pipeline to make a warning triangle look nicer is the wrong trade.
 *
 * All maths is plain float arithmetic against the same view and projection matrices ARCore supplies, so
 * a marker sits exactly where a GL-rendered one would.
 */
class Projection {

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val scratchIn = FloatArray(4)
    private val scratchOut = FloatArray(4)

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var valid: Boolean = false

    init {
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.setIdentityM(viewProjection, 0)
    }

    val isValid: Boolean get() = valid && viewportWidth > 0 && viewportHeight > 0

    fun setViewport(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    /** Copies in this frame's matrices. Both are 16-element column-major, as ARCore supplies them. */
    fun update(view: FloatArray, projection: FloatArray) {
        require(view.size >= 16 && projection.size >= 16) {
            "view and projection must be 4x4 column-major matrices"
        }
        System.arraycopy(view, 0, viewMatrix, 0, 16)
        System.arraycopy(projection, 0, projectionMatrix, 0, 16)
        Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)
        valid = true
    }

    fun invalidate() {
        valid = false
    }

    /** A marker's on-screen position, or the reason it has none. */
    data class ScreenPoint(
        val x: Float,
        val y: Float,
        /** Metres from the camera. Drives marker scale and draw order. */
        val distanceMetres: Float,
        /** False when the marker is behind the camera or outside the frustum. */
        val visible: Boolean,
        /**
         * Signed horizontal offset when the marker is off-screen, -1..1.
         *
         * Drives the off-screen arrow. Without it a worker who has turned the wrong way sees an empty
         * scene and concludes the app is broken, which is the single most common way an AR drill fails
         * in the field.
         */
        val offScreenDirection: Float,
    )

    /**
     * Projects a world point.
     *
     * Returns `visible = false` rather than clamping, because a marker clamped to the screen edge looks
     * like a real option sitting there and a worker would tap it. The off-screen case is a distinct UI
     * affordance, not a degenerate version of the on-screen one.
     */
    fun project(x: Float, y: Float, z: Float): ScreenPoint {
        if (!isValid) return ScreenPoint(0f, 0f, 0f, visible = false, offScreenDirection = 0f)

        scratchIn[0] = x
        scratchIn[1] = y
        scratchIn[2] = z
        scratchIn[3] = 1f
        Matrix.multiplyMV(scratchOut, 0, viewProjection, 0, scratchIn, 0)

        val w = scratchOut[3]
        val distance = cameraDistanceTo(x, y, z)

        // w <= 0 means the point is at or behind the camera plane. Dividing by it produces a
        // plausible-looking position on the wrong side of the screen.
        if (abs(w) < EPSILON || w <= 0f) {
            return ScreenPoint(
                x = 0f,
                y = 0f,
                distanceMetres = distance,
                visible = false,
                // The view-space x tells us which way to turn even when the projection is unusable.
                offScreenDirection = if (viewSpaceX(x, y, z) >= 0f) 1f else -1f,
            )
        }

        val ndcX = scratchOut[0] / w
        val ndcY = scratchOut[1] / w

        val screenX = (ndcX + 1f) * 0.5f * viewportWidth
        val screenY = (1f - ndcY) * 0.5f * viewportHeight

        val onScreen = ndcX in -1f..1f && ndcY in -1f..1f
        return ScreenPoint(
            x = screenX,
            y = screenY,
            distanceMetres = distance,
            visible = onScreen,
            offScreenDirection = ndcX.coerceIn(-1f, 1f),
        )
    }

    /**
     * Nearest marker to a screen tap, within [radiusPx].
     *
     * Radius-based rather than exact-bounds, and generously so. Workers wear gloves; the tap lands
     * several millimetres from where they intended. Requiring a precise hit would score glove slip as a
     * wrong decision, which is a measurement error dressed up as a training result.
     */
    fun nearestWithin(
        placements: List<MarkerPlacement>,
        tapX: Float,
        tapY: Float,
        radiusPx: Float,
    ): MarkerPlacement? {
        var best: MarkerPlacement? = null
        var bestDistance = Float.MAX_VALUE

        for (placement in placements) {
            val point = project(placement.x, placement.y, placement.z)
            if (!point.visible) continue
            val dx = point.x - tapX
            val dy = point.y - tapY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= radiusPx && distance < bestDistance) {
                bestDistance = distance
                best = placement
            }
        }
        return best
    }

    /** The marker closest to the screen centre, for gaze and dwell selection. */
    fun underReticle(placements: List<MarkerPlacement>, radiusPx: Float): MarkerPlacement? =
        nearestWithin(placements, viewportWidth / 2f, viewportHeight / 2f, radiusPx)

    /**
     * Metres from the camera to a world point.
     *
     * Read out of the view matrix rather than tracked separately, so it cannot drift out of step with
     * the pose the projection actually used.
     */
    private fun cameraDistanceTo(x: Float, y: Float, z: Float): Float {
        scratchIn[0] = x
        scratchIn[1] = y
        scratchIn[2] = z
        scratchIn[3] = 1f
        Matrix.multiplyMV(scratchOut, 0, viewMatrix, 0, scratchIn, 0)
        val vx = scratchOut[0]
        val vy = scratchOut[1]
        val vz = scratchOut[2]
        return sqrt(vx * vx + vy * vy + vz * vz)
    }

    private fun viewSpaceX(x: Float, y: Float, z: Float): Float {
        scratchIn[0] = x
        scratchIn[1] = y
        scratchIn[2] = z
        scratchIn[3] = 1f
        Matrix.multiplyMV(scratchOut, 0, viewMatrix, 0, scratchIn, 0)
        return scratchOut[0]
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
