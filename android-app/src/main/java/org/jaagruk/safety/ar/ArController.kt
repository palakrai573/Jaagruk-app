package org.jaagruk.safety.ar

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.catalog.Pictogram

/**
 * A marker the scene is asked to show.
 *
 * Deliberately declarative: the drill says "show these five targets", and the controller decides where
 * they physically go — a resolved site Cloud Anchor if one exists for that target, a template offset
 * around the worker otherwise. Because the indirection is explicit, one scenario definition runs
 * unchanged in full site-scanned AR, in a generic room, on a sensor-only fallback and in a flat 2D
 * drill.
 */
data class SceneMarker(
    /** Matches `StepOption.optionId`, so a hit maps straight back to an answer. */
    val optionId: String,
    /** One of `org.jaagruk.core.catalog.ArTargets`. */
    val targetKey: String,
    val pictogram: Pictogram,
    /** Shown under the marker unless the worker is in zero-text mode. */
    val label: String?,
    val highlighted: Boolean = false,
)

/** Where a marker ended up, and how much that placement can be trusted. */
data class MarkerPlacement(
    val marker: SceneMarker,
    /** Metres, right-handed, +y up, relative to the worker at scene start. */
    val x: Float,
    val y: Float,
    val z: Float,
    /** True when this position came from a resolved site anchor rather than a template. */
    val siteAnchored: Boolean,
)

/** Live tracking quality, for the coach overlay. */
enum class ArTrackingQuality {
    /** Nothing usable yet. */
    INITIALISING,

    /** Tracking, but the pose is unreliable — too dark, too featureless, moving too fast. */
    LIMITED,

    /** Good. */
    TRACKING,

    /** Lost and not recovering. The drill pauses rather than scoring a worker on a frozen scene. */
    LOST,

    /** Camera unavailable — revoked permission, another app took it, hardware error. */
    UNAVAILABLE,
    ;

    val isUsable: Boolean get() = this == TRACKING || this == LIMITED
}

/** Why tracking is limited, in terms a worker can act on. */
enum class TrackingHint {
    NONE,
    TOO_DARK,
    TOO_FAST,
    NOT_ENOUGH_TEXTURE,
    LOOK_AROUND_MORE,
    CAMERA_BLOCKED,
    RECOVERING,
}

/**
 * A marker's position on screen, recomputed every frame.
 *
 * Published by the controller rather than computed in the UI because the view and projection matrices only
 * exist inside the AR session. It is what lets markers be ordinary composables — with content descriptions,
 * proper Devanagari and Ol Chiki shaping, and real touch targets — instead of GL quads with no semantics.
 */
data class ProjectedMarker(
    val optionId: String,
    val pictogram: Pictogram,
    val label: String?,
    /** Pixels, in the AR surface's coordinate space. Only meaningful when [visible]. */
    val screenX: Float,
    val screenY: Float,
    val visible: Boolean,
    val distanceMetres: Float,
    /**
     * -1..1 when the marker is off-screen, telling the worker which way to turn.
     *
     * Without this, a worker who turned the wrong way sees an empty scene and concludes the app is broken —
     * the single most common way an AR drill fails in the field.
     */
    val offScreenDirection: Float,
    val siteAnchored: Boolean,
)

/** Snapshot of everything the UI needs from the AR layer on each frame. */
data class ArState(
    val quality: ArTrackingQuality = ArTrackingQuality.INITIALISING,
    val hint: TrackingHint = TrackingHint.NONE,
    val presentation: ArPresentation = ArPresentation.PICTOGRAM_2D,
    val placements: List<MarkerPlacement> = emptyList(),
    val projected: List<ProjectedMarker> = emptyList(),
    /** Option id currently under the reticle, for dwell steps and gaze selection. */
    val reticleOptionId: String? = null,
    /** 0.0..1.0 progress of the current dwell. */
    val dwellProgress: Float = 0f,
    /** True when at least one marker came from a resolved site anchor. */
    val siteAnchored: Boolean = false,
    /** Non-null when the session failed in a way the worker should be told about. */
    val failureMessageKey: String? = null,
)

/**
 * The AR surface, as the drill sees it.
 *
 * One interface, three implementations, and the drill code cannot tell them apart. That is what keeps a
 * non-ARCore handset from being a second-class path bolted on later: `ArCoreController`,
 * `SensorFallbackArController` and the flat pictogram mode all satisfy the same contract, so the
 * assessment engine, the scoring, the certificate flags and the UI are identical across them.
 *
 * Nothing here is suspending. The AR loop runs on its own GL thread and publishes state through
 * [state]; a suspending call in the frame path would introduce a scheduling delay into the one
 * measurement this platform is built on.
 */
interface ArController {

    val state: StateFlow<ArState>

    /** Capability this controller is serving. Fixed for its lifetime. */
    val capability: ArAvailability.Capability

    /**
     * Binds to a lifecycle. Camera and GL resources follow it, so a backgrounded drill releases the
     * camera for the next app rather than holding it and being killed.
     */
    fun attach(owner: LifecycleOwner)

    fun detach()

    /**
     * Loads the markers for the current step.
     *
     * Called once per step, not per frame. Re-placing markers every frame would let a marker drift
     * between the moment a worker decides and the moment they touch it.
     */
    fun setMarkers(siteId: String, markers: List<SceneMarker>)

    fun clearMarkers()

    /**
     * Screen-space tap to option id.
     *
     * Returns null when the tap missed everything, which the drill treats as no answer rather than a
     * wrong one — a glove sliding off a marker must not score as an incorrect decision.
     */
    fun hitTest(screenX: Float, screenY: Float): String?

    /** Begins a dwell requirement on [optionId]; progress arrives through [state]. */
    fun beginDwell(optionId: String, dwellMs: Long)

    fun cancelDwell()

    /**
     * Places a persistent site anchor at the current reticle position.
     *
     * Only meaningful on the ARCore path. The sensor fallback returns false rather than pretending,
     * because a sensor-only anchor would not survive the worker turning around.
     */
    suspend fun placeSiteAnchor(siteId: String, targetKey: String, label: String?): Boolean

    /** True when the session has resolved enough site anchors to claim a site-scanned presentation. */
    fun isSiteAnchored(): Boolean

    /** Turns the torch on or off, for the coach's low-light prompt. */
    fun setTorch(enabled: Boolean)
}
