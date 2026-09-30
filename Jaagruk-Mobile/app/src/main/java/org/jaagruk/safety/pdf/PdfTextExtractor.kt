package org.jaagruk.safety.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * PdfTextExtractor
 *
 * Extracts readable text from a PDF Uri using Android's built-in PdfRenderer (API 21+)
 * combined with raw PDF content-stream parsing. No third-party library required.
 *
 * Works for text-based PDFs (Word exports, reports, articles).
 * Scanned/image-only PDFs will return an explanatory error — OCR is out of scope.
 *
 * Text is capped at MAX_CHARS to stay within the model's 2048-token context window.
 */
class PdfTextExtractor(private val context: Context) {

    companion object {
        private const val TAG = "PdfTextExtractor"
        // Budget breakdown (N_CTX = 2048):
        //   system prompt     ≈  60 tokens
        //   ChatML scaffolding ≈  20 tokens
        //   prompt prefix      ≈  20 tokens
        //   max output        = 512 tokens
        //   remaining for doc ≈ 1436 tokens
        // Clean prose tokenises at ~4 chars/token → 1436 * 4 = 5744 chars safe upper bound.
        // PDF artifacts inflate token count 3-4×, so we use 800 chars as a safe cap
        // (≈200 clean tokens) to leave generous headroom for artifacts + output.
        private const val MAX_CHARS = 800
        private val PDF_CONTENT_RE = Regex("""\(([^)]*)\)|<([0-9A-Fa-f]{4,})>""")
        // PDF operator keywords that leak into extracted text — strip them.
        // Uses (?<![A-Za-z]) / (?![A-Za-z]) instead of \b so that operators
        // adjacent to digits (e.g. "720Td", "12cm") are also matched.
        // \b does NOT fire between a digit and a letter, both being \w chars.
        private val PDF_OPERATORS = Regex(
            """(?<![A-Za-z])(BT|ET|Tf|Td|TD|Tm|T\*|Tj|TJ|Tw|Tc|Tz|TL|Tr|Ts|cm|re|gs|Do|BI|EI|BMC|BDC|EMC|MP|DP|sh|SCN|scn|RG|rg|CS|cs)(?![A-Za-z])"""
        )
    }

    /**
     * Extract text from the PDF at [uri].
     * @param onProgress called with (pagesProcessed, totalPages)
     */
    suspend fun extract(uri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }
                    ?: return@withContext Result.failure(Exception("Cannot open PDF file"))

                // Use PdfRenderer only for page count + progress reporting
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext Result.failure(Exception("Cannot open file descriptor"))

                val totalPages = pfd.use { PdfRenderer(it).use { r -> r.pageCount } }
                if (totalPages == 0) return@withContext Result.failure(Exception("PDF has no pages"))

                Log.i(TAG, "PDF: $totalPages pages")
                onProgress(0, totalPages)

                val rawText = extractFromContentStreams(bytes)

                if (rawText.isBlank()) {
                    return@withContext Result.failure(
                        Exception("No readable text found. This may be a scanned or image-only PDF.")
                    )
                }

                onProgress(totalPages, totalPages)

                val trimmed = if (rawText.length > MAX_CHARS)
                    rawText.take(MAX_CHARS) + "\n[Truncated]"
                else rawText

