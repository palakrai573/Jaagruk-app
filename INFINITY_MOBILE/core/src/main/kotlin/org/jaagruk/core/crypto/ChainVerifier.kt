package org.jaagruk.core.crypto

import org.jaagruk.core.cert.Attestation
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.cert.QrCodec
import org.jaagruk.core.cert.SignedAttestation
import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.Hex

/**
 * Outcome of verifying one certificate.
 *
 * Deliberately not a boolean. "Valid signature but I have never synced this site's chain" is
 * the normal situation for a DGMS inspector scanning a certificate at a mine they are
 * visiting for the first time, and collapsing it into "invalid" would train inspectors to
 * ignore the tool. Each status says exactly how much trust is warranted.
 */
enum class ChainStatus {
    /** Signature valid and the record links correctly into the chain held locally. */
    VERIFIED,

    /** Signature valid; this verifier holds no chain copy to cross-check linkage against. */
    SIGNATURE_VALID_CHAIN_UNKNOWN,

    /** Signature valid but the predecessor link contradicts the held chain: insertion or fork. */
    BROKEN_LINK,

    /** Signature valid and links consistently, but sequence numbers are missing in between. */
    SEQUENCE_GAP,

    /** Signature verification failed: the payload was altered or signed by the wrong key. */
    BAD_SIGNATURE,

    /** No public key held for this site, so nothing can be asserted. */
    UNKNOWN_SITE_KEY,

    /** Not a decodable Jaagruk certificate at all. */
    MALFORMED,
    ;

    /** True only for states an inspector may treat as a pass. */
    val isTrustworthy: Boolean
        get() = this == VERIFIED || this == SIGNATURE_VALID_CHAIN_UNKNOWN

    /** True for states that indicate deliberate interference rather than missing data. */
    val indicatesTampering: Boolean
        get() = this == BROKEN_LINK || this == BAD_SIGNATURE
}

/** Machine-readable reason code. The UI maps these to localised strings; never show the enum. */
enum class VerificationReasonCode {
    DECODE_FAILED,
    UNSUPPORTED_FORMAT_VERSION,
    NO_PUBLIC_KEY_FOR_SITE,
    SIGNATURE_MISMATCH,
    RECORD_HASH_MISMATCH,
    NO_LOCAL_CHAIN_COPY,
    AHEAD_OF_LOCAL_CHAIN,
    PREDECESSOR_MISSING,
    PREDECESSOR_HASH_MISMATCH,
    DUPLICATE_SEQUENCE_DIFFERENT_RECORD,
    SEQUENCE_GAP_DETECTED,
    GENESIS_PREV_HASH_NOT_ZERO,
    ISSUED_IN_FUTURE,
    STATUTORY_VALIDITY_EXPIRED,
    LINK_OK,
    SIGNATURE_OK,
}

class VerificationReason(
    val code: VerificationReasonCode,
    val detail: String,
) {
    override fun toString(): String = "$code: $detail"

    override fun equals(other: Any?): Boolean =
        other is VerificationReason && other.code == code && other.detail == detail

    override fun hashCode(): Int = 31 * code.hashCode() + detail.hashCode()
}

/** Result of verifying a single certificate. */
class VerificationResult(
    val status: ChainStatus,
    val signed: SignedAttestation?,
    val reasons: List<VerificationReason>,
) {
    val attestation: Attestation? get() = signed?.attestation

    override fun toString(): String =
        "VerificationResult($status, ${reasons.joinToString("; ") { it.code.name }})"
}

/** Result of walking an entire site chain. */
class ChainAuditResult(
    val siteId: String,
    val recordsChecked: Int,
    val status: ChainStatus,
    val firstProblemSeq: Long?,
    val reasons: List<VerificationReason>,
) {
    val isClean: Boolean get() = status == ChainStatus.VERIFIED && firstProblemSeq == null

    override fun toString(): String =
        "ChainAuditResult($siteId, checked=$recordsChecked, $status, firstProblemSeq=$firstProblemSeq)"
}

/**
 * Read-only view of whatever chain data a verifier happens to hold.
 *
 * Implemented over Room on the phone and over Postgres on the server, with the same
 * verification code running against both. An inspector's phone with no synced data
 * legitimately returns null everywhere, and that produces
 * [ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN] rather than a failure.
 */
interface ChainView {
    /** Ed25519 public key for the site, or null if this verifier has never received it. */
    fun sitePublicKey(siteId: String): ByteArray?

    /** `recordHash` stored at [seq], or null if that slot is not held. */
    fun recordHashAt(siteId: String, seq: Long): ByteArray?

    /** Highest seq held for the site, or null if no records at all are held. */
    fun highestSeq(siteId: String): Long?

    /** Count of records held for the site. Used to distinguish a gap from a partial sync. */
    fun recordCount(siteId: String): Long
}

/** A [ChainView] that holds nothing — a freshly installed inspector app. */
object EmptyChainView : ChainView {
    override fun sitePublicKey(siteId: String): ByteArray? = null
    override fun recordHashAt(siteId: String, seq: Long): ByteArray? = null
    override fun highestSeq(siteId: String): Long? = null
    override fun recordCount(siteId: String): Long = 0L
}

