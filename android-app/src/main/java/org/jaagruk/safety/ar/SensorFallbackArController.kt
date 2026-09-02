package org.jaagruk.safety.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource
import kotlin.math.abs

/**
 * The no-ARCore path: camera preview plus rotation sensors.
 *
 * About a third of mid-range Android stock in this market is not ARCore certified, and it is
 * disproportionately what a contract worker in Dhanbad actually carries. Requiring ARCore would make
 * the app invisible on Play to exactly the people the problem statement is about, so this exists and is
 * a first-class path rather than a stub.
 *
 * **What it does.** Markers sit on a virtual sphere around the worker. The rotation vector sensor gives
 * device orientation, so turning the phone genuinely looks around the scene: "the exit is behind you"
 * still means turning around, and the spatial habit the drill is building still forms.
 *
 * **What it cannot do, and does not pretend to.** There is no world tracking, so walking does not move
 * the scene — only turning does. There are no planes and no persistent anchors, so a site scan is not
 * available and [placeSiteAnchor] returns false rather than storing something that would not survive
 * the worker turning around. The presentation is reported as `SENSOR_FALLBACK`, which is signed into the
 * certificate, so an inspector can see exactly which fidelity the run was assessed at.
 */
class SensorFallbackArController(
    private val context: Context,
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
) : ArController, SensorEventListener {

    private val projection = Projection()
    private val placer = ScenePlacer()
    private val coach = TrackingCoach(monotonic)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(16)
    private val remappedMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    private val _state = MutableStateFlow(
        ArState(presentation = ArPresentation.SENSOR_FALLBACK),
    )
    override val state: StateFlow<ArState> = _state.asStateFlow()

    override val capability: ArAvailability.Capability = ArAvailability.Capability.SENSOR_FALLBACK

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var lifecycleOwner: LifecycleOwner? = null

    @Volatile
    private var markers: List<SceneMarker> = emptyList()

    @Volatile
    private var placements: List<MarkerPlacement> = emptyList()

    @Volatile
    private var displayRotation: Int = Surface.ROTATION_0

    private var lastSensorEventAtMs: Long = 0L
    private var lastAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    private var dwellOptionId: String? = null
    private var dwellRequiredMs: Long = 0L
    private var dwellStartedAtMs: Long = 0L

    init {
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.setIdentityM(projectionMatrix, 0)
    }

    /** Called by [ArSurfaceHost] once Compose has a preview surface. */
    fun bindPreview(view: PreviewView) {
        previewView = view
        // COMPATIBLE, not PERFORMANCE: PERFORMANCE uses a SurfaceView, which on several older devices
        // punches a hole through the Compose overlay that draws the markers.
        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        view.scaleType = PreviewView.ScaleType.FILL_CENTER
        startCamera()
    }

    fun setViewport(width: Int, height: Int) {
        projection.setViewport(width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
        // A nominal 62-degree vertical field of view. Not measured from the camera, and it does not need
        // to be: this path has no world registration, so markers only have to be self-consistent for
        // "which direction is that" to be a fair question.
        Matrix.perspectiveM(projectionMatrix, 0, NOMINAL_FOV_DEGREES, aspect, NEAR_M, FAR_M)
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
        owner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    registerSensor()
                    startCamera()
                }

                override fun onPause(owner: LifecycleOwner) {
                    sensorManager?.unregisterListener(this@SensorFallbackArController)
                }

                override fun onDestroy(owner: LifecycleOwner) = detach()
            },
        )
        registerSensor()
    }

    override fun detach() {
        sensorManager?.unregisterListener(this)
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        cameraControl = null
        previewView = null
        lifecycleOwner = null
        coach.reset()
        projection.invalidate()
    }

    private fun registerSensor() {
        val sensor = rotationSensor
        if (sensor == null) {
            // No rotation vector at all. Rare but real on the cheapest handsets, and it means this path
            // cannot work either — the caller drops to the flat pictogram drill.
            _state.value = _state.value.copy(
                quality = ArTrackingQuality.UNAVAILABLE,
                failureMessageKey = "ar_error_no_orientation_sensor",
            )
            return
        }
        sensorManager?.registerListener(this, sensor, SENSOR_PERIOD_US)
    }

    private fun startCamera() {
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                    cameraControl = camera.cameraControl
                    _state.value = _state.value.copy(failureMessageKey = null)
                } catch (e: Exception) {
                    // Permission revoked mid-session, camera held by another app, or a hardware fault.
                    // All three end the same way: tell the worker, and let the caller fall back to 2D.
                    Log.w(TAG, "camera preview could not start", e)
                    _state.value = _state.value.copy(
                        quality = ArTrackingQuality.UNAVAILABLE,
                        failureMessageKey = "ar_error_camera_unavailable",
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // -----------------------------------------------------------------------
    // Sensors
    // -----------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR
        ) {
            return
        }

        lastSensorEventAtMs = monotonic.elapsedMillis()
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // The rotation matrix is expressed against the device's natural orientation. Remapping it for the
        // current display rotation is what keeps the scene upright when the phone is held sideways —
        // which is how anyone holds a phone they are pointing at something.
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)

        // The remapped matrix rotates device coordinates into the world frame. The view matrix is its
        // inverse, and for an orthonormal rotation that is just the transpose — cheaper and numerically
        // better behaved than a general inverse.
        Matrix.transposeM(viewMatrix, 0, remappedMatrix, 0)

        projection.update(viewMatrix, projectionMatrix)
        onFrameTick()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        lastAccuracy = accuracy
    }

    /**
     * Publishes state from the latest sensor sample.
     *
     * Driven by the sensor rather than by a render loop. There is no GL thread on this path, and polling
     * would either waste power or lag the worker's head movement.
     */
    private fun onFrameTick() {
        val staleness = monotonic.elapsedMillis() - lastSensorEventAtMs
        val quality = when {
            rotationSensor == null -> ArTrackingQuality.UNAVAILABLE
            staleness > SENSOR_STALE_MS -> ArTrackingQuality.LOST
            lastAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE -> ArTrackingQuality.LIMITED
            lastAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW -> ArTrackingQuality.LIMITED
            else -> ArTrackingQuality.TRACKING
        }

        val hint = if (quality == ArTrackingQuality.LIMITED) {
            // The usual cause is a magnetometer disturbed by the steel everything underground is made
            // of. "Move away from large metal objects" is the honest prompt, and it is why this path
            // prefers the game rotation vector where the device offers one.
            TrackingHint.LOOK_AROUND_MORE
        } else {
            TrackingHint.NONE
        }

        val advice = coach.onFrame(quality, hint)
        updateDwell()

        val reticle = projection.underReticle(placements, RETICLE_RADIUS_PX)?.marker?.optionId

        _state.value = _state.value.copy(
            quality = quality,
            hint = advice.hint,
            presentation = ArPresentation.SENSOR_FALLBACK,
            placements = placements,
            projected = projectAll(),
            reticleOptionId = reticle,
            siteAnchored = false,
        )
    }

    /** Projects placements into screen space for the Compose marker overlay. */
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
            // Never true on this path: there is no world registration, so nothing is pinned to anything.
            siteAnchored = false,
        )
    }

    // -----------------------------------------------------------------------
    // Markers and input
    // -----------------------------------------------------------------------

    override fun setMarkers(siteId: String, markers: List<SceneMarker>) {
        this.markers = markers
        placements = placer.place(markers)
        _state.value = _state.value.copy(
            placements = placements,
            presentation = ArPresentation.SENSOR_FALLBACK,
            siteAnchored = false,
        )
    }

    override fun clearMarkers() {
        markers = emptyList()
        placements = emptyList()
        dwellOptionId = null
        _state.value = _state.value.copy(placements = emptyList(), dwellProgress = 0f)
    }

    override fun hitTest(screenX: Float, screenY: Float): String? {
        if (!coach.acceptsInput()) return null
        return projection.nearestWithin(placements, screenX, screenY, TAP_RADIUS_PX)?.marker?.optionId
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

    private fun updateDwell() {
        val target = dwellOptionId ?: return
        val underReticle = projection.underReticle(placements, RETICLE_RADIUS_PX)?.marker?.optionId
        val now = monotonic.elapsedMillis()

        if (underReticle != target) {
            dwellStartedAtMs = 0L
            _state.value = _state.value.copy(dwellProgress = 0f)
            return
        }
        if (dwellStartedAtMs == 0L) dwellStartedAtMs = now
        val progress = ((now - dwellStartedAtMs).toFloat() / dwellRequiredMs).coerceIn(0f, 1f)
        _state.value = _state.value.copy(dwellProgress = progress)
    }

    /**
     * Always false.
     *
     * Not a limitation being hidden — a sensor-only anchor has no world registration, so it would not
     * survive the worker turning around, let alone a restart. Returning true and storing it would put a
     * row in the database claiming this site is scanned when nothing is actually pinned to anything.
     */
    override suspend fun placeSiteAnchor(siteId: String, targetKey: String, label: String?): Boolean =
        false

    override fun isSiteAnchored(): Boolean = false

    override fun setTorch(enabled: Boolean) {
        try {
            cameraControl?.enableTorch(enabled)
        } catch (e: Exception) {
            Log.i(TAG, "torch unavailable on this device", e)
        }
    }

    /** True once the current dwell has completed. */
    fun dwellComplete(): Boolean = _state.value.dwellProgress >= 1f && dwellOptionId != null

    private companion object {
        const val TAG = "SensorFallbackAr"

        /** 60 Hz. Fast enough that head movement feels attached, slow enough not to cook the battery. */
        const val SENSOR_PERIOD_US = 16_000

        /** No sample for this long means the sensor has stopped delivering. */
        const val SENSOR_STALE_MS = 500L

        const val NOMINAL_FOV_DEGREES = 62f
        const val NEAR_M = 0.1f
        const val FAR_M = 100f

        const val TAP_RADIUS_PX = 160f
        const val RETICLE_RADIUS_PX = 220f
    }
}
