package org.jaagruk.safety.ar

import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.catalog.ArTargets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decides where each marker physically goes.
 *
 * The whole reason scenarios name targets symbolically is so this class can be the only place that
 * knows about geometry. Three placement sources, in strict priority order:
 *
 *  1. **A resolved site anchor.** The supervisor pinned "primary exit" to the real doorway during a site
 *     scan, so the marker sits on the real doorway. This is the version that builds muscle memory of
 *     *this* corridor, which is the entire argument for site scanning over a generic room.
 *  2. **A template offset around the worker.** Deterministic per target, so the same target is always in
 *     the same relative direction. That consistency matters: a drill where the exit is somewhere new
 *     every run teaches nothing about looking in a direction.
 *  3. **A flat 2D layout.** Grid positions, used by the pictogram fallback.
 *
 * Distractors are placed with the same care as correct answers. A distractor that is obviously
 * mis-scaled or floating in mid-air is not a distractor, and a test whose wrong answers are visibly
 * wrong measures nothing.
 */
class ScenePlacer {

    /** Anchor positions resolved for this site, keyed by target. Metres in the session frame. */
    private val resolvedAnchors = mutableMapOf<String, Triple<Float, Float, Float>>()

    fun setResolvedAnchor(targetKey: String, x: Float, y: Float, z: Float) {
        resolvedAnchors[targetKey] = Triple(x, y, z)
    }

    fun clearResolvedAnchors() = resolvedAnchors.clear()

    val resolvedCount: Int get() = resolvedAnchors.size

    /**
     * How many semantically anchored targets are resolved.
     *
     * Only these count towards claiming a site-scanned run, because only these change what the drill
     * teaches. Ten anchored toolboxes do not make a fire evacuation site-specific.
     */
    fun resolvedSemanticCount(): Int =
        resolvedAnchors.keys.count { ArTargets.requiresSiteAnchor(it) }

    /**
     * The presentation this scene may honestly claim.
     *
     * Called at the point the certificate flags are built, and deliberately conservative: the flag is
     * signed, so overclaiming it is a permanent record of something that was not true.
     */
    fun presentationFor(
        capability: ArAvailability.Capability,
        markers: List<SceneMarker>,
    ): ArPresentation {
        val base = capability.basePresentation()
        if (base != ArPresentation.ARCORE_GENERIC) return base

        val semanticTargets = markers.map { it.targetKey }.filter(ArTargets::requiresSiteAnchor)
        if (semanticTargets.isEmpty()) return ArPresentation.ARCORE_GENERIC

        // Every semantic target in *this step* must be site-anchored. A step where the real exit is
        // anchored but the decoy exit is a template offset would be trivially winnable by noticing which
        // marker looks placed, which measures nothing about evacuation.
        val allAnchored = semanticTargets.all { it in resolvedAnchors }
        return if (allAnchored) ArPresentation.SITE_SCANNED else ArPresentation.ARCORE_GENERIC
    }

    /** Places every marker for a step. */
    fun place(markers: List<SceneMarker>, flat: Boolean = false): List<MarkerPlacement> {
        if (flat) return placeFlat(markers)

        return markers.mapIndexed { index, marker ->
            val anchored = resolvedAnchors[marker.targetKey]
            if (anchored != null) {
                MarkerPlacement(
                    marker = marker,
                    x = anchored.first,
                    y = anchored.second,
                    z = anchored.third,
                    siteAnchored = true,
                )
            } else {
                val (x, y, z) = templateOffset(marker.targetKey, index, markers.size)
                MarkerPlacement(marker, x, y, z, siteAnchored = false)
            }
        }
    }

    /**
     * Deterministic position for an unanchored target.
     *
     * Derived from the target name rather than the list index, so the extinguisher station is in the
     * same direction on every run and across every handset at the site. Using the index would move
     * markers whenever a scenario author reordered options — and a worker who learned "the alarm is on
     * my left" would then be wrong through no fault of their own.
     */
    private fun templateOffset(targetKey: String, index: Int, total: Int): Triple<Float, Float, Float> {
        val slot = TEMPLATE_DIRECTIONS[targetKey]
        if (slot != null) return slot

        // Unlisted target: spread evenly across the forward arc, stable in the target's name.
        val hash = targetKey.hashCode()
        val spread = if (total <= 1) 0.0 else (index.toDouble() / (total - 1)) - 0.5
        val angle = spread * FORWARD_ARC_RADIANS + ((hash % 7) - 3) * 0.04
        val distance = DEFAULT_DISTANCE_M + ((hash.mod(3)) * 0.4f)
        return Triple(
            (sin(angle) * distance).toFloat(),
            DEFAULT_HEIGHT_M,
            (-cos(angle) * distance).toFloat(),
        )
    }

