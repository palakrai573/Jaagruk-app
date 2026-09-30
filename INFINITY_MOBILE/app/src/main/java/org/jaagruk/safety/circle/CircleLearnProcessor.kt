package org.jaagruk.safety.circle

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CircleLearnProcessor
 *
 * Adapter between the Circle Learn overlay and the existing OCR infrastructure.
 *
 * Responsibilities:
 *   1. Crop the screen bitmap to the user-selected region
 *   2. Run ML Kit OCR on the cropped bitmap (same recognizer config as OcrTextExtractor)
 *   3. Clean and cap the text (same rules as OcrTextExtractor)
 *   4. Detect content type via ContentTypeDetector
 *   5. Return result — recycle bitmaps
 *
 * Does NOT call OcrTextExtractor directly because that class accepts a Uri, not a Bitmap.
 * Uses the same ML Kit recognizer config so behavior is identical.
 */
class CircleLearnProcessor {

    companion object {
        private const val TAG      = "CircleLearnProcessor"
        private const val MAX_CHARS = 800  // same budget as OcrTextExtractor
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class ProcessResult(
        val text      : String,
        val detection : ContentTypeDetector.DetectionResult,
        val truncated : Boolean
    )

    /**
     * Crop [screenBitmap] to [region], run OCR, detect content type.
     * Recycles the cropped bitmap after OCR. Never recycles [screenBitmap] — caller owns it.
     */
    suspend fun process(screenBitmap: Bitmap, region: Rect): Result<ProcessResult> =
        withContext(Dispatchers.IO) {
            try {
                // ── 1. Validate region ────────────────────────────────────────
                val bw = screenBitmap.width
                val bh = screenBitmap.height
                val left   = region.left.coerceIn(0, bw - 1)
                val top    = region.top.coerceIn(0, bh - 1)
                val right  = region.right.coerceIn(left + 1, bw)
                val bottom = region.bottom.coerceIn(top + 1, bh)
                val w = right - left
                val h = bottom - top

                if (w < 10 || h < 10) {
                    return@withContext Result.failure(Exception("Selection too small. Please select a larger area."))
                }

                Log.i(TAG, "Cropping region: ${left}x${top} ${w}x${h} from ${bw}x${bh}")

                // ── 2. Crop ───────────────────────────────────────────────────
                val cropped = Bitmap.createBitmap(screenBitmap, left, top, w, h)

                // ── 3. OCR ────────────────────────────────────────────────────
                val raw = try {
                    recognizeFromBitmap(cropped)
                } finally {
                    if (cropped != screenBitmap) cropped.recycle()
                }

                if (raw.isBlank()) {
                    return@withContext Result.failure(Exception("No text found in selected area."))
                }

                // ── 4. Clean (same pipeline as OcrTextExtractor) ──────────────
                val clean = raw.clean()
                val truncated = clean.length > MAX_CHARS
                val final = if (truncated) clean.take(MAX_CHARS) else clean
                Log.i(TAG, "OCR: raw=${raw.length} clean=${clean.length} final=${final.length}")

                // ── 5. Detect content type ────────────────────────────────────
                val detection = ContentTypeDetector.detect(final)

                Result.success(ProcessResult(final, detection, truncated))

            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                Result.failure(e)
            }
        }

    private suspend fun recognizeFromBitmap(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    private fun String.clean(): String = this
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .split("\n")
        .filter { line ->
            val t = line.trim()
            t.isEmpty() || (t.length >= 2 && t.any { it.isLetterOrDigit() })
        }
        .joinToString("\n")
        .trim()

    fun close() = recognizer.close()
}
