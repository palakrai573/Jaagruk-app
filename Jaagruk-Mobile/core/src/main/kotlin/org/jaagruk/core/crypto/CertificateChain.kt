package org.jaagruk.core.crypto

import org.jaagruk.core.cert.Attestation
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.cert.SignedAttestation
import org.jaagruk.core.util.Hex

/**
 * The tip of one site's certificate chain.
 *
 * One chain per site, not per worker and not global. A site is the unit that has a signing
 * key, a supervisor, and an inspector who audits it, so it is also the unit whose history
 * must be tamper-evident. Per-worker chains would be trivially truncated by deleting a
 * worker; a global chain would need connectivity that these sites do not have.
 */
class ChainHead(
    val siteId: String,
    val lastSeq: Long,
    val lastRecordHash: ByteArray,
) {
    init {
        require(siteId.isNotBlank()) { "siteId must not be blank" }
        require(lastSeq >= 0) { "lastSeq must be >= 0 (0 means empty chain), got $lastSeq" }
        require(lastRecordHash.size == Sha256.SIZE_BYTES) {
            "lastRecordHash must be ${Sha256.SIZE_BYTES} bytes, got ${lastRecordHash.size}"
        }
        require(lastSeq != 0L || Sha256.isZero(lastRecordHash)) {
            "an empty chain (lastSeq=0) must carry a zero lastRecordHash"
        }
        require(lastSeq == 0L || !Sha256.isZero(lastRecordHash)) {
            "a non-empty chain (lastSeq=$lastSeq) cannot carry a zero lastRecordHash"
        }
    }

    val isEmpty: Boolean get() = lastSeq == 0L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainHead) return false
        return siteId == other.siteId &&
            lastSeq == other.lastSeq &&
            lastRecordHash.contentEquals(other.lastRecordHash)
    }

    override fun hashCode(): Int {
        var result = siteId.hashCode()
        result = 31 * result + lastSeq.hashCode()
        result = 31 * result + lastRecordHash.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ChainHead($siteId, seq=$lastSeq, hash=${Hex.encodePrefix(lastRecordHash, 6)}..)"

    companion object {
        fun empty(siteId: String): ChainHead = ChainHead(siteId, 0L, Sha256.ZERO)
    }
}

/** A newly issued certificate together with the chain tip it produced. */
class ChainAppend(val signed: SignedAttestation, val newHead: ChainHead)

/** Raised when the chain cannot be extended. Always actionable, never a bare failure. */
class ChainAppendException(message: String) : IllegalStateException(message)

/**
 * Appends to a site's hash chain.
 *
 * Pure functions over an explicit [ChainHead] — no ambient state, no singletons. Whoever
 * calls this is responsible for making read-head → append → write-head atomic. On Android
 * that is a single Room transaction guarded by a unique index on `(siteId, seq)`, so even a
 * race between two coroutines cannot mint two certificates into one slot: the database
 * refuses the second write rather than trusting the code path that produced it.
 */
object CertificateChain {

    /**
     * @throws ChainAppendException if the sequence space for this site is exhausted. At one
     *   certificate per second it would take 136 years to reach, but an undefined overflow
     *   in the code that guarantees tamper-evidence is not something to leave open.
     */
    fun nextSeq(head: ChainHead): Long {
        if (head.lastSeq >= Attestation.MAX_SEQ) {
            throw ChainAppendException(
                "certificate sequence space exhausted for site ${head.siteId} " +
                    "(${Attestation.MAX_SEQ}); roll to a new site key epoch",
            )
        }
        return head.lastSeq + 1L
    }

    /** Builds the next attestation without signing it. Useful for previewing and testing. */
    fun buildNext(
        head: ChainHead,
        workerIdHash: ByteArray,
        moduleCode: Int,
        scorePermille: Int,
        medianLatencyMs: Long,
        outcomeFlags: OutcomeFlags,
        issuedAtEpochMin: Long,
    ): Attestation = Attestation(
        siteId = head.siteId,
        seq = nextSeq(head),
        workerIdHash = workerIdHash,
        moduleCode = moduleCode,
        scorePermille = scorePermille,
        medianLatencyMs = medianLatencyMs,
        outcomeFlags = outcomeFlags,
        issuedAtEpochMin = issuedAtEpochMin,
        prevRecordHash = if (head.isEmpty) Sha256.ZERO else head.lastRecordHash,
    )

    /** Builds, signs and returns the certificate plus the advanced head. */
    fun issue(
        head: ChainHead,
        sitePrivateKey: ByteArray,
        workerIdHash: ByteArray,
        moduleCode: Int,
        scorePermille: Int,
        medianLatencyMs: Long,
        outcomeFlags: OutcomeFlags,
        issuedAtEpochMin: Long,
    ): ChainAppend {
        val attestation = buildNext(
            head = head,
            workerIdHash = workerIdHash,
            moduleCode = moduleCode,
            scorePermille = scorePermille,
            medianLatencyMs = medianLatencyMs,
            outcomeFlags = outcomeFlags,
            issuedAtEpochMin = issuedAtEpochMin,
        )
        val signed = AttestationCodec.sign(attestation, sitePrivateKey)
        return ChainAppend(
            signed = signed,
            newHead = ChainHead(head.siteId, attestation.seq, signed.recordHash),
        )
    }

    /** Advances a head to include an already-signed record, checking linkage first. */
    fun advance(head: ChainHead, signed: SignedAttestation): ChainHead {
        val attestation = signed.attestation
        if (attestation.siteId != head.siteId) {
            throw ChainAppendException(
                "record belongs to site ${attestation.siteId}, chain head is ${head.siteId}",
            )
        }
        val expectedSeq = head.lastSeq + 1L
        if (attestation.seq != expectedSeq) {
            throw ChainAppendException(
                "out-of-order append for site ${head.siteId}: expected seq $expectedSeq, got ${attestation.seq}",
            )
        }
        val expectedPrev = if (head.isEmpty) Sha256.ZERO else head.lastRecordHash
        if (!Sha256.constantTimeEquals(expectedPrev, attestation.prevRecordHash)) {
            throw ChainAppendException(
                "broken link at seq ${attestation.seq} for site ${head.siteId}: " +
                    "record points at ${Hex.encodePrefix(attestation.prevRecordHash, 6)}.. " +
                    "but the chain tip is ${Hex.encodePrefix(expectedPrev, 6)}..",
            )
        }
        return ChainHead(head.siteId, attestation.seq, signed.recordHash)
    }
}