    /**
     * Flat fallback layout.
     *
     * A two-column grid at a fixed distance, facing the worker. Not AR, and not pretending to be: this
     * is the path a handset with no camera or no GLES3 takes, and the drill still asks the same
     * questions in the same order under the same clock, so the score means the same thing.
     */
    private fun placeFlat(markers: List<SceneMarker>): List<MarkerPlacement> {
        val columns = if (markers.size <= 2) markers.size.coerceAtLeast(1) else 2
        return markers.mapIndexed { index, marker ->
            val row = index / columns
            val column = index % columns
            val x = (column - (columns - 1) / 2f) * FLAT_SPACING_M
            val y = DEFAULT_HEIGHT_M - row * FLAT_SPACING_M
            MarkerPlacement(marker, x, y, -FLAT_DISTANCE_M, siteAnchored = false)
        }
    }

    private companion object {
        const val DEFAULT_DISTANCE_M = 3.2f
        const val DEFAULT_HEIGHT_M = 0.1f
        const val FLAT_DISTANCE_M = 2.5f
        const val FLAT_SPACING_M = 1.1f

        /** 150 degrees. Wide enough to require turning, narrow enough to be findable. */
        val FORWARD_ARC_RADIANS = 150.0 * PI / 180.0

        /**
         * Fixed directions for the targets a worker should build a spatial habit about.
         *
         * Coordinates are metres: +x right, +y up, -z forward. Chosen to be reachable by turning rather
         * than walking, because a drill run in a canteen or a site office has no room to walk and a
         * worker stepping backwards while wearing a headlamp is a hazard the training itself created.
         */
        val TEMPLATE_DIRECTIONS: Map<String, Triple<Float, Float, Float>> = mapOf(
            // Ahead and slightly left: the primary exit is the thing to find first.
            ArTargets.EXIT_PRIMARY to Triple(-1.4f, 0.2f, -3.4f),
            ArTargets.EXIT_SECONDARY to Triple(3.0f, 0.2f, -1.6f),
            // Behind-right, so choosing it requires actually turning and looking.
            ArTargets.EXIT_BLOCKED to Triple(2.6f, 0.2f, 1.8f),
            ArTargets.ASSEMBLY_POINT to Triple(0.4f, 0.0f, -5.0f),
            ArTargets.LIFT_DOOR to Triple(-3.1f, 0.2f, 0.9f),
            ArTargets.STORE_ROOM to Triple(-2.4f, 0.1f, 2.2f),
            ArTargets.REFUGE_CHAMBER to Triple(1.2f, 0.1f, -4.2f),

            ArTargets.EXTINGUISHER_STATION to Triple(-2.2f, -0.1f, -2.4f),
            ArTargets.FIRE_ALARM_POINT to Triple(-1.0f, 0.5f, -2.0f),
            ArTargets.HOSE_REEL to Triple(-2.8f, 0.0f, -1.2f),

            ArTargets.GAS_ZONE to Triple(0.0f, -0.3f, -2.8f),
            ArTargets.VENT_FAN to Triple(2.0f, 0.6f, -2.6f),
            ArTargets.CONFINED_SPACE_ENTRY to Triple(0.8f, -0.6f, -2.2f),
            ArTargets.GAS_VALVE to Triple(1.6f, -0.2f, -1.8f),

            ArTargets.MACHINE_NIP_POINT to Triple(-0.6f, -0.2f, -2.0f),
            ArTargets.MACHINE_GUARD to Triple(0.6f, -0.2f, -2.0f),
            ArTargets.ISOLATOR_SWITCH to Triple(-2.0f, 0.3f, -1.4f),
            ArTargets.CONVEYOR_PULL_CORD to Triple(1.8f, 0.4f, -1.6f),
            ArTargets.WINCH_DRUM to Triple(2.4f, -0.1f, -2.2f),

            ArTargets.ANCHOR_POINT_VALID to Triple(-1.2f, 1.4f, -2.6f),
            ArTargets.ANCHOR_POINT_INVALID to Triple(1.2f, 1.3f, -2.6f),
            ArTargets.DAMAGED_CABLE to Triple(-1.8f, -0.4f, -2.2f),
            ArTargets.LV_PANEL to Triple(-2.6f, 0.2f, -1.0f),

            ArTargets.WALKWAY to Triple(0.0f, -0.8f, -2.0f),
            ArTargets.TOOLBOX to Triple(1.4f, -0.7f, -1.4f),
            ArTargets.LADDER to Triple(-1.6f, 0.4f, -1.6f),
            ArTargets.WATER_PUMP to Triple(2.2f, -0.6f, -1.2f),
            ArTargets.VEHICLE_PARK to Triple(3.2f, -0.2f, -2.8f),
            ArTargets.MAIN_GATE to Triple(-3.4f, 0.2f, -2.6f),
            ArTargets.SCAFFOLD_RAIL to Triple(1.0f, 0.9f, -2.4f),
            ArTargets.PIPE_RUN to Triple(-0.8f, 1.1f, -1.8f),
        )
    }
}
