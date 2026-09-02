package org.jaagruk.safety.ui.verify

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera QR scanner for certificate verification.
 *
 * Restricted to `FORMAT_QR_CODE` on purpose. It roughly halves the per-frame cost, and more importantly it
 * stops the scanner latching onto a barcode on a nearby toolbox and reporting it as an unrecognised
 * certificate — which reads to an inspector like the app is broken.
 *
 * Recognition is debounced by payload, not by time. An inspector holding a card steady produces the same
 * string thirty times a second, and re-verifying each one would flicker the verdict. The same payload is
 * accepted once until a different one appears, which also lets a second card be scanned immediately.
 */
/**
 * `ImageProxy.getImage` is opt-in because the returned `Image` is only valid until the proxy is closed. That
 * contract is honoured here: the image goes straight to ML Kit, and the proxy is closed in a completion
 * listener rather than immediately — closing it while ML Kit still holds the buffer stalls the analyser after
 * a handful of frames, which presents as a scanner that works for a second and then stops.
 */
@SuppressLint("MissingPermission")
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun QrScanner(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        var lastPayload: String? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener(
            {
                val provider = try {
                    providerFuture.get()
                } catch (e: Exception) {
                    Log.w(TAG, "camera provider unavailable", e)
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build(),
                    )
                    // Latest frame only. A backlog would make the scanner feel laggy and would report a
                    // verdict for a card the inspector has already moved away from.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        proxy.imageInfo.rotationDegrees,
                    )
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val payload = barcodes.firstNotNullOfOrNull { it.rawValue }
                            if (payload != null && payload != lastPayload) {
                                lastPayload = payload
                                ContextCompat.getMainExecutor(context).execute {
                                    onQrDetected(payload)
                                }
                            }
                        }
                        .addOnFailureListener { error ->
                            Log.d(TAG, "barcode pass failed: ${error.message}")
                        }
                        // Closing in a completion listener rather than immediately: closing the proxy while
                        // ML Kit still holds the buffer stalls the analyser after a few frames.
                        .addOnCompleteListener { proxy.close() }
                }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "could not bind the scanner camera", e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}

private const val TAG = "QrScanner"
