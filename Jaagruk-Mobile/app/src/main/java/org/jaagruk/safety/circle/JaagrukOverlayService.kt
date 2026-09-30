package org.jaagruk.safety.circle

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.jaagruk.safety.MainActivity
import org.jaagruk.safety.ui.theme.JaagrukTheme
import kotlinx.coroutines.*
import org.jaagruk.safety.R

/**
 * JaagrukOverlayService
 *
 * Owns the entire Circle Learn flow via WindowManager overlays.
 *
 * KEY FIX: MediaProjection is created ONCE when the service starts and
 * reused for every capture. Only VirtualDisplay is created/released per tap.
 * The projection is only stopped in onDestroy() or ACTION_STOP.
 *
 * Pipeline per tap:
 *   Bubble tap
 *   → captureScreen()          creates VirtualDisplay from existing mp, grabs frame, releases VD
 *   → attachSelectionOverlay() RegionSelectionView on WindowManager
 *   → vm.processRegion()       OCR
 *   → attachBottomSheetOverlay() CircleLearnBottomSheetHost on WindowManager
 *   → onDismiss → removeAllOverlays(), restore bubble  ← bubble stays, ready for next tap
 */
class JaagrukOverlayService : Service() {

    companion object {
        const val ACTION_START       = "org.jaagruk.safety.circle.START"
        const val ACTION_STOP        = "org.jaagruk.safety.circle.STOP"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        private const val NOTIF_ID   = 9001
        private const val CHANNEL_ID = "jaagruk_overlay"
        private const val TAG        = "JaagrukOverlayService"

        @Volatile var pendingScreenshot: Bitmap? = null
    }

    // ── WindowManager ──────────────────────────────────────────────────────────
    private lateinit var wm: WindowManager
    private val overlayType
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ── Overlay views ──────────────────────────────────────────────────────────
    private var bubble:           FloatingBubbleView? = null
    private var selectionOverlay: RegionSelectionView? = null
    private var sheetHost:        OverlayComposeHost? = null

    // ── MediaProjection — created ONCE, reused forever until service stops ─────
    // This is the fix: we never call mp.stop() after a capture.
    // Only VirtualDisplay is created/released per capture.
    private var mediaProjection: MediaProjection? = null
    private var projectionResultCode: Int     = Activity.RESULT_CANCELED
    private var projectionResultData: Intent? = null

    // ── ViewModel (service-scoped) ─────────────────────────────────────────────
    private val vmStore = ViewModelStore()
    private lateinit var vm: CircleLearnViewModel

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var screenWidth  = 0
    private var screenHeight = 0
    private var screenDpi    = 0
    private var currentScreenshot: Bitmap? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val dm = resources.displayMetrics
        screenWidth  = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDpi    = dm.densityDpi

