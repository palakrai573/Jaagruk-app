package org.jaagruk.core

import org.jaagruk.core.cert.Attestation
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.cert.SignedAttestation
import org.jaagruk.core.crypto.Ed25519
import org.jaagruk.core.crypto.Sha256

/**
 * Deterministic test data.
 *
 * An Ed25519 private key is just 32 uniform bytes, so a hardcoded array gives a fully
 * reproducible signing identity with no seeded-PRNG portability worries. The same key material
 * is used by the fixture generator, which is what lets the Kotlin and Python canonical encoders
 * be checked against one committed file.
 */
object TestFixtures {

    const val SITE_ID: String = "JH-DHN-001"
    const val OTHER_SITE_ID: String = "JH-BOK-007"
    const val WORKER_ID: String = "JH-DHN-001-W00042"

    /** Fixed, non-secret, test-only key material. */
    val SITE_PRIVATE_KEY: ByteArray = ByteArray(32) { (it + 1).toByte() }

    val SITE_PUBLIC_KEY: ByteArray = Ed25519.publicKeyFromPrivate(SITE_PRIVATE_KEY)

    /** A second identity, for "signed by the wrong site" cases. */
    val IMPOSTOR_PRIVATE_KEY: ByteArray = ByteArray(32) { (200 - it).toByte() }

    val IMPOSTOR_PUBLIC_KEY: ByteArray = Ed25519.publicKeyFromPrivate(IMPOSTOR_PRIVATE_KEY)

    fun workerHash(workerId: String = WORKER_ID): ByteArray =
        AttestationCodec.workerIdHash(workerId)

    fun attestation(
        siteId: String = SITE_ID,
        seq: Long = 1L,
        workerId: String = WORKER_ID,
        moduleCode: Int = 1,
        scorePermille: Int = 842,
        medianLatencyMs: Long = 2_400L,
        flags: OutcomeFlags = OutcomeFlags.of(OutcomeFlags.PASSED, OutcomeFlags.SITE_SCANNED_AR),
        issuedAtEpochMin: Long = 29_400_000L,
        prevRecordHash: ByteArray = Sha256.ZERO,
    ): Attestation = Attestation(
        siteId = siteId,
        seq = seq,
        workerIdHash = workerHash(workerId),
        moduleCode = moduleCode,
        scorePermille = scorePermille,
        medianLatencyMs = medianLatencyMs,
        outcomeFlags = flags,
        issuedAtEpochMin = issuedAtEpochMin,
        prevRecordHash = prevRecordHash,
    )

    fun sign(
        attestation: Attestation,
        privateKey: ByteArray = SITE_PRIVATE_KEY,
    ): SignedAttestation = AttestationCodec.sign(attestation, privateKey)

    /** A valid chain of [length] linked, signed records for [siteId]. */
    fun chain(
        length: Int,
        siteId: String = SITE_ID,
        privateKey: ByteArray = SITE_PRIVATE_KEY,
    ): List<SignedAttestation> {
        require(length >= 0) { "length must be >= 0" }
        val records = mutableListOf<SignedAttestation>()
        var previous = Sha256.ZERO
        for (index in 1..length) {
            val attestation = attestation(
                siteId = siteId,
                seq = index.toLong(),
                workerId = "$siteId-W%05d".format(index),
                moduleCode = ((index - 1) % 5) + 1,
                scorePermille = 700 + (index * 7) % 300,
                medianLatencyMs = 1_500L + index * 37L,
                issuedAtEpochMin = 29_400_000L + index * 15L,
                prevRecordHash = previous,
            )
            val signed = sign(attestation, privateKey)
            records += signed
            previous = signed.recordHash
        }
        return records
    }
}
