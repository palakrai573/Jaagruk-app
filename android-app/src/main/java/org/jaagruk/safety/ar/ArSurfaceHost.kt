package org.jaagruk.safety.ar

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import org.jaagruk.safety.data.repo.SiteRepository

/**
 * Builds the right [ArController] for this handset.
 *
 * The ladder is walked once per drill rather than cached for the app's lifetime, because capability is
 * not static: ARCore can finish installing between two drills, and a camera permission can be revoked
 * from the notification shade mid-shift. Re-probing costs a few milliseconds and means a worker gets the
 * best path currently available instead of the one that was available at launch.
 */
class ArControllerFactory(
    private val context: Context,
    private val siteRepository: SiteRepository,
    private val anchorResolver: AnchorResolver,
    private val scope: CoroutineScope,
) {

    /**
     * @param preferFlat set when the worker has explicitly turned AR off. Honoured without argument:
     *   somebody who has just come off a shift underground and wants a flat drill is not making a
     *   mistake, and the certificate records the presentation either way.
     */
    fun create(preferFlat: Boolean = false): ArController {
        if (preferFlat) return PictogramArController()

        return when (ArAvailability.probe(context)) {
            ArAvailability.Capability.ARCORE_READY ->
                ArCoreController(context, siteRepository, anchorResolver, scope)

            // Needs an install. The sensor path runs the drill now; the install prompt is offered
            // separately rather than blocking a worker who is standing there ready to train.
            ArAvailability.Capability.ARCORE_NEEDS_INSTALL ->
                SensorFallbackArController(context)

            ArAvailability.Capability.SENSOR_FALLBACK ->
                SensorFallbackArController(context)

            ArAvailability.Capability.PICTOGRAM_ONLY -> PictogramArController()
        }
    }
}

/**
 * Hosts whichever camera surface the controller needs, underneath the Compose marker overlay.
 *
 * `GLSurfaceView` for ARCore, `PreviewView` for the sensor fallback, nothing at all for the flat drill.
 * The caller draws markers on top as ordinary composables, which is what keeps content descriptions,
 * Devanagari and Ol Chiki shaping, and touch target sizing working without a custom text pipeline.
 */
@Composable
fun ArSurfaceHost(
    controller: ArController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The controller outlives recomposition, so attaching in a DisposableEffect keyed on it is what
    // prevents a rotation from registering a second lifecycle observer on the same session.
    DisposableEffect(controller) {
        controller.attach(lifecycleOwner)
        onDispose { controller.detach() }
    }

    when (controller) {
        is ArCoreController -> {
            val glView = remember(controller) {
                GLSurfaceView(context).also(controller::bindSurface)
            }
            LaunchedEffect(controller) {
                controller.setDisplayRotation(currentDisplayRotation(context))
            }
            AndroidView(
                factory = { glView },
                modifier = modifier
                    .fillMaxSize()
                    .onSizeChanged { controller.setDisplayRotation(currentDisplayRotation(context)) },
            )
        }

        is SensorFallbackArController -> {
            val previewView = remember(controller) {
                PreviewView(context).also(controller::bindPreview)
            }
            AndroidView(
                factory = { previewView },
                modifier = modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        controller.setViewport(size.width, size.height)
                        controller.setDisplayRotation(currentDisplayRotation(context))
                    },
            )
        }

        // Flat drill: no camera surface at all. An empty Box keeps the layout identical so the marker
        // overlay above it does not have to know which path it is on.
        else -> Box(modifier = modifier.fillMaxSize())
    }
}

/**
 * Current display rotation.
 *
 * Read from the display rather than from configuration orientation. ARCore needs the rotation the
 * *surface* is at, and on a device with a rotated panel — some rugged handsets have exactly this — the
 * two disagree, which shows up as a camera feed 90 degrees out from the world.
 */
private fun currentDisplayRotation(context: Context): Int {
    val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay
    }
    return display?.rotation ?: android.view.Surface.ROTATION_0
}
