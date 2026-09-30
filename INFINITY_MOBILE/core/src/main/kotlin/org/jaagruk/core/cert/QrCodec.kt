package org.jaagruk.core.cert

import org.jaagruk.core.crypto.Ed25519
import org.jaagruk.core.util.Base64Url
import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.CanonicalReader
import org.jaagruk.core.util.CanonicalWriter

/**
 * Encodes a [SignedAttestation] into a QR string and back.
 *
 * Density is a functional requirement, not an aesthetic one. These codes get scanned by
 * mid-range cameras, underground, in dust, off a scratched printed card. A typical payload
 * is ~216 characters, which is QR version 9 at error-correction level M — comfortably
 * scannable. [MAX_PAYLOAD_BYTES] and a test that pins the exact length exist so that adding
 * a field later cannot quietly push real certificates past what a phone can read.
 *
 * Two accepted input forms:
 *
 *  * `JGK1:<base64url>` — what the app generates and what it prefers.
 *  * `https://…/v/<base64url>` — the same payload wrapped in a URL, so a printed card also
 *    works with a stock camera app. Jaagruk still verifies it fully offline; the URL is a
 *    convenience for people who do not have the app, not part of the trust path.
 */
object QrCodec {

    const val TEXT_PREFIX: String = "JGK1:"
    const val MAGIC: String = "J"

    /** Marker used by the printable URL form. */
    const val VERIFY_URL_MARKER: String = "/v/"

    /**
     * Hard ceiling on the binary payload. Well above the ~158 bytes a real certificate
     * needs, well below the point where a QR stops being readable on a cracked screen.
     */
    const val MAX_PAYLOAD_BYTES: Int = 512

    /** magic(1) + version(1) + smallest possible body + signature(64). */
    private const val MIN_PAYLOAD_BYTES: Int = 2 + 84 + Ed25519.SIGNATURE_SIZE

    fun encode(signed: SignedAttestation): String {
        val body = AttestationCodec.bodyBytes(signed.attestation)
        val binary = CanonicalWriter(body.size + 2 + Ed25519.SIGNATURE_SIZE)
            .magic(MAGIC)
            .u8(signed.attestation.formatVersion)
            .fixed(body, body.size)
            .fixed(signed.signature, Ed25519.SIGNATURE_SIZE)
            .toByteArray()

        check(binary.size <= MAX_PAYLOAD_BYTES) {
            "QR payload grew to ${binary.size} bytes, over the $MAX_PAYLOAD_BYTES limit; " +
                "shrink a field or bump the format version deliberately"
        }
        return TEXT_PREFIX + Base64Url.encode(binary)
    }

    /** Wraps the same payload in a scannable https URL for printed certificates. */
    fun encodeAsUrl(signed: SignedAttestation, baseUrl: String): String {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        val payload = encode(signed).removePrefix(TEXT_PREFIX)
        return baseUrl.trimEnd('/') + VERIFY_URL_MARKER + payload
    }

    /**
     * @throws CanonicalFormatException for anything that is not a well-formed Jaagruk
     *   certificate. A damaged scan, a QR from another app, a truncated payload and a
     *   payload targeting a future format all land here — never as a silent partial decode
     *   and never as a false "valid".
     */
    fun decode(qrText: String): SignedAttestation {
        val payload = extractPayload(qrText)
        val binary = Base64Url.decode(payload)

        if (binary.size < MIN_PAYLOAD_BYTES) {
            throw CanonicalFormatException(
                "certificate payload is only ${binary.size} bytes; " +
                    "at least $MIN_PAYLOAD_BYTES are required (damaged or partial scan?)",
            )
        }
        if (binary.size > MAX_PAYLOAD_BYTES) {
            throw CanonicalFormatException(
                "certificate payload is ${binary.size} bytes, over the $MAX_PAYLOAD_BYTES limit",
            )
        }

        val reader = CanonicalReader(binary)
        reader.magic(MAGIC)
        val formatVersion = reader.u8()

        val bodyLength = binary.size - reader.position - Ed25519.SIGNATURE_SIZE
        if (bodyLength <= 0) {
            throw CanonicalFormatException("certificate payload has no body before its signature")
        }
        val body = reader.fixed(bodyLength)
        val signature = reader.fixed(Ed25519.SIGNATURE_SIZE)
        reader.requireExhausted()

        val canonical = AttestationCodec.canonicalFromBody(formatVersion, body)
        // decodeCanonical enforces every field range and rejects trailing bytes, so a body
        // of the wrong length is caught here rather than producing a plausible-looking record.
        val attestation = AttestationCodec.decodeCanonical(canonical)
        val recordHash = AttestationCodec.recordHash(canonical, signature)

        return SignedAttestation(attestation, signature, recordHash)
    }

    /** Non-throwing variant for scanner hot paths that see a lot of unrelated barcodes. */
    fun decodeOrNull(qrText: String): SignedAttestation? =
        try {
            decode(qrText)
        } catch (e: CanonicalFormatException) {
            null
        }

    /** Cheap pre-filter so a scanner can ignore barcodes that are obviously not ours. */
    fun looksLikeJaagrukCertificate(qrText: String): Boolean {
        val trimmed = qrText.trim()
        return trimmed.startsWith(TEXT_PREFIX) || trimmed.contains(VERIFY_URL_MARKER)
    }

    private fun extractPayload(qrText: String): String {
        val trimmed = qrText.trim()
        if (trimmed.isEmpty()) throw CanonicalFormatException("empty QR content")

        val raw = when {
            trimmed.startsWith(TEXT_PREFIX) -> trimmed.substring(TEXT_PREFIX.length)

            trimmed.contains(VERIFY_URL_MARKER) ->
                trimmed.substringAfterLast(VERIFY_URL_MARKER)

            else -> throw CanonicalFormatException(
                "this QR code is not a Jaagruk certificate " +
                    "(expected a '$TEXT_PREFIX' prefix or a '$VERIFY_URL_MARKER' verification link)",
            )
        }

        // Strip anything a URL shortener, tracker or scanner may have appended.
        val payload = raw.substringBefore('?').substringBefore('#').trim()
        if (payload.isEmpty()) {
            throw CanonicalFormatException("QR code carries a Jaagruk prefix but no payload")
        }
        return payload
    }
}
