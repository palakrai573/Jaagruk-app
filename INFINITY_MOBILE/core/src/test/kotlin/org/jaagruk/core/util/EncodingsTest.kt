package org.jaagruk.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random

class EncodingsTest {

    @Test
    fun `base64url round trips arbitrary bytes`() {
        val random = Random(20260901L)
        repeat(200) {
            val size = random.nextInt(200) + 1
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            assertThat(Base64Url.decode(Base64Url.encode(bytes))).isEqualTo(bytes)
        }
    }

    @Test
    fun `base64url emits no padding and stays url safe`() {
        // Sizes 1..3 are exactly the cases that would normally produce '=' padding.
        for (size in 1..8) {
            val encoded = Base64Url.encode(ByteArray(size) { it.toByte() })
            assertThat(encoded).doesNotContain("=")
            assertThat(encoded).doesNotContain("+")
            assertThat(encoded).doesNotContain("/")
        }
    }

    @Test
    fun `base64url tolerates padding on input`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val unpadded = Base64Url.encode(bytes)
        assertThat(Base64Url.decode("$unpadded=")).isEqualTo(bytes)
        assertThat(Base64Url.decode("$unpadded==")).isEqualTo(bytes)
    }

    @Test
    fun `base64url rejects illegal characters with a position`() {
        val error = assertThrows<CanonicalFormatException> { Base64Url.decode("abc\$def") }
        assertThat(error).hasMessageThat().contains("index 3")
    }

    @Test
    fun `base64url rejects standard alphabet characters`() {
        // '+' and '/' belong to standard Base64, not the URL-safe alphabet. Accepting them would
        // mean two different encodings decode to the same certificate.
        assertThrows<CanonicalFormatException> { Base64Url.decode("ab+d") }
        assertThrows<CanonicalFormatException> { Base64Url.decode("ab/d") }
    }

    @Test
    fun `base64url rejects an empty payload`() {
        assertThrows<CanonicalFormatException> { Base64Url.decode("") }
    }

    @Test
    fun `hex round trips`() {
        val random = Random(7L)
        repeat(100) {
            val bytes = ByteArray(random.nextInt(64) + 1).also { random.nextBytes(it) }
            assertThat(Hex.decode(Hex.encode(bytes))).isEqualTo(bytes)
        }
    }

    @Test
    fun `hex is lower case and fixed width`() {
        assertThat(Hex.encode(byteArrayOf(0x00, 0x0F, 0xFF.toByte(), 0xA0.toByte())))
            .isEqualTo("000fffa0")
    }

    @Test
    fun `hex prefix truncates safely`() {
        val bytes = ByteArray(32) { it.toByte() }
        assertThat(Hex.encodePrefix(bytes, 4)).isEqualTo("00010203")
        // Asking for more than there is must clamp rather than throw.
        assertThat(Hex.encodePrefix(byteArrayOf(1), 8)).isEqualTo("01")
        assertThat(Hex.encodePrefix(ByteArray(0), 4)).isEmpty()
    }

    @Test
    fun `hex accepts a 0x prefix and spaces`() {
        assertThat(Hex.decode("0x0a0b")).isEqualTo(byteArrayOf(0x0A, 0x0B))
        assertThat(Hex.decode("0a 0b")).isEqualTo(byteArrayOf(0x0A, 0x0B))
    }

    @Test
    fun `hex rejects odd length and non hex characters`() {
        assertThrows<CanonicalFormatException> { Hex.decode("abc") }
        assertThrows<CanonicalFormatException> { Hex.decode("zzzz") }
    }
}