                Log.i(TAG, "Final text: ${trimmed.length} chars, est. ~${trimmed.length / 4} tokens")
                Result.success(trimmed)

            } catch (e: Exception) {
                Log.e(TAG, "PDF extraction failed", e)
                Result.failure(e)
            }
        }

    // ── Raw PDF content-stream parser ─────────────────────────────────────────

    /**
     * Walks all stream...endstream blocks in the raw PDF bytes looking for
     * BT...ET (Begin Text / End Text) sections, then extracts string operands
     * from Tj / TJ / ' / " operators.
     *
     * Works for uncompressed content streams (standard for text-only PDFs).
     * Flate-compressed streams are skipped gracefully (no crash, just empty).
     */
    private fun extractFromContentStreams(bytes: ByteArray): String {
        val pdf = String(bytes, Charsets.ISO_8859_1)
        val sb  = StringBuilder()

        var searchFrom = 0
        while (true) {
            val streamStart = pdf.indexOf("stream", searchFrom).takeIf { it >= 0 } ?: break
            val dataStart = when {
                pdf.getOrNull(streamStart + 6) == '\r' &&
                pdf.getOrNull(streamStart + 7) == '\n' -> streamStart + 8
                pdf.getOrNull(streamStart + 6) == '\n' -> streamStart + 7
                else                                   -> streamStart + 6
            }
            val streamEnd = pdf.indexOf("endstream", dataStart).takeIf { it >= 0 } ?: break
            searchFrom = streamEnd + 9

            // Check if this stream object uses FlateDecode compression by scanning
            // backwards from "stream" to find the stream dictionary (<<...>>).
            val dictEnd   = streamStart
            val dictStart = pdf.lastIndexOf("<<", dictEnd).takeIf { it >= 0 } ?: continue
            val dict      = pdf.substring(dictStart, dictEnd)
            val isFlate   = dict.contains("FlateDecode") || dict.contains("Fl ")

            val block: String = if (isFlate) {
                // Decompress the raw bytes of this stream using zlib
                val rawBytes = bytes.copyOfRange(
                    dataStart.coerceAtMost(bytes.size),
                    streamEnd.coerceAtMost(bytes.size)
                )
                tryInflate(rawBytes) ?: continue
            } else {
                pdf.substring(dataStart, streamEnd)
            }

            if (!block.contains("BT")) continue   // not a text content stream
            parseBtEtBlocks(block, sb)
        }

        // Fallback: plain string literals outside streams (simple/flat PDFs)
        if (sb.isBlank()) {
            Regex("""\(([^)]{4,})\)""").findAll(pdf).forEach { mr ->
                val s = mr.groupValues[1].decodePdfLiteral()
                if (s.isPrintableLine()) sb.append(s).append(' ')
            }
        }

        val dirty = sb.toString()
        val clean = dirty.clean()
        Log.i(TAG, "Raw extracted: ${dirty.length} chars → cleaned: ${clean.length} chars")
        return clean
    }

    /**
     * Aggressively clean raw PDF-extracted text:
     * 1. Strip control characters (\x00-\x1F except \n, \t) — these each become tokens
     * 2. Strip PDF operator keywords that leaked through
     * 3. Strip lone punctuation/digit lines left by coordinate operators
     * 4. Collapse runs of whitespace / newlines
     * 5. Remove lines that are pure numbers or single characters (PDF artifacts)
     */
    private fun String.clean(): String = this
        // 1. Remove non-printable control characters (keep \n, \t, space)
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        // 2. Remove high-byte ISO-8859-1 artifacts that aren't real Unicode letters
        .replace(Regex("[\\x80-\\x9F]"), "")
        // 3. Strip PDF operator keywords
        .replace(PDF_OPERATORS, " ")
        // 4. Collapse runs of spaces/tabs
        .replace(Regex("[ \t]{2,}"), " ")
        // 5. Collapse 3+ consecutive newlines to double newline (paragraph break)
        .replace(Regex("\\n{3,}"), "\n\n")
        // 6. Remove lines that are only digits, spaces, or single chars (coordinate artifacts)
        .split("\n")
        .filter { line ->
            val t = line.trim()
            t.isEmpty() || (t.length >= 3 && t.any { it.isLetter() })
        }
        .joinToString("\n")
        .trim()

    private fun parseBtEtBlocks(block: String, sb: StringBuilder) {
        var i = 0
        while (i < block.length) {
            val bt = block.indexOf("BT", i).takeIf { it >= 0 } ?: break
            val et = block.indexOf("ET", bt + 2).takeIf { it >= 0 } ?: break
            extractStrings(block.substring(bt + 2, et), sb)
            i = et + 2
        }
    }

    private fun extractStrings(section: String, sb: StringBuilder) {
        PDF_CONTENT_RE.findAll(section).forEach { mr ->
            val literal = mr.groupValues[1]
            val hex     = mr.groupValues[2]
            val text = when {
                literal.isNotEmpty() -> literal.decodePdfLiteral()
                hex.isNotEmpty()     -> hex.decodeHex()
                else                 -> ""
            }
            if (text.isPrintableLine()) sb.append(text)
        }
        sb.append(' ')
    }

    private fun String.decodePdfLiteral() = replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\(", "(")
        .replace("\\)", ")")
        .replace("\\\\", "\\")

    private fun String.decodeHex(): String = try {
        chunked(2).map { it.toInt(16).toChar() }.joinToString("").filter { it.code in 32..126 }
    } catch (_: Exception) { "" }

    private fun String.isPrintableLine() = isNotBlank() && any { it.isLetterOrDigit() }

    /**
     * Attempt zlib decompression (FlateDecode) of a PDF stream's raw bytes.
     * Returns the decompressed string on success, null on any failure
     * (wrong stream type, corrupt data — both are silently skipped).
     */
    private fun tryInflate(data: ByteArray): String? = try {
        InflaterInputStream(ByteArrayInputStream(data))
            .use { it.readBytes() }
            .let { String(it, Charsets.ISO_8859_1) }
    } catch (_: Exception) {
        null   // not a zlib stream or corrupt — skip silently
    }
}
