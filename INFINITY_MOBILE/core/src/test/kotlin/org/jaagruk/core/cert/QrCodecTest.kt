package org.jaagruk.core.cert

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.TestFixtures
import org.jaagruk.core.util.Base64Url
import org.jaagruk.core.util.CanonicalFormatException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QrCodecTest {

    @Test
    fun `round trips a signed certificate`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val decoded = QrCodec.decode(QrCodec.encode(signed))

        assertThat(decoded.attestation).isEqualTo(signed.attestation)
        assertThat(decoded.signature).isEqualTo(signed.signature)
        assertThat(decoded.recordHash).isEqualTo(signed.recordHash)
    }

    @Test
    fun `decoded certificate still verifies against the site key`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val decoded = QrCodec.decode(QrCodec.encode(signed))
        assertThat(AttestationCodec.verifySignature(decoded, TestFixtures.SITE_PUBLIC_KEY)).isTrue()
    }

    @Test
    fun `payload length stays inside the scannable budget`() {
        // Pinned deliberately. A certificate that is 20 % larger stops being readable off a
        // scratched printed card in a poorly lit mine office, and that failure would only show up
        // in the field. If this assertion breaks, the format grew and the change was not deliberate.
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val text = QrCodec.encode(signed)
        val binary = Base64Url.decode(text.removePrefix(QrCodec.TEXT_PREFIX))

        assertThat(binary).hasLength(158)
        assertThat(text).hasLength(216)
        assertThat(binary.size).isAtMost(QrCodec.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `payload stays within budget for the longest allowed site id`() {
        val signed = TestFixtures.sign(
            TestFixtures.attestation(siteId = "J".repeat(Attestation.MAX_SITE_ID_BYTES)),
        )
        val binary = Base64Url.decode(QrCodec.encode(signed).removePrefix(QrCodec.TEXT_PREFIX))
        assertThat(binary.size).isAtMost(QrCodec.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `encoded text carries the jaagruk prefix`() {
        val text = QrCodec.encode(TestFixtures.sign(TestFixtures.attestation()))
        assertThat(text).startsWith(QrCodec.TEXT_PREFIX)
        assertThat(QrCodec.looksLikeJaagrukCertificate(text)).isTrue()
    }

    @Test
    fun `accepts the printable url form`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val url = QrCodec.encodeAsUrl(signed, "https://jaagruk.jharkhand.gov.in")

        assertThat(url).startsWith("https://jaagruk.jharkhand.gov.in/v/")
        assertThat(QrCodec.decode(url).attestation).isEqualTo(signed.attestation)
        assertThat(QrCodec.looksLikeJaagrukCertificate(url)).isTrue()
    }

    @Test
    fun `url form tolerates a trailing slash on the base`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val url = QrCodec.encodeAsUrl(signed, "https://example.gov.in/")
        assertThat(url).doesNotContain("//v/")
        assertThat(QrCodec.decode(url).attestation).isEqualTo(signed.attestation)
    }

    @Test
    fun `strips query strings and fragments a scanner may append`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val url = QrCodec.encodeAsUrl(signed, "https://example.gov.in")

        assertThat(QrCodec.decode("$url?utm_source=camera").attestation)
            .isEqualTo(signed.attestation)
        assertThat(QrCodec.decode("$url#top").attestation).isEqualTo(signed.attestation)
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        val text = QrCodec.encode(TestFixtures.sign(TestFixtures.attestation()))
        assertThat(QrCodec.decode("  $text\n").attestation).isNotNull()
    }

    @Test
    fun `rejects a qr code from another app`() {
        val error = assertThrows<CanonicalFormatException> {
            QrCodec.decode("WIFI:S:MineNetwork;T:WPA;P:secret;;")
        }
        assertThat(error).hasMessageThat().contains("not a Jaagruk certificate")
        assertThat(QrCodec.looksLikeJaagrukCertificate("WIFI:S:x;;")).isFalse()
    }

    @Test
    fun `rejects an empty or prefix only payload`() {
        assertThrows<CanonicalFormatException> { QrCodec.decode("") }
        assertThrows<CanonicalFormatException> { QrCodec.decode("   ") }
        assertThrows<CanonicalFormatException> { QrCodec.decode(QrCodec.TEXT_PREFIX) }
    }

    @Test
    fun `rejects a truncated payload rather than guessing`() {
        // A partially decoded QR from a damaged card must fail loudly. Silently accepting a short
        // payload is how a verifier ends up reporting a forged certificate as genuine.
        val text = QrCodec.encode(TestFixtures.sign(TestFixtures.attestation()))
        for (cut in listOf(10, 40, 100, 180, 210)) {
            assertThrows<CanonicalFormatException> { QrCodec.decode(text.substring(0, cut)) }
        }
    }

    @Test
    fun `rejects an oversized payload`() {
        val padded = QrCodec.TEXT_PREFIX + Base64Url.encode(ByteArray(600))
        val error = assertThrows<CanonicalFormatException> { QrCodec.decode(padded) }
        assertThat(error).hasMessageThat().contains("over the")
    }

    @Test
    fun `rejects a bad internal magic byte`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val binary = Base64Url.decode(QrCodec.encode(signed).removePrefix(QrCodec.TEXT_PREFIX))
        binary[0] = 'Z'.code.toByte()
        assertThrows<CanonicalFormatException> {
            QrCodec.decode(QrCodec.TEXT_PREFIX + Base64Url.encode(binary))
        }
    }

    @Test
    fun `decodeOrNull swallows malformed input for scanner hot paths`() {
        assertThat(QrCodec.decodeOrNull("not-a-certificate")).isNull()
        assertThat(QrCodec.decodeOrNull(QrCodec.TEXT_PREFIX + "AAAA")).isNull()

        val text = QrCodec.encode(TestFixtures.sign(TestFixtures.attestation()))
        assertThat(QrCodec.decodeOrNull(text)).isNotNull()
    }

    @Test
    fun `a single flipped payload bit is always detected`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val binary = Base64Url.decode(QrCodec.encode(signed).removePrefix(QrCodec.TEXT_PREFIX))

        var detected = 0
        for (index in binary.indices) {
            val mutated = binary.copyOf()
            mutated[index] = (mutated[index].toInt() xor 0x01).toByte()
            val text = QrCodec.TEXT_PREFIX + Base64Url.encode(mutated)

            val decoded = QrCodec.decodeOrNull(text)
            val trusted = decoded != null &&
                AttestationCodec.verifySignature(decoded, TestFixtures.SITE_PUBLIC_KEY)
            if (!trusted) detected++
        }
        // Every single-bit mutation must be caught either by the decoder or by the signature.
        assertThat(detected).isEqualTo(binary.size)
    }

    @Test
    fun `qr text is stable for the same certificate`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        assertThat(QrCodec.encode(signed)).isEqualTo(QrCodec.encode(signed))
    }

    @Test
    fun `encodeAsUrl rejects a blank base`() {
        assertThrows<IllegalArgumentException> {
            QrCodec.encodeAsUrl(TestFixtures.sign(TestFixtures.attestation()), "  ")
        }
    }
}
