package org.jaagruk.core.cert

import org.jaagruk.core.crypto.Sha256
import org.jaagruk.core.util.Hex

/**
 * Bit flags describing the conditions a certificate was earned under.
 *
 * These travel inside the signed payload, so they cannot be edited after issuance. That is
 * what makes "certified in basic mode without a real buddy" an auditable fact rather than a
 * claim, and it is why an inspector can tell a full site-scanned two-phone drill apart from
 * a fallback single-player run.
 *
 * Bits 6 and 7 are reserved and **must** be zero. A decoder that meets a reserved bit
 * rejects the payload instead of ignoring it, so a payload forged against a future format
 * cannot be replayed against today's verifier.
 */
@JvmInline
value class OutcomeFlags private constructor(val bits: Int) {

    val passed: Boolean get() = has(PASSED)
    val hesitationFlagged: Boolean get() = has(HESITATION)
    val buddyDrill: Boolean get() = has(BUDDY_DRILL)
    val siteScannedAr: Boolean get() = has(SITE_SCANNED_AR)
    val refresher: Boolean get() = has(REFRESHER)
    val assistedMode: Boolean get() = has(ASSISTED_MODE)

    fun has(flag: Int): Boolean = (bits and flag) != 0

    fun with(flag: Int): OutcomeFlags = fromBits(bits or flag)

    fun without(flag: Int): OutcomeFlags = fromBits(bits and flag.inv() and ALL_MASK)

    override fun toString(): String = buildString {
        append("OutcomeFlags(0x%02X".format(bits))
        val names = mutableListOf<String>()
        if (passed) names += "passed"
        if (hesitationFlagged) names += "hesitation"
        if (buddyDrill) names += "buddy"
        if (siteScannedAr) names += "siteScanned"
        if (refresher) names += "refresher"
        if (assistedMode) names += "assisted"
        if (names.isNotEmpty()) append(" ").append(names.joinToString("|"))
        append(")")
    }

    companion object {
        const val PASSED: Int = 1 shl 0
        const val HESITATION: Int = 1 shl 1
        const val BUDDY_DRILL: Int = 1 shl 2
        const val SITE_SCANNED_AR: Int = 1 shl 3
        const val REFRESHER: Int = 1 shl 4
        const val ASSISTED_MODE: Int = 1 shl 5

        /** Bits 6..7. Any of these set means the payload is not for this format version. */
        const val RESERVED_MASK: Int = (1 shl 6) or (1 shl 7)
        const val ALL_MASK: Int = 0x3F

        val NONE: OutcomeFlags = OutcomeFlags(0)

        fun of(vararg flags: Int): OutcomeFlags = fromBits(flags.fold(0) { acc, f -> acc or f })

        /** @throws IllegalArgumentException if out of `u8` range or a reserved bit is set. */
        fun fromBits(bits: Int): OutcomeFlags {
            require(bits in 0..0xFF) { "outcomeFlags out of u8 range: $bits" }
            require((bits and RESERVED_MASK) == 0) {
                "reserved outcomeFlags bits set (0x%02X); payload targets a newer format".format(bits)
            }
            return OutcomeFlags(bits)
        }
    }
}

/**
 * The one and only signed object in the certification scheme.
 *
 * Everything an offline verifier needs is here, and nothing more. In particular the
 * plaintext `workerId` is **not** here — only [workerIdHash]. A dropped certificate card
 * therefore discloses no identity: an inspector confirms the holder by hashing the ID from
 * the physical card and comparing, which keeps the QR useless to anyone who finds it.
 *
 * Field encoding is fixed by `docs/ARCHITECTURE.md` §2.1 and pinned by committed test
 * vectors that the Python backend is checked against.
 */