/**
 * In-memory [ChainView], used by tests and by the backend's chain audit.
 *
 * @param publicKeys siteId -> Ed25519 public key
 * @param records siteId -> (seq -> recordHash)
 */
class InMemoryChainView(
    private val publicKeys: Map<String, ByteArray> = emptyMap(),
    private val records: Map<String, Map<Long, ByteArray>> = emptyMap(),
) : ChainView {
    override fun sitePublicKey(siteId: String): ByteArray? = publicKeys[siteId]

    override fun recordHashAt(siteId: String, seq: Long): ByteArray? = records[siteId]?.get(seq)

    override fun highestSeq(siteId: String): Long? = records[siteId]?.keys?.maxOrNull()

    override fun recordCount(siteId: String): Long = (records[siteId]?.size ?: 0).toLong()
}

/**
 * Verifies certificates offline.
 *
 * The whole point of the scheme is that this class needs nothing but the QR code and a site
 * public key. No server, no signal, no cached session. That is what makes it usable at a
 * mine gate in Dhanbad where there is no connectivity at all.
 */
object ChainVerifier {

    /** Convenience entry point for a scanner: raw QR text straight to a verdict. */
    fun verifyQr(qrText: String, view: ChainView): VerificationResult {
        val signed = try {
            QrCodec.decode(qrText)
        } catch (e: CanonicalFormatException) {
            return VerificationResult(
                status = ChainStatus.MALFORMED,
                signed = null,
                reasons = listOf(
                    VerificationReason(
                        VerificationReasonCode.DECODE_FAILED,
                        e.message ?: "the QR code could not be decoded",
                    ),
                ),
            )
        }
        return verify(signed, view)
    }

    fun verify(signed: SignedAttestation, view: ChainView): VerificationResult {
        val reasons = mutableListOf<VerificationReason>()
        val attestation = signed.attestation
        val siteId = attestation.siteId

        // 1. Do we even know this site's key?
        val publicKey = view.sitePublicKey(siteId)
        if (publicKey == null) {
            return VerificationResult(
                status = ChainStatus.UNKNOWN_SITE_KEY,
                signed = signed,
                reasons = listOf(
                    VerificationReason(
                        VerificationReasonCode.NO_PUBLIC_KEY_FOR_SITE,
                        "no public key held for site $siteId; connect once to fetch it",
                    ),
                ),
            )
        }

        // 2. Signature. Nothing else is worth checking until this passes.
        if (!AttestationCodec.verifySignature(signed, publicKey)) {
            return VerificationResult(
                status = ChainStatus.BAD_SIGNATURE,
                signed = signed,
                reasons = listOf(
                    VerificationReason(
                        VerificationReasonCode.SIGNATURE_MISMATCH,
                        "signature does not match site $siteId's key: the certificate was " +
                            "altered or was not issued by this site",
                    ),
                ),
            )
        }
        reasons += VerificationReason(
            VerificationReasonCode.SIGNATURE_OK,
            "Ed25519 signature valid for site $siteId",
        )

        // 3. Chain linkage, best-effort against whatever this device holds.
        val highestSeq = view.highestSeq(siteId)
        if (highestSeq == null) {
            reasons += VerificationReason(
                VerificationReasonCode.NO_LOCAL_CHAIN_COPY,
                "this device holds no chain records for site $siteId, so linkage was not cross-checked",
            )
            return VerificationResult(ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN, signed, reasons)
        }

        // 3a. A record we already hold at this seq must be byte-identical. Two different
        //     records in one slot is a fork, whatever produced it.
        val existingAtSeq = view.recordHashAt(siteId, attestation.seq)
        if (existingAtSeq != null && !Sha256.constantTimeEquals(existingAtSeq, signed.recordHash)) {
            reasons += VerificationReason(
                VerificationReasonCode.DUPLICATE_SEQUENCE_DIFFERENT_RECORD,
                "site $siteId already holds a different record at seq ${attestation.seq} " +
                    "(${Hex.encodePrefix(existingAtSeq, 6)}.. vs ${Hex.encodePrefix(signed.recordHash, 6)}..)",
            )
            return VerificationResult(ChainStatus.BROKEN_LINK, signed, reasons)
        }

        // 3b. Genesis.
        if (attestation.isGenesis) {
            if (!Sha256.isZero(attestation.prevRecordHash)) {
                // Unreachable via the constructor, kept so a future decoder change cannot
                // slip a non-zero genesis link past this check.
                reasons += VerificationReason(
                    VerificationReasonCode.GENESIS_PREV_HASH_NOT_ZERO,
                    "genesis record for site $siteId does not carry a zero predecessor hash",
                )
                return VerificationResult(ChainStatus.BROKEN_LINK, signed, reasons)
            }
            reasons += VerificationReason(
                VerificationReasonCode.LINK_OK,
                "genesis record for site $siteId",
            )
            return VerificationResult(ChainStatus.VERIFIED, signed, reasons)
        }

        // 3c. Non-genesis: find the predecessor.
        val predecessorSeq = attestation.seq - 1L
        val predecessorHash = view.recordHashAt(siteId, predecessorSeq)

        if (predecessorHash == null) {
            return if (predecessorSeq > highestSeq) {
                // The certificate is newer than anything synced here. Normal for a device
                // that has not talked to the site in weeks.
                reasons += VerificationReason(
                    VerificationReasonCode.AHEAD_OF_LOCAL_CHAIN,
                    "certificate seq ${attestation.seq} is ahead of the newest record held " +
                        "for site $siteId (seq $highestSeq); sync to cross-check linkage",
                )
                VerificationResult(ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN, signed, reasons)
            } else {
                // We hold records past this point but not the predecessor: something was
                // removed from the middle of our copy.
                reasons += VerificationReason(
                    VerificationReasonCode.PREDECESSOR_MISSING,
                    "record at seq $predecessorSeq is missing from site $siteId's chain " +
                        "even though seq $highestSeq is held",
                )
                reasons += VerificationReason(
                    VerificationReasonCode.SEQUENCE_GAP_DETECTED,
                    "gap around seq $predecessorSeq",
                )
                VerificationResult(ChainStatus.SEQUENCE_GAP, signed, reasons)
            }
        }

        if (!Sha256.constantTimeEquals(predecessorHash, attestation.prevRecordHash)) {
            reasons += VerificationReason(
                VerificationReasonCode.PREDECESSOR_HASH_MISMATCH,
                "certificate at seq ${attestation.seq} points at " +
                    "${Hex.encodePrefix(attestation.prevRecordHash, 6)}.. but site $siteId's " +
                    "record at seq $predecessorSeq hashes to ${Hex.encodePrefix(predecessorHash, 6)}..",
            )
            return VerificationResult(ChainStatus.BROKEN_LINK, signed, reasons)
        }

        reasons += VerificationReason(
            VerificationReasonCode.LINK_OK,
            "links correctly to seq $predecessorSeq",
        )
        return VerificationResult(ChainStatus.VERIFIED, signed, reasons)
    }

