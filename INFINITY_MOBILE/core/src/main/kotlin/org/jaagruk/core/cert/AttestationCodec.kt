package org.jaagruk.core.cert

import org.jaagruk.core.crypto.Ed25519
import org.jaagruk.core.crypto.Sha256
import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.CanonicalReader
import org.jaagruk.core.util.CanonicalWriter

/**
 * Canonical encoding, signing and verification for [Attestation].
 *
 * This object is the trust boundary of the whole certification layer. Its byte layout is
 * specified in `docs/ARCHITECTURE.md` §2.1, re-implemented in
 * `backend/app/core/canonical.py`, and both implementations are pinned to the committed
 * fixture vectors in `core/src/test/resources/fixtures/attestation_vectors.json`. A change
 * on either side without regenerating the fixtures turns a test red instead of quietly
 * invalidating every certificate in the field.
 */
object AttestationCodec {

    const val MAGIC: String = "JGKA"

    /** magic(4) + formatVersion(1). Stripped from the QR payload and rebuilt on decode. */
    const val HEADER_SIZE: Int = 5

    // -----------------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------------

    /** The exact bytes that get signed. */
    fun canonicalBytes(attestation: Attestation): ByteArray =
        CanonicalWriter(128)
            .magic(MAGIC)
            .u8(attestation.formatVersion)
            .lp(attestation.siteId, Attestation.MAX_SITE_ID_BYTES)
            .u32(attestation.seq)
            .fixed(attestation.workerIdHash, Sha256.SIZE_BYTES)
            .u8(attestation.moduleCode)
            .u16(attestation.scorePermille)
            .u32(attestation.medianLatencyMs)
            .u8(attestation.outcomeFlags.bits)
            .u32(attestation.issuedAtEpochMin)
            .fixed(attestation.prevRecordHash, Sha256.SIZE_BYTES)
            .toByteArray()

    /** Canonical bytes without the fixed header, for embedding in a QR payload. */
    fun bodyBytes(attestation: Attestation): ByteArray {
        val canonical = canonicalBytes(attestation)
        return canonical.copyOfRange(HEADER_SIZE, canonical.size)
    }

    /** Rebuilds the signable canonical bytes from a header-less body. */
    fun canonicalFromBody(formatVersion: Int, body: ByteArray): ByteArray {
        require(formatVersion in 0..0xFF) { "formatVersion out of u8 range: $formatVersion" }
        return CanonicalWriter(body.size + HEADER_SIZE)
            .magic(MAGIC)
            .u8(formatVersion)
            .fixed(body, body.size)
            .toByteArray()
    }

    // -----------------------------------------------------------------------
    // Decoding
    // -----------------------------------------------------------------------

    /**
     * @throws CanonicalFormatException on bad magic, unsupported version, reserved flag
     *   bits, out-of-range values, truncation or trailing bytes. Never returns a
     *   half-populated object.
     */
    fun decodeCanonical(canonical: ByteArray): Attestation {
        val reader = CanonicalReader(canonical)
        reader.magic(MAGIC)
        val formatVersion = reader.u8()
        if (formatVersion != Attestation.FORMAT_VERSION) {
            throw CanonicalFormatException(
                "attestation format version $formatVersion is not supported by this build " +
                    "(expected ${Attestation.FORMAT_VERSION}); update the app",
            )
        }
        val siteId = reader.lp(Attestation.MAX_SITE_ID_BYTES)
        val seq = reader.u32()
        val workerIdHash = reader.fixed(Sha256.SIZE_BYTES)
        val moduleCode = reader.u8()
        val scorePermille = reader.u16()
        val medianLatencyMs = reader.u32()
        val flagBits = reader.u8()
        val issuedAtEpochMin = reader.u32()
        val prevRecordHash = reader.fixed(Sha256.SIZE_BYTES)
        reader.requireExhausted()

        // Range and invariant violations arrive here as IllegalArgumentException from the
        // Attestation constructor. They are remapped so that every caller of this object
        // only ever has to handle CanonicalFormatException.
        return try {
            Attestation(
                formatVersion = formatVersion,
                siteId = siteId,
                seq = seq,
                workerIdHash = workerIdHash,
                moduleCode = moduleCode,
                scorePermille = scorePermille,
                medianLatencyMs = medianLatencyMs,
                outcomeFlags = OutcomeFlags.fromBits(flagBits),
                issuedAtEpochMin = issuedAtEpochMin,
                prevRecordHash = prevRecordHash,
            )
        } catch (e: IllegalArgumentException) {
            throw CanonicalFormatException("attestation failed validation: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Signing / verification
    // -----------------------------------------------------------------------

    /** `SHA-256(canonical || signature)` — the chain link a successor points back to. */
    fun recordHash(canonical: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == Ed25519.SIGNATURE_SIZE) {
            "signature must be ${Ed25519.SIGNATURE_SIZE} bytes, got ${signature.size}"
        }
        return Sha256.hash(canonical, signature)
    }

    fun recordHash(attestation: Attestation, signature: ByteArray): ByteArray =
        recordHash(canonicalBytes(attestation), signature)

    fun sign(attestation: Attestation, sitePrivateKey: ByteArray): SignedAttestation {
        val canonical = canonicalBytes(attestation)
        val signature = Ed25519.sign(sitePrivateKey, canonical)
        return SignedAttestation(attestation, signature, recordHash(canonical, signature))
    }

    /**
     * Verifies the signature **and** that [SignedAttestation.recordHash] is genuinely the
     * hash of what was signed. Checking only the signature would let a caller carry a
     * doctored `recordHash` and redirect the chain.
     */
    fun verifySignature(signed: SignedAttestation, sitePublicKey: ByteArray): Boolean {
        val canonical = canonicalBytes(signed.attestation)
        if (!Ed25519.verify(sitePublicKey, canonical, signed.signature)) return false
        val expected = recordHash(canonical, signed.signature)
        return Sha256.constantTimeEquals(expected, signed.recordHash)
    }

    /** Hashes a plaintext worker ID into the form the QR carries. */
    fun workerIdHash(workerId: String): ByteArray {
        require(workerId.isNotBlank()) { "workerId must not be blank" }
        return Sha256.hashUtf8(workerId)
    }

    /**
     * Confirms a scanned certificate belongs to the ID printed on a worker's physical card.
     * Constant-time so an inspector's device cannot be used as a hash oracle.
     */
    fun matchesWorkerId(attestation: Attestation, candidateWorkerId: String): Boolean {
        if (candidateWorkerId.isBlank()) return false
        return Sha256.constantTimeEquals(attestation.workerIdHash, workerIdHash(candidateWorkerId))
    }
}
