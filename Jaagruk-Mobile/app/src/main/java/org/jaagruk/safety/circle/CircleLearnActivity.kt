package org.jaagruk.safety.circle

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.jaagruk.safety.ui.theme.JaagrukTheme

/**
 * CircleLearnActivity — FALLBACK ONLY.
 *
 * Used when SYSTEM_ALERT_WINDOW permission is not granted and the overlay
 * pipeline cannot run. In that case JaagrukOverlayService captures the
 * screenshot, stores it in [JaagrukOverlayService.pendingScreenshot], and
 * launches this transparent activity.
 *
 * Normal flow: everything runs entirely inside JaagrukOverlayService via
 * WindowManager overlays and this activity is never launched.
 */
class CircleLearnActivity : ComponentActivity() {

    companion object { private const val TAG = "CircleLearnActivity" }

    private val vm: CircleLearnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screenshot = JaagrukOverlayService.pendingScreenshot
        if (screenshot == null || screenshot.width <= 1) {
            Log.e(TAG, "No screenshot available")
            showErrorAndFinish("Could not capture screen. Please try again.")
            return
        }

        showSelectionView(screenshot)
    }

    private fun showSelectionView(screenshot: Bitmap) {
        val selectionView = RegionSelectionView(this).apply {
            setScreenshot(screenshot)
            onCancel = { finish() }
            onRegionSelected = { region ->
                setContentView(FrameLayout(this@CircleLearnActivity))
                vm.processRegion(screenshot, region)
                showBottomSheet()
            }
        }
        setContentView(selectionView)
    }

    private fun showBottomSheet() {
        setContent {
            JaagrukTheme(darkTheme = true) {
                CircleLearnBottomSheetHost(
                    vm          = vm,
                    onDismiss   = { finish() },
                    onOpenInApp = null  // already inside the app
                )
            }
        }
    }

    private fun showErrorAndFinish(msg: String) {
        setContent {
            JaagrukTheme(darkTheme = true) {
                CircleErrorScreen(message = msg, onDismiss = { finish() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear the static bitmap; don't recycle — the bitmap may still be in use
        // by the VM's OCR pipeline. GC will collect it.
        JaagrukOverlayService.pendingScreenshot = null
    }
}