    /**
     * Walks a full site chain and reports the first problem.
     *
     * [records] must be ordered by ascending seq. Used by the dashboard's "verify this
     * site's entire ledger" action and by the server after ingesting a batch. Stops at the
     * first break because everything after an unexplained break is untrustworthy anyway.
     */
    fun auditChain(
        siteId: String,
        sitePublicKey: ByteArray,
        records: List<SignedAttestation>,
    ): ChainAuditResult {
        val reasons = mutableListOf<VerificationReason>()

        if (records.isEmpty()) {
            return ChainAuditResult(
                siteId = siteId,
                recordsChecked = 0,
                status = ChainStatus.VERIFIED,
                firstProblemSeq = null,
                reasons = listOf(
                    VerificationReason(
                        VerificationReasonCode.LINK_OK,
                        "site $siteId has issued no certificates yet",
                    ),
                ),
            )
        }

        var expectedSeq = 1L
        var expectedPrev = Sha256.ZERO
        var checked = 0

        for (record in records) {
            val attestation = record.attestation
            checked++

            if (attestation.siteId != siteId) {
                reasons += VerificationReason(
                    VerificationReasonCode.PREDECESSOR_HASH_MISMATCH,
                    "record at seq ${attestation.seq} belongs to site ${attestation.siteId}, not $siteId",
                )
                return ChainAuditResult(siteId, checked, ChainStatus.BROKEN_LINK, attestation.seq, reasons)
            }

            if (!AttestationCodec.verifySignature(record, sitePublicKey)) {
                reasons += VerificationReason(
                    VerificationReasonCode.SIGNATURE_MISMATCH,
                    "signature invalid at seq ${attestation.seq}",
                )
                return ChainAuditResult(siteId, checked, ChainStatus.BAD_SIGNATURE, attestation.seq, reasons)
            }

            if (attestation.seq != expectedSeq) {
                reasons += VerificationReason(
                    VerificationReasonCode.SEQUENCE_GAP_DETECTED,
                    "expected seq $expectedSeq but found ${attestation.seq}: " +
                        "${attestation.seq - expectedSeq} record(s) missing from site $siteId",
                )
                return ChainAuditResult(siteId, checked, ChainStatus.SEQUENCE_GAP, expectedSeq, reasons)
            }

            if (!Sha256.constantTimeEquals(expectedPrev, attestation.prevRecordHash)) {
                reasons += VerificationReason(
                    VerificationReasonCode.PREDECESSOR_HASH_MISMATCH,
                    "broken link at seq ${attestation.seq}: points at " +
                        "${Hex.encodePrefix(attestation.prevRecordHash, 6)}.. but the previous " +
                        "record hashes to ${Hex.encodePrefix(expectedPrev, 6)}..",
                )
                return ChainAuditResult(siteId, checked, ChainStatus.BROKEN_LINK, attestation.seq, reasons)
            }

            expectedPrev = record.recordHash
            expectedSeq = attestation.seq + 1L
        }

        reasons += VerificationReason(
            VerificationReasonCode.LINK_OK,
            "all $checked record(s) for site $siteId verified end to end",
        )
        return ChainAuditResult(siteId, checked, ChainStatus.VERIFIED, null, reasons)
    }
}