class Attestation(
    val formatVersion: Int = FORMAT_VERSION,
    val siteId: String,
    val seq: Long,
    val workerIdHash: ByteArray,
    val moduleCode: Int,
    val scorePermille: Int,
    val medianLatencyMs: Long,
    val outcomeFlags: OutcomeFlags,
    val issuedAtEpochMin: Long,
    val prevRecordHash: ByteArray,
) {

    init {
        require(formatVersion == FORMAT_VERSION) {
            "unsupported attestation format version $formatVersion (this build speaks $FORMAT_VERSION)"
        }
        require(siteId.isNotBlank()) { "siteId must not be blank" }
        val siteIdBytes = siteId.toByteArray(Charsets.UTF_8).size
        require(siteIdBytes <= MAX_SITE_ID_BYTES) {
            "siteId is $siteIdBytes bytes; the QR budget allows at most $MAX_SITE_ID_BYTES"
        }
        require(seq in MIN_SEQ..MAX_SEQ) {
            "seq must be in $MIN_SEQ..$MAX_SEQ (got $seq); roll the site key epoch if exhausted"
        }
        require(workerIdHash.size == Sha256.SIZE_BYTES) {
            "workerIdHash must be ${Sha256.SIZE_BYTES} bytes, got ${workerIdHash.size}"
        }
        require(moduleCode in MIN_MODULE_CODE..MAX_MODULE_CODE) {
            "moduleCode must be in $MIN_MODULE_CODE..$MAX_MODULE_CODE (0 is reserved), got $moduleCode"
        }
        require(scorePermille in 0..MAX_SCORE_PERMILLE) {
            "scorePermille must be 0..$MAX_SCORE_PERMILLE, got $scorePermille"
        }
        require(medianLatencyMs in 0..MAX_U32) {
            "medianLatencyMs must fit in u32, got $medianLatencyMs"
        }
        require(issuedAtEpochMin in 0..MAX_U32) {
            "issuedAtEpochMin must fit in u32, got $issuedAtEpochMin"
        }
        require(prevRecordHash.size == Sha256.SIZE_BYTES) {
            "prevRecordHash must be ${Sha256.SIZE_BYTES} bytes, got ${prevRecordHash.size}"
        }
        require(seq != MIN_SEQ || Sha256.isZero(prevRecordHash)) {
            "the genesis certificate (seq=$MIN_SEQ) must carry a zero prevRecordHash"
        }
        require(seq == MIN_SEQ || !Sha256.isZero(prevRecordHash)) {
            "a non-genesis certificate (seq=$seq) must carry a non-zero prevRecordHash"
        }
    }

    val isGenesis: Boolean get() = seq == MIN_SEQ

    val scorePercent: Double get() = scorePermille / 10.0

    fun copy(
        siteId: String = this.siteId,
        seq: Long = this.seq,
        workerIdHash: ByteArray = this.workerIdHash,
        moduleCode: Int = this.moduleCode,
        scorePermille: Int = this.scorePermille,
        medianLatencyMs: Long = this.medianLatencyMs,
        outcomeFlags: OutcomeFlags = this.outcomeFlags,
        issuedAtEpochMin: Long = this.issuedAtEpochMin,
        prevRecordHash: ByteArray = this.prevRecordHash,
    ): Attestation = Attestation(
        formatVersion = formatVersion,
        siteId = siteId,
        seq = seq,
        workerIdHash = workerIdHash,
        moduleCode = moduleCode,
        scorePermille = scorePermille,
        medianLatencyMs = medianLatencyMs,
        outcomeFlags = outcomeFlags,
        issuedAtEpochMin = issuedAtEpochMin,
        prevRecordHash = prevRecordHash,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attestation) return false
        return formatVersion == other.formatVersion &&
            siteId == other.siteId &&
            seq == other.seq &&
            workerIdHash.contentEquals(other.workerIdHash) &&
            moduleCode == other.moduleCode &&
            scorePermille == other.scorePermille &&
            medianLatencyMs == other.medianLatencyMs &&
            outcomeFlags == other.outcomeFlags &&
            issuedAtEpochMin == other.issuedAtEpochMin &&
            prevRecordHash.contentEquals(other.prevRecordHash)
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + siteId.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + workerIdHash.contentHashCode()
        result = 31 * result + moduleCode
        result = 31 * result + scorePermille
        result = 31 * result + medianLatencyMs.hashCode()
        result = 31 * result + outcomeFlags.bits
        result = 31 * result + issuedAtEpochMin.hashCode()
        result = 31 * result + prevRecordHash.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Attestation(site=$siteId, seq=$seq, module=$moduleCode, score=$scorePermille, " +
            "latency=${medianLatencyMs}ms, flags=$outcomeFlags, issuedMin=$issuedAtEpochMin, " +
            "worker=${Hex.encodePrefix(workerIdHash, 4)}.., prev=${Hex.encodePrefix(prevRecordHash, 4)}..)"

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val MAX_SITE_ID_BYTES: Int = 16
        const val MIN_SEQ: Long = 1L
        const val MAX_SEQ: Long = 0xFFFF_FFFFL
        const val MIN_MODULE_CODE: Int = 1
        const val MAX_MODULE_CODE: Int = 255
        const val MAX_SCORE_PERMILLE: Int = 1000
        const val MAX_U32: Long = 0xFFFF_FFFFL
    }
}

/**
 * An [Attestation] plus its signature and the resulting chain link.
 *
 * [recordHash] is `SHA-256(canonical || signature)` — it commits to the signature as well
 * as the payload, so re-signing an identical payload with a different key produces a
 * different link and cannot be spliced into an existing chain.
 */
class SignedAttestation(
    val attestation: Attestation,
    val signature: ByteArray,
    val recordHash: ByteArray,
) {
    init {
        require(signature.size == org.jaagruk.core.crypto.Ed25519.SIGNATURE_SIZE) {
            "signature must be ${org.jaagruk.core.crypto.Ed25519.SIGNATURE_SIZE} bytes, got ${signature.size}"
        }
        require(recordHash.size == Sha256.SIZE_BYTES) {
            "recordHash must be ${Sha256.SIZE_BYTES} bytes, got ${recordHash.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedAttestation) return false
        return attestation == other.attestation &&
            signature.contentEquals(other.signature) &&
            recordHash.contentEquals(other.recordHash)
    }

    override fun hashCode(): Int {
        var result = attestation.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + recordHash.contentHashCode()
        return result
    }

    override fun toString(): String =
        "SignedAttestation($attestation, record=${Hex.encodePrefix(recordHash, 6)}..)"
}
