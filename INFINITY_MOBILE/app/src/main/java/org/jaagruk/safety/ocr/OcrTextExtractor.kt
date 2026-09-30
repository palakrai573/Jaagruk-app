package org.jaagruk.safety.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OcrTextExtractor
 *
 * Wraps ML Kit Text Recognition as a suspend function.
 * Returns cleaned, token-safe text capped at MAX_CHARS.
 * Reused by both OcrViewModel and ScreenshotExplainerViewModel.
 */
class OcrTextExtractor {

    companion object {
        private const val TAG = "OcrTextExtractor"
        const val MAX_CHARS   = 800
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run OCR on the image at [uri].
     * Returns Result.success(Pair(cleanedText, wasTruncated)) or Result.failure.
     */
    suspend fun extract(context: Context, uri: Uri): Result<Pair<String, Boolean>> {
        return try {
            val image = InputImage.fromFilePath(context, uri)
            val raw   = recognize(image)
            if (raw.isBlank()) return Result.failure(Exception("No text detected in image."))
            val clean       = raw.clean()
            val truncated   = clean.length > MAX_CHARS
            val final       = if (truncated) clean.take(MAX_CHARS) else clean
            Log.i(TAG, "OCR: raw=${raw.length} clean=${clean.length} final=${final.length} truncated=$truncated")
            Log.i(TAG, "  first 500 chars: ${final.take(500).replace("\n", "\\n")}")
            Result.success(Pair(final, truncated))
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            Result.failure(e)
        }
    }

    /** Suspend wrapper around ML Kit's Task-based API. */
    private suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    /**
     * Clean OCR output:
     * - Remove control characters
     * - Collapse excessive whitespace and newlines
     * - Remove lines that are only punctuation or single chars (OCR noise)
     */
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
