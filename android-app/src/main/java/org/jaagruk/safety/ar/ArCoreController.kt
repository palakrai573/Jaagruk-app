package org.jaagruk.safety.ar

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource
import org.jaagruk.safety.data.repo.SiteRepository
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The full ARCore path: world tracking, plane hit-testing and site anchors.
 *
 * Structure worth knowing before reading it:
 *
 *  * The **GL thread** does exactly two things — draw the camera background and copy out this frame's
 *    view and projection matrices. Everything else reads a published [ArState].
 *  * **Markers are placed once per step, never per frame.** Re-placing them continuously would let a
 *    marker drift between the instant a worker decides and the instant their glove lands on it, which
 *    would show up as latency the worker did not spend.
 *  * **Tracking loss pauses the drill** through [TrackingCoach]. Scoring decision latency against a
 *    frozen scene would corrupt the one measurement this whole platform is built on.
 *  * **Session creation failure is not a crash.** Every `UnavailableException` subtype resolves to a
 *    state the caller can fall back from; a worker at the start of a shift gets a drill either way.
 */
class ArCoreController(
    private val context: Context,
    private val siteRepository: SiteRepository,
    private val anchorResolver: AnchorResolver,
    private val scope: CoroutineScope,
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
) : ArController, GLSurfaceView.Renderer {

    private val background = CameraBackgroundRenderer()
    private val projection = Projection()
    private val placer = ScenePlacer()
    private val coach = TrackingCoach(monotonic)

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    private val _state = MutableStateFlow(ArState())
    override val state: StateFlow<ArState> = _state.asStateFlow()

    override val capability: ArAvailability.Capability = ArAvailability.Capability.ARCORE_READY

    private var session: Session? = null
    private var surfaceView: GLSurfaceView? = null
    private var lifecycleOwner: LifecycleOwner? = null

    @Volatile
    private var glReady = false

    @Volatile
    private var viewportWidth = 0

    @Volatile
    private var viewportHeight = 0

    @Volatile
    private var displayRotation = Surface.ROTATION_0

    @Volatile
    private var markers: List<SceneMarker> = emptyList()

    @Volatile
    private var placements: List<MarkerPlacement> = emptyList()

    @Volatile
    private var torchOn = false

    /** Anchors resolved for the current site, kept so their poses can be refreshed each frame. */
    private val liveAnchors = mutableMapOf<String, Anchor>()

    private var dwellOptionId: String? = null
    private var dwellRequiredMs: Long = 0L
    private var dwellStartedAtMs: Long = 0L

    /** Set by [ArSurfaceHost] once the Compose tree has a surface to render into. */
    fun bindSurface(view: GLSurfaceView) {
        surfaceView = view
        view.preserveEGLContextOnPause = true
        view.setEGLContextClientVersion(3)
        // 8/8/8/16/0: alpha is present because the Compose marker overlay composites on top, and a
        // 16-bit depth buffer is plenty for a scene whose furthest object is five metres away.
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        view.setRenderer(this)
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view.setWillNotDraw(false)
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
        owner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = resumeSession()

                override fun onPause(owner: LifecycleOwner) = pauseSession()

                override fun onDestroy(owner: LifecycleOwner) = detach()
            },
        )
    }

    override fun detach() {
        pauseSession()
        session?.close()
        session = null
        liveAnchors.values.forEach { runCatching { it.detach() } }
        liveAnchors.clear()
        placer.clearResolvedAnchors()
        coach.reset()
        projection.invalidate()
        lifecycleOwner = null
    }

    private fun resumeSession() {
        val existing = session
        if (existing != null) {
            try {
                existing.resume()
                surfaceView?.onResume()
                return
            } catch (e: CameraNotAvailableException) {
                publishFailure("ar_error_camera_unavailable")
                return
            }
        }

        val created = try {
            Session(context)
        } catch (e: UnavailableException) {
            // Every subtype is a device fact rather than a bug: no Play Services for AR, an APK that is
            // too old, a device that was never certified, or an SDK version mismatch. The caller falls
            // back to the sensor path.
            Log.i(TAG, "ARCore session unavailable: ${e::class.java.simpleName}")
            publishFailure("ar_error_arcore_unavailable")
            return
        } catch (e: Exception) {
            Log.w(TAG, "unexpected ARCore session failure", e)
            publishFailure("ar_error_arcore_unavailable")
            return
        }

        val config = Config(created).apply {
            // LATEST_CAMERA_IMAGE, not BLOCKING: a blocking update ties the GL thread to the camera's
            // frame rate, and on a mid-range handset that turns into visible stutter in the exact
            // moment latency is being measured.
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            // Light estimation is off. It costs frame time and buys nothing: these markers are flat
            // high-contrast pictograms that must stay legible in a headlamp beam, not lit objects
            // pretending to belong to the room.
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = Config.DepthMode.DISABLED
            if (anchorResolver.cloudAnchorsEnabled) {
                cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            }
        }

        try {
            created.configure(config)
            created.resume()
        } catch (e: CameraNotAvailableException) {
            created.close()
            publishFailure("ar_error_camera_unavailable")
            return
        } catch (e: Exception) {
            created.close()
            Log.w(TAG, "ARCore configure failed", e)
            publishFailure("ar_error_arcore_unavailable")
            return
        }

        session = created
        surfaceView?.onResume()
        _state.value = _state.value.copy(
            quality = ArTrackingQuality.INITIALISING,
            failureMessageKey = null,
        )
    }

    private fun pauseSession() {
        surfaceView?.onPause()
        runCatching { session?.pause() }
        coach.reset()
        projection.invalidate()
    }

    private fun publishFailure(messageKey: String) {
        _state.value = _state.value.copy(
            quality = ArTrackingQuality.UNAVAILABLE,
            hint = TrackingHint.CAMERA_BLOCKED,
            failureMessageKey = messageKey,
        )
    }

    // -----------------------------------------------------------------------
    // Markers
    // -----------------------------------------------------------------------

    override fun setMarkers(siteId: String, markers: List<SceneMarker>) {
        this.markers = markers
        placements = placer.place(markers)
        publishPlacements()
        resolveSiteAnchors(siteId)
    }

    override fun clearMarkers() {
        markers = emptyList()
        placements = emptyList()
        dwellOptionId = null
        publishPlacements()
    }

    /**
     * Resolves this site's stored anchors and re-places the markers that hit.
     *
     * Runs off the GL thread and updates placements when it finishes, so a slow or failing resolve never
     * delays the first frame. A worker sees the drill immediately and it becomes more site-specific a
     * moment later, rather than waiting on a network call that may not succeed at all.
     */
    private fun resolveSiteAnchors(siteId: String) {
        val activeSession = session ?: return
        val owner = lifecycleOwner ?: return

        owner.lifecycleScope.launch {
            val stored = siteRepository.anchors(siteId)
            if (stored.isEmpty()) return@launch

            val relevant = stored.filter { entity ->
                markers.any { it.targetKey == entity.targetKey }
            }
            if (relevant.isEmpty()) return@launch

            anchorResolver.resolveAll(
                session = activeSession,
                anchors = relevant,
                onResolved = { entity, anchor ->
                    liveAnchors[entity.targetKey] = anchor
                    val pose = anchor.pose
                    placer.setResolvedAnchor(entity.targetKey, pose.tx(), pose.ty(), pose.tz())
                    scope.launch { siteRepository.recordResolveSuccess(entity.anchorId) }
                },
                onFailed = { entity ->
                    scope.launch { siteRepository.recordResolveFailure(entity.anchorId) }
                },
            )

            placements = placer.place(markers)
            publishPlacements()
        }
    }

    private fun publishPlacements() {
        _state.value = _state.value.copy(
            placements = placements,
            presentation = placer.presentationFor(capability, markers),
            siteAnchored = placements.any { it.siteAnchored },
        )
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    override fun hitTest(screenX: Float, screenY: Float): String? {
        // A tap against a stale pose is not an answer. Accepting it would attribute a mis-hit to the
        // worker's judgement rather than to the tracking gap that actually caused it.
        if (!coach.acceptsInput()) return null
        val hit = projection.nearestWithin(placements, screenX, screenY, TAP_RADIUS_PX)
        return hit?.marker?.optionId
    }

    override fun beginDwell(optionId: String, dwellMs: Long) {
        dwellOptionId = optionId
        dwellRequiredMs = dwellMs.coerceAtLeast(1L)
        dwellStartedAtMs = 0L
    }

    override fun cancelDwell() {
        dwellOptionId = null
        dwellStartedAtMs = 0L
        _state.value = _state.value.copy(dwellProgress = 0f)
    }

    override suspend fun placeSiteAnchor(siteId: String, targetKey: String, label: String?): Boolean {
        val activeSession = session ?: return false
        val frameCamera = lastCameraPose ?: return false

        val anchor = try {
            // Placed two metres ahead of where the supervisor is standing and looking, rather than on a
            // detected plane. Plane detection is unreliable on the matte, dusty, uniformly grey surfaces
            // that make up an underground roadway, and requiring a plane hit would make scanning fail
            // exactly where it matters most.
            activeSession.createAnchor(frameCamera.compose(anchorOffsetPose()))
        } catch (e: Exception) {
            Log.w(TAG, "could not create an anchor", e)
            return false
        }

        val result = anchorResolver.host(activeSession, anchor)
        val cloudId = (result as? AnchorResolver.HostResult.Hosted)?.cloudAnchorId

        liveAnchors[targetKey] = anchor
        val pose = anchor.pose
        placer.setResolvedAnchor(targetKey, pose.tx(), pose.ty(), pose.tz())

        siteRepository.saveAnchor(
            siteId = siteId,
            targetKey = targetKey,
            cloudAnchorId = cloudId,
            label = label,
        )

        if (result is AnchorResolver.HostResult.SessionScoped) {
            Log.i(TAG, "anchor for $targetKey is session-scoped: ${result.reason}")
        }
        placements = placer.place(markers)
        publishPlacements()
        return true
    }

    override fun isSiteAnchored(): Boolean =
        placer.resolvedSemanticCount() >= SiteRepository.MIN_SEMANTIC_ANCHORS_FOR_SCANNED

    override fun setTorch(enabled: Boolean) {
        val activeSession = session ?: return
        torchOn = enabled
        try {
            val config = activeSession.config
            config.flashMode = if (enabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
            activeSession.configure(config)
        } catch (e: Exception) {
            // Not every device exposes torch through ARCore. The coach simply stops offering it.
            Log.i(TAG, "torch is not available through ARCore on this device", e)
            torchOn = false
        }
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    // -----------------------------------------------------------------------
    // GL thread
    // -----------------------------------------------------------------------

    @Volatile
    private var lastCameraPose: com.google.ar.core.Pose? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        glReady = background.createOnGlThread()
        if (!glReady) {
            Log.e(TAG, "camera background renderer failed to initialise")
            publishFailure("ar_error_gl_unavailable")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        projection.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val activeSession = session ?: return
        if (!glReady) return

        try {
            activeSession.setCameraTextureName(background.cameraTextureId)
            activeSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)

            val frame = activeSession.update()
            background.onFrame(frame)
            background.draw()

            val camera = frame.camera
            val quality = when (camera.trackingState) {
                TrackingState.TRACKING -> ArTrackingQuality.TRACKING
                TrackingState.PAUSED -> ArTrackingQuality.LIMITED
                TrackingState.STOPPED -> ArTrackingQuality.LOST
                else -> ArTrackingQuality.INITIALISING
            }

            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE_M, FAR_PLANE_M)
                projection.update(viewMatrix, projectionMatrix)
                lastCameraPose = camera.pose
                refreshAnchoredPlacements()
            }

            val advice = coach.onFrame(quality, camera.trackingFailureReason.toHint())
            updateDwell()

            val reticle = projection.underReticle(placements, RETICLE_RADIUS_PX)?.marker?.optionId

            _state.value = _state.value.copy(
                quality = quality,
                hint = advice.hint,
                projected = projectAll(),
                reticleOptionId = reticle,
                failureMessageKey = if (advice.recommendFallback) "ar_error_tracking_gave_up" else null,
            )
        } catch (e: SessionPausedException) {
            // Ordinary: the activity paused between the null check and the update.
            projection.invalidate()
        } catch (e: CameraNotAvailableException) {
            publishFailure("ar_error_camera_unavailable")
        } catch (e: Exception) {
            Log.w(TAG, "AR frame failed", e)
            projection.invalidate()
        }
    }

    /**
     * Re-reads resolved anchor poses.
     *
     * ARCore refines an anchor's pose as it learns more about the room, so a pose captured once drifts
     * away from the real object. Reading it every tracked frame is what keeps the "primary exit" marker
     * on the actual doorway instead of near it.
     */
    private fun refreshAnchoredPlacements() {
        if (liveAnchors.isEmpty()) return
        var changed = false
        for ((targetKey, anchor) in liveAnchors) {
            if (anchor.trackingState != TrackingState.TRACKING) continue
            val pose = anchor.pose
            placer.setResolvedAnchor(targetKey, pose.tx(), pose.ty(), pose.tz())
            changed = true
        }
        if (changed && markers.isNotEmpty()) {
            placements = placer.place(markers)
        }
    }

    private fun updateDwell() {
        val target = dwellOptionId ?: return
        val underReticle = projection.underReticle(placements, RETICLE_RADIUS_PX)?.marker?.optionId
        val now = monotonic.elapsedMillis()

        if (underReticle != target) {
            // Looking away resets the dwell rather than pausing it. A dwell step is asking the worker to
            // hold their attention on one thing; crediting accumulated glances would not test that.
            dwellStartedAtMs = 0L
            _state.value = _state.value.copy(dwellProgress = 0f)
            return
        }

        if (dwellStartedAtMs == 0L) dwellStartedAtMs = now
        val progress = ((now - dwellStartedAtMs).toFloat() / dwellRequiredMs).coerceIn(0f, 1f)
        _state.value = _state.value.copy(dwellProgress = progress)
    }

    /** True once the current dwell has completed. Polled by the drill. */
    fun dwellComplete(): Boolean = _state.value.dwellProgress >= 1f && dwellOptionId != null

    /** Projects the current placements into screen space for the Compose overlay. */
    private fun projectAll(): List<ProjectedMarker> = placements.map { placement ->
        val point = projection.project(placement.x, placement.y, placement.z)
        ProjectedMarker(
            optionId = placement.marker.optionId,
            pictogram = placement.marker.pictogram,
            label = placement.marker.label,
            screenX = point.x,
            screenY = point.y,
            visible = point.visible,
            distanceMetres = point.distanceMetres,
            offScreenDirection = point.offScreenDirection,
            siteAnchored = placement.siteAnchored,
        )
    }

    private fun anchorOffsetPose(): com.google.ar.core.Pose =
        com.google.ar.core.Pose.makeTranslation(0f, 0f, -ANCHOR_PLACEMENT_DISTANCE_M)

    private fun TrackingFailureReason.toHint(): TrackingHint = when (this) {
        TrackingFailureReason.NONE -> TrackingHint.NONE
        TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingHint.TOO_DARK
        TrackingFailureReason.EXCESSIVE_MOTION -> TrackingHint.TOO_FAST
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingHint.NOT_ENOUGH_TEXTURE
        TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingHint.CAMERA_BLOCKED
        TrackingFailureReason.BAD_STATE -> TrackingHint.RECOVERING
        else -> TrackingHint.LOOK_AROUND_MORE
    }

    private companion object {
        const val TAG = "ArCoreController"

        const val NEAR_PLANE_M = 0.1f
        const val FAR_PLANE_M = 100f

        /**
         * Generous tap radius, in pixels.
         *
         * Workers wear gloves. Requiring a precise hit would record glove slip as a wrong decision, which
         * is measurement error presented as a training result.
         */
        const val TAP_RADIUS_PX = 160f

        const val RETICLE_RADIUS_PX = 220f

        const val ANCHOR_PLACEMENT_DISTANCE_M = 2.0f
    }
}
