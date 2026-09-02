package org.jaagruk.safety.ui.components

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders certificate QR text.
 *
 * Two parameters carry the field requirements, and both are deliberate:
 *
 *  * **Error correction level Q (25%).** The default M (15%) is fine on a screen. These codes get printed,
 *    laminated, carried in a pocket for a year and scanned in a mine gatehouse — Q survives a scratch
 *    across a quarter of the symbol. The payload is 216 characters, so the extra redundancy costs one
 *    version step and no scannability.
 *  * **A real quiet zone.** Four modules, as the spec requires. Renderers that omit it produce codes that
 *    scan on the phone that made them and fail against a wall-mounted reader.
 */
@Composable
fun QrImage(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
) {
    val bitmap = remember(text) { encodeQr(text) }

    Box(
        modifier = modifier
            // White field always, regardless of theme. A dark-mode QR is a QR that does not scan on half
            // the readers in existence.
            .background(Color.White)
            .padding(12.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.size(size),
                // None: any smoothing blurs module edges and costs scan reliability at a distance.
                contentScale = ContentScale.FillBounds,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
        }
    }
}

/**
 * Encodes to a 1-bit-style bitmap.
 *
 * Returns null rather than throwing. A certificate whose QR failed to render must still show its text
 * form and its verification URL, because those are what let an inspector check it by hand.
 */
private fun encodeQr(text: String): ImageBitmap? = try {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        MODULE_PIXELS,
        MODULE_PIXELS,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            pixels[row + x] = if (matrix.get(x, y)) BLACK else WHITE
        }
    }

    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
        .asImageBitmap()
} catch (e: Exception) {
    Log.w(TAG, "could not encode the certificate QR", e)
    null
}

private const val TAG = "QrImage"

/**
 * Requested pixel size handed to ZXing.
 *
 * ZXing rounds up to a whole number of modules, so this is a target rather than an exact size. 640 gives
 * at least 8 pixels per module for a 216-character payload, which is comfortably above the 4-pixel floor
 * where camera scanning starts to struggle.
 */
private const val MODULE_PIXELS = 640

private const val QUIET_ZONE_MODULES = 4
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
