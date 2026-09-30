package org.jaagruk.core.util

import java.io.ByteArrayOutputStream

/**
 * Thrown whenever bytes do not conform to a Jaagruk canonical encoding.
 *
 * Decoding never returns a partially populated object and never guesses: a malformed
 * certificate must surface as "malformed", not as an unverifiable one that looks real.
 */
class CanonicalFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Deterministic big-endian byte writer used for everything that gets hashed or signed.
 *
 * Two properties matter and are enforced rather than assumed:
 *
 *  1. **Determinism** — the same logical record always produces the identical byte
 *     string, on any JVM, any locale, any Kotlin version. No JSON, no text formatting,
 *     no map iteration order, no floating point.
 *  2. **Unambiguity** — every variable-length field is length-prefixed ([lp]), so no
 *     value can be shifted into an adjacent field. `siteId = "A"`, `workerId = "BC"`
 *     can never encode to the same bytes as `siteId = "AB"`, `workerId = "C"`.
 *
 * The Python re-implementation in `backend/app/core/canonical.py` mirrors this class
 * byte for byte and both are pinned to the same committed fixture vectors.
 */
class CanonicalWriter(initialCapacity: Int = 160) {

    private val out = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun u8(value: Int): CanonicalWriter {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        out.write(value)
        return this
    }

    fun u16(value: Int): CanonicalWriter {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
        return this
    }

    fun u32(value: Long): CanonicalWriter {
        require(value in 0..0xFFFF_FFFFL) { "u32 out of range: $value" }
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
        return this
    }

    fun i64(value: Long): CanonicalWriter {
        for (shift in 56 downTo 0 step 8) {
            out.write(((value ushr shift) and 0xFF).toInt())
        }
        return this
    }

    /** Writes exactly [expectedLength] bytes, rejecting anything else. */
    fun fixed(value: ByteArray, expectedLength: Int): CanonicalWriter {
        require(value.size == expectedLength) {
            "expected exactly $expectedLength bytes, got ${value.size}"
        }
        out.write(value, 0, value.size)
        return this
    }

    /** Raw ASCII, no length prefix. Reserved for fixed-width magic markers. */
    fun magic(value: String): CanonicalWriter {
        require(value.all { it.code in 0x20..0x7E }) { "magic must be printable ASCII: $value" }
        out.write(value.toByteArray(Charsets.US_ASCII))
        return this
    }

    /**
     * Length-prefixed UTF-8 string: `u16(byteLength) || utf8Bytes`.
     *
     * @param maxBytes hard cap for the field. Enforced here rather than at the call site
     *   so an oversized site name cannot silently push a QR payload past scannable density.
     */
    fun lp(value: String, maxBytes: Int = 0xFFFF): CanonicalWriter {
        require(maxBytes in 1..0xFFFF) { "maxBytes must be 1..65535, got $maxBytes" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) {
            "string field exceeds $maxBytes bytes (was ${bytes.size}): '${value.take(24)}...'"
        }
        u16(bytes.size)
        out.write(bytes, 0, bytes.size)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Mirror of [CanonicalWriter]. Every read is bounds-checked and every failure is a
 * [CanonicalFormatException] carrying the offset, because "malformed at byte 47" is
 * debuggable in the field and "invalid" is not.
 */
class CanonicalReader(private val buf: ByteArray, private var pos: Int = 0) {

    val position: Int get() = pos
    val remaining: Int get() = buf.size - pos
    val exhausted: Boolean get() = pos >= buf.size

    private fun need(count: Int) {
        if (count < 0) throw CanonicalFormatException("negative read length $count at $pos")
        if (pos + count > buf.size) {
            throw CanonicalFormatException(
                "truncated: need $count byte(s) at offset $pos but only $remaining remain",
            )
        }
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) {
            v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
        }
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
        }
        pos += 8
        return v
    }

    fun fixed(length: Int): ByteArray {
        need(length)
        val result = buf.copyOfRange(pos, pos + length)
        pos += length
        return result
    }

    fun magic(expected: String): String {
        val bytes = fixed(expected.length)
        val actual = String(bytes, Charsets.US_ASCII)
        if (actual != expected) {
            throw CanonicalFormatException("bad magic: expected '$expected', got '$actual'")
        }
        return actual
    }

    fun lp(maxBytes: Int = 0xFFFF): String {
        val length = u16()
        if (length > maxBytes) {
            throw CanonicalFormatException(
                "string field length $length exceeds cap $maxBytes at offset ${pos - 2}",
            )
        }
        val bytes = fixed(length)
        return String(bytes, Charsets.UTF_8)
    }

    /** Fails if any bytes are left over. Trailing garbage means a forged or corrupt payload. */
    fun requireExhausted() {
        if (!exhausted) {
            throw CanonicalFormatException("$remaining unexpected trailing byte(s) at offset $pos")
        }
    }
}
