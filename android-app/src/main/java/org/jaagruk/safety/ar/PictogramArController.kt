package org.jaagruk.safety.ar

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource

/**
 * The floor of the fallback ladder: a flat pictogram drill with no camera at all.
 *
 * Reached on a handset with no GLES3, no usable camera, or no orientation sensor — and also chosen
 * deliberately when a worker turns AR off, which some do after a shift underground with a headlamp.
 *
 * The important property is that **the assessment is unchanged**. Same steps, same order, same timeouts,
 * same expert baselines, same hesitation detection, same scoring, same certificate. Only the
 * presentation differs, and it is recorded as `PICTOGRAM_2D` inside the signature so a reviewer can see
 * the fidelity the run was assessed at. That honesty is the whole point: a fallback that quietly claimed
 * to be AR would make the `SITE_SCANNED_AR` flag meaningless everywhere.
 *
 * Options are rendered by the UI as a grid of cards, so this controller places nothing in space and
 * never hit-tests. Dwell steps become a hold-to-confirm timer, which tests the same "commit to one
 * answer for a sustained moment" behaviour without pretending to have a gaze direction.
 */
class PictogramArController(
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
) : ArController {

    private val _state = MutableStateFlow(
        ArState(
            // TRACKING, not INITIALISING: there is nothing to track and nothing to wait for, and
            // reporting anything else would make the coach pause a drill that is running perfectly.
            quality = ArTrackingQuality.TRACKING,
            presentation = ArPresentation.PICTOGRAM_2D,
        ),
    )
    override val state: StateFlow<ArState> = _state.asStateFlow()

    override val capability: ArAvailability.Capability = ArAvailability.Capability.PICTOGRAM_ONLY

    private var markers: List<SceneMarker> = emptyList()
    private var dwellOptionId: String? = null
    private var dwellRequiredMs: Long = 0L
    private var dwellStartedAtMs: Long = 0L

    override fun attach(owner: LifecycleOwner) = Unit

    override fun detach() {
        markers = emptyList()
        dwellOptionId = null
    }

    override fun setMarkers(siteId: String, markers: List<SceneMarker>) {
        this.markers = markers
        // Placements are left empty on purpose. There is no 3D scene, and synthesising coordinates for a
        // grid of cards would invite the UI to project them, which would look like AR and is not.
        _state.value = _state.value.copy(
            placements = emptyList(),
            presentation = ArPresentation.PICTOGRAM_2D,
            siteAnchored = false,
        )
    }

    override fun clearMarkers() {
        markers = emptyList()
        dwellOptionId = null
        _state.value = _state.value.copy(dwellProgress = 0f)
    }

    /** The options the UI should lay out as cards, in scenario order. */
    fun currentMarkers(): List<SceneMarker> = markers

    override fun hitTest(screenX: Float, screenY: Float): String? = null

    override fun beginDwell(optionId: String, dwellMs: Long) {
        dwellOptionId = optionId
        dwellRequiredMs = dwellMs.coerceAtLeast(1L)
        dwellStartedAtMs = monotonic.elapsedMillis()
        _state.value = _state.value.copy(reticleOptionId = optionId, dwellProgress = 0f)
    }

    /** Called while the worker holds a card down. Returns true once the hold is satisfied. */
    fun tickDwell(): Boolean {
        if (dwellOptionId == null || dwellStartedAtMs == 0L) return false
        val elapsed = monotonic.elapsedMillis() - dwellStartedAtMs
        val progress = (elapsed.toFloat() / dwellRequiredMs).coerceIn(0f, 1f)
        _state.value = _state.value.copy(dwellProgress = progress)
        return progress >= 1f
    }

    override fun cancelDwell() {
        dwellOptionId = null
        dwellStartedAtMs = 0L
        _state.value = _state.value.copy(reticleOptionId = null, dwellProgress = 0f)
    }

    override suspend fun placeSiteAnchor(siteId: String, targetKey: String, label: String?): Boolean =
        false

    override fun isSiteAnchored(): Boolean = false

    /** No camera, so no torch. */
    override fun setTorch(enabled: Boolean) = Unit
}
