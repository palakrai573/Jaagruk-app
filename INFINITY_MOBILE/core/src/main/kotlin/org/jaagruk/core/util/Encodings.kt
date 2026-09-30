package org.jaagruk.core.util

import java.util.Base64

/**
 * URL-safe, unpadded Base64. Unpadded matters: `=` is legal in a QR alphanumeric segment
 * but costs density, and stripping it removes an entire class of "some scanners keep the
 * padding, some drop it" interoperability bug.
 */
object Base64Url {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** @throws CanonicalFormatException if [text] is not valid unpadded URL-safe Base64. */
    fun decode(text: String): ByteArray {
        if (text.isEmpty()) throw CanonicalFormatException("empty base64url payload")
        // Tolerate padding on input even though we never emit it -- some QR generators and
        // clipboard paths add it back. Reject anything else explicitly.
        val trimmed = text.trimEnd('=')
        for ((index, ch) in trimmed.withIndex()) {
            val ok = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_'
            if (!ok) {
                throw CanonicalFormatException(
                    "illegal base64url character '$ch' (U+%04X) at index %d".format(ch.code, index),
                )
            }
        }
        return try {
            decoder.decode(trimmed)
        } catch (e: IllegalArgumentException) {
            throw CanonicalFormatException("malformed base64url payload: ${e.message}", e)
        }
    }
}

/** Lower-case hex. Used for fixture vectors, chain display and log lines. */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** First [count] bytes as hex, for compact display of a 32-byte hash. */
    fun encodePrefix(bytes: ByteArray, count: Int): String =
        encode(bytes.copyOfRange(0, count.coerceAtMost(bytes.size)))

    fun decode(text: String): ByteArray {
        val clean = text.removePrefix("0x").replace(" ", "")
        if (clean.length % 2 != 0) {
            throw CanonicalFormatException("hex string has odd length ${clean.length}")
        }
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) {
                throw CanonicalFormatException("illegal hex characters at index ${i * 2}")
            }
            result[i] = ((hi shl 4) or lo).toByte()
        }
        return result
    }
}