        vm = ViewModelProvider(
            vmStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[CircleLearnViewModel::class.java]
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                projectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                projectionResultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                // Create MediaProjection ONCE here — reuse for all captures
                initMediaProjection()
                showBubble()
                Log.i(TAG, "Circle Learn ready")
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed — releasing MediaProjection")
        scope.cancel()
        hideBubble()
        removeAllOverlays()
        currentScreenshot?.recycle(); currentScreenshot = null
        releaseMediaProjection()
        vmStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── MediaProjection init (once per service session) ────────────────────────

    private fun initMediaProjection() {
        val data = projectionResultData
        if (projectionResultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "No projection consent — cannot init MediaProjection")
            return
        }
        if (mediaProjection != null) {
            Log.i(TAG, "MediaProjection reused")
            return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(projectionResultCode, data)
        if (mediaProjection != null) {
            Log.i(TAG, "MediaProjection created successfully")
            // Register callback so we know if system kills the projection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection stopped by system")
                        mediaProjection = null
                    }
                }, Handler(Looper.getMainLooper()))
            }
        } else {
            Log.e(TAG, "getMediaProjection returned null")
        }
    }

    private fun releaseMediaProjection() {
        mediaProjection?.stop()
        mediaProjection = null
    }

    // ── Bubble ─────────────────────────────────────────────────────────────────

    private fun showBubble() {
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            Log.w(TAG, "No overlay permission — bubble not shown"); return
        }
        if (bubble?.isShowing() == true) {
            Log.i(TAG, "Bubble reused")
            return
        }
        bubble = FloatingBubbleView(this) { onBubbleTapped() }
        bubble?.show()
        Log.i(TAG, "Bubble attached")
    }

    private fun hideBubble() { bubble?.hide(); bubble = null }

    /** Ensure bubble is present — re-attaches if it was removed unexpectedly. */
    private fun ensureBubble() {
        if (bubble?.isShowing() == true) {
            Log.i(TAG, "Bubble reused")
            return
        }
        Log.w(TAG, "Bubble missing — re-attaching")
        bubble = FloatingBubbleView(this) { onBubbleTapped() }
        bubble?.show()
        Log.i(TAG, "Bubble attached")
    }

    // ── Step 1: Bubble tapped ──────────────────────────────────────────────────

    private fun onBubbleTapped() {
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            fallbackToActivity(); return
        }
        // Hide bubble so it doesn't appear in the screenshot
        bubble?.hide()

        scope.launch {
            delay(120) // let bubble hide animation finish before capturing
            captureScreen { bitmap ->
                currentScreenshot?.recycle()
                currentScreenshot = bitmap
                attachSelectionOverlay(bitmap)
            }
        }
    }

    // ── Step 2: Selection overlay ──────────────────────────────────────────────

    private fun attachSelectionOverlay(screenshot: Bitmap) {
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val view = RegionSelectionView(this).apply {
            setScreenshot(screenshot)
            onCancel = {
                scope.launch(Dispatchers.Main) {
                    removeSelectionOverlay()
                    // Bubble stays alive — user cancelled, ready for next tap
                    ensureBubble()
                }
            }
            onRegionSelected = { region ->
                scope.launch(Dispatchers.Main) {
                    removeSelectionOverlay()
                    vm.processRegion(screenshot, region)
                    attachBottomSheetOverlay()
                }
            }
        }

        selectionOverlay = view
        runCatching { wm.addView(view, params) }
            .onFailure { Log.e(TAG, "Failed to add selection overlay", it) }
    }

    private fun removeSelectionOverlay() {
        selectionOverlay?.let { runCatching { wm.removeView(it) } }
        selectionOverlay = null
    }

    // ── Step 3: Bottom-sheet overlay ──────────────────────────────────────────

    private fun attachBottomSheetOverlay() {
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val host = OverlayComposeHost(this) {
            JaagrukTheme(darkTheme = true) {
                CircleLearnBottomSheetHost(
                    vm          = vm,
                    onDismiss   = {
                        scope.launch(Dispatchers.Main) {
                            removeBottomSheetOverlay()
                            vm.reset()
                            currentScreenshot?.recycle(); currentScreenshot = null
                            Log.i(TAG, "Activity closed — returning control to bubble")
                            // Bubble re-attach: service stays alive, bubble ready for next tap
                            ensureBubble()
                        }
                    },
                    onOpenInApp = { route ->
                        scope.launch(Dispatchers.Main) {
                            removeAllOverlays()
                            vm.reset()
                            // DO NOT stop service or destroy bubble here
                            // Bubble stays alive in background while user views app
                            launchMainActivity(route)
                        }
                    }
                )
            }
        }

        sheetHost = host
        runCatching {
            wm.addView(host.view, params)
            host.start()
        }.onFailure { Log.e(TAG, "Failed to add bottom sheet overlay", it) }
    }

    private fun removeBottomSheetOverlay() {
        sheetHost?.let { it.stop(); runCatching { wm.removeView(it.view) } }
        sheetHost = null
    }

    private fun removeAllOverlays() {
        removeSelectionOverlay()
        removeBottomSheetOverlay()
    }

    // ── Fallback: launch CircleLearnActivity when overlay permission missing ───

    private fun fallbackToActivity() {
        Log.w(TAG, "Overlay permission unavailable — falling back to CircleLearnActivity")
        captureScreen { bitmap ->
            pendingScreenshot?.recycle()
            pendingScreenshot = bitmap
            val intent = Intent(this, CircleLearnActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    // ── Open the Jaagruk app (explicit user request only) ────────────────────────

    private fun launchMainActivity(route: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (route != null) putExtra("route", route)
        }
        startActivity(intent)
    }

    // ── Screen capture ─────────────────────────────────────────────────────────
    // Uses the EXISTING mediaProjection — never re-creates it.
    // Only VirtualDisplay is created and released per capture.

    private fun captureScreen(onCaptured: (Bitmap) -> Unit) {
        val mp = mediaProjection
        if (mp == null) {
            Log.e(TAG, "MediaProjection not available — attempting re-init")
            // Try to recover by re-initialising (handles system-killed projection edge case)
            initMediaProjection()
            val mpRetry = mediaProjection
            if (mpRetry == null) {
                Log.e(TAG, "Re-init failed — cannot capture")
                vm.setError("Screen capture not available. Please restart Circle Learn.")
                attachBottomSheetOverlay()
                ensureBubble()
                return
            }
            doCaptureWithProjection(mpRetry, onCaptured)
            return
        }
        Log.i(TAG, "MediaProjection reused")
        doCaptureWithProjection(mp, onCaptured)
    }

    private fun doCaptureWithProjection(mp: MediaProjection, onCaptured: (Bitmap) -> Unit) {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        val vd: VirtualDisplay? = mp.createVirtualDisplay(
            "CircleLearn", screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        if (vd == null) {
            reader.close()
            Log.e(TAG, "createVirtualDisplay returned null")
            vm.setError("Screen capture failed. Please try again.")
            attachBottomSheetOverlay()
            ensureBubble()
            return
        }

        scope.launch(Dispatchers.IO) {
            delay(300) // allow VirtualDisplay to render one frame
            val image = reader.acquireLatestImage()
            try {
                if (image == null) {
                    Log.w(TAG, "No image acquired — returning blank bitmap")
                    withContext(Dispatchers.Main) {
                        onCaptured(Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888))
                    }
                    return@launch
                }
                val planes      = image.planes
                val buffer      = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride   = planes[0].rowStride
                val rowPadding  = rowStride - pixelStride * screenWidth

                val bmp = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                image.close()

                val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                if (cropped != bmp) bmp.recycle()

                Log.i(TAG, "Capture complete — ${cropped.width}x${cropped.height}")
                withContext(Dispatchers.Main) { onCaptured(cropped) }
            } finally {
                // Only release VirtualDisplay — NEVER call mp.stop() here
                image?.close()
                vd.release()
                reader.close()
                Log.i(TAG, "VirtualDisplay released — MediaProjection still alive")
            }
        }
    }

    // ── WindowManager helpers ──────────────────────────────────────────────────

    private fun fullScreenParams(flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        0, 0,
        overlayType,
        flags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.explain_notification_channel), NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Circle Learn overlay service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, JaagrukOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.explain_notification_title))
            .setContentText("Tap the bubble to circle anything on screen and have it explained")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }
}
