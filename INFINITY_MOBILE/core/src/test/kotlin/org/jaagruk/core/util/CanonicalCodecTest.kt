package org.jaagruk.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CanonicalCodecTest {

    @Test
    fun `round trips every primitive`() {
        val bytes = CanonicalWriter()
            .magic("JGKA")
            .u8(0)
            .u8(255)
            .u16(0)
            .u16(65_535)
            .u32(0)
            .u32(0xFFFF_FFFFL)
            .i64(Long.MIN_VALUE)
            .i64(Long.MAX_VALUE)
            .lp("JH-DHN-001")
            .lp("")
            .fixed(ByteArray(32) { it.toByte() }, 32)
            .toByteArray()

        val reader = CanonicalReader(bytes)
        assertThat(reader.magic("JGKA")).isEqualTo("JGKA")
        assertThat(reader.u8()).isEqualTo(0)
        assertThat(reader.u8()).isEqualTo(255)
        assertThat(reader.u16()).isEqualTo(0)
        assertThat(reader.u16()).isEqualTo(65_535)
        assertThat(reader.u32()).isEqualTo(0L)
        assertThat(reader.u32()).isEqualTo(0xFFFF_FFFFL)
        assertThat(reader.i64()).isEqualTo(Long.MIN_VALUE)
        assertThat(reader.i64()).isEqualTo(Long.MAX_VALUE)
        assertThat(reader.lp()).isEqualTo("JH-DHN-001")
        assertThat(reader.lp()).isEmpty()
        assertThat(reader.fixed(32)).isEqualTo(ByteArray(32) { it.toByte() })
        reader.requireExhausted()
    }

    @Test
    fun `encoding is byte-identical across repeated calls`() {
        fun encode(): ByteArray = CanonicalWriter()
            .magic("JGKA")
            .u8(1)
            .lp("JH-BOK-042")
            .u32(9_999L)
            .fixed(ByteArray(32) { 7 }, 32)
            .toByteArray()

        assertThat(encode()).isEqualTo(encode())
    }

    @Test
    fun `length prefixes make adjacent fields unambiguous`() {
        // The whole reason variable-length fields are length-prefixed: without it, ("A","BC")
        // and ("AB","C") would produce the same bytes and a forger could shift a field boundary.
        val first = CanonicalWriter().lp("A").lp("BC").toByteArray()
        val second = CanonicalWriter().lp("AB").lp("C").toByteArray()
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `utf8 strings survive the round trip`() {
        val samples = listOf(
            "आपातकालीन निकास",
            "ᱡᱟᱦᱟᱸ ᱛᱮᱭᱟᱜ",
            "JH-DHN-001",
            "mixed देवनागरी and ASCII",
        )
        val writer = CanonicalWriter()
        samples.forEach { writer.lp(it) }
        val reader = CanonicalReader(writer.toByteArray())
        samples.forEach { assertThat(reader.lp()).isEqualTo(it) }
        reader.requireExhausted()
    }

    @Test
    fun `rejects out of range primitives`() {
        assertThrows<IllegalArgumentException> { CanonicalWriter().u8(256) }
        assertThrows<IllegalArgumentException> { CanonicalWriter().u8(-1) }
        assertThrows<IllegalArgumentException> { CanonicalWriter().u16(65_536) }
        assertThrows<IllegalArgumentException> { CanonicalWriter().u32(0x1_0000_0000L) }
        assertThrows<IllegalArgumentException> { CanonicalWriter().u32(-1L) }
    }

    @Test
    fun `rejects a fixed field of the wrong length`() {
        assertThrows<IllegalArgumentException> { CanonicalWriter().fixed(ByteArray(31), 32) }
        assertThrows<IllegalArgumentException> { CanonicalWriter().fixed(ByteArray(33), 32) }
    }

    @Test
    fun `rejects a string field over its declared cap`() {
        val error = assertThrows<IllegalArgumentException> {
            CanonicalWriter().lp("this-site-id-is-far-too-long-for-a-qr", maxBytes = 16)
        }
        assertThat(error).hasMessageThat().contains("16")
    }

    @Test
    fun `counts utf8 bytes not characters against the cap`() {
        // Eight Devanagari characters are 24 UTF-8 bytes, so a 16-byte cap must reject them.
        assertThrows<IllegalArgumentException> {
            CanonicalWriter().lp("आपातकालीन", maxBytes = 16)
        }
    }

    @Test
    fun `reader reports truncation with an offset`() {
        val bytes = CanonicalWriter().u32(1L).toByteArray().copyOfRange(0, 2)
        val error = assertThrows<CanonicalFormatException> { CanonicalReader(bytes).u32() }
        assertThat(error).hasMessageThat().contains("truncated")
    }

    @Test
    fun `reader rejects a bad magic marker`() {
        val bytes = CanonicalWriter().magic("XXXX").toByteArray()
        val error = assertThrows<CanonicalFormatException> { CanonicalReader(bytes).magic("JGKA") }
        assertThat(error).hasMessageThat().contains("bad magic")
    }

    @Test
    fun `reader rejects a declared length over the cap`() {
        val bytes = CanonicalWriter().lp("a".repeat(100)).toByteArray()
        assertThrows<CanonicalFormatException> { CanonicalReader(bytes).lp(maxBytes = 16) }
    }

    @Test
    fun `requireExhausted rejects trailing bytes`() {
        val bytes = CanonicalWriter().u8(1).u8(2).toByteArray()
        val reader = CanonicalReader(bytes)
        reader.u8()
        val error = assertThrows<CanonicalFormatException> { reader.requireExhausted() }
        assertThat(error).hasMessageThat().contains("trailing")
    }

    @Test
    fun `magic rejects non printable ascii`() {
        assertThrows<IllegalArgumentException> { CanonicalWriter().magic("JG\u0000A") }
        assertThrows<IllegalArgumentException> { CanonicalWriter().magic("जे") }
    }

    @Test
    fun `size tracks bytes written`() {
        val writer = CanonicalWriter()
        assertThat(writer.size).isEqualTo(0)
        writer.u8(1)
        assertThat(writer.size).isEqualTo(1)
        writer.u32(1L)
        assertThat(writer.size).isEqualTo(5)
        writer.lp("abc")
        assertThat(writer.size).isEqualTo(10)
    }

    @Test
    fun `big endian ordering is explicit`() {
        assertThat(CanonicalWriter().u16(0x0102).toByteArray())
            .isEqualTo(byteArrayOf(0x01, 0x02))
        assertThat(CanonicalWriter().u32(0x01020304L).toByteArray())
            .isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    }
}
