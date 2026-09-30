package org.jaagruk.safety.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.jaagruk.core.cert.Attestation
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.cert.QrCodec
import org.jaagruk.core.cert.SignedAttestation
import org.jaagruk.core.crypto.ChainAppendException
import org.jaagruk.core.crypto.ChainAuditResult
import org.jaagruk.core.crypto.ChainHead
import org.jaagruk.core.crypto.ChainStatus
import org.jaagruk.core.crypto.ChainVerifier
import org.jaagruk.core.crypto.EmptyChainView
import org.jaagruk.core.crypto.InMemoryChainView
import org.jaagruk.core.crypto.VerificationReason
import org.jaagruk.core.crypto.VerificationReasonCode
import org.jaagruk.core.crypto.VerificationResult
import org.jaagruk.core.util.Hex
import org.jaagruk.core.util.TimeUnits
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.db.CertificateEntity
import org.jaagruk.safety.data.db.ChainHeadEntity
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.sync.SyncKind
import java.util.UUID

/**
 * Issues and verifies certificates.
 *
 * Three properties this class exists to guarantee:
 *
 *  1. **Issuance is atomic.** Building the record, storing it, advancing the chain head and
 *     enqueuing the upload happen in one Room transaction. A partial write would leave the local
 *     ledger describing a chain that does not exist.
 *  2. **One certificate per chain slot.** The unique index on `(siteId, seq)` decides the winner
 *     when two coroutines race — not whichever code path happens to run first.
 *  3. **Verification needs nothing but the QR and a public key.** No server, no signal, no cached
 *     session. That is the whole point of signing certificates rather than looking them up.
 */
class CertificateRepository(
    private val database: JaagrukDatabase,
    private val keyStore: SiteKeyStore,
    private val clock: WallClock,
) {

    private val certificates = database.certificateDao()
    private val heads = database.chainHeadDao()
    private val sites = database.siteDao()
    private val queue = database.syncQueueDao()
    private val workers = database.workerDao()

    /** Outcome of an issuance attempt. Every failure is actionable rather than a bare error. */
    sealed interface IssueResult {
        data class Issued(val certificate: CertificateEntity, val signed: SignedAttestation) :
            IssueResult

        /**
         * No site signing key on this handset.
         *
         * The caller keeps the run as passed-pending-certificate and the certificate is minted
         * automatically once a supervisor enrols a key. Discarding the pass would be the wrong
         * trade — the worker did the work.
         */
        data object NoSigningKey : IssueResult

        data class SequenceExhausted(val siteId: String) : IssueResult

        /** Another coroutine took the slot. The caller retries against the refreshed head. */
        data object SlotTaken : IssueResult

        data class Rejected(val reason: String) : IssueResult
    }

    fun observeForWorker(workerId: String): Flow<List<CertificateEntity>> =
        certificates.observeForWorker(workerId)

    suspend fun find(certId: String): CertificateEntity? = certificates.find(certId)

    /** The certificate minted from a run, or null when one was not — see `IssueResult.NoSigningKey`. */
    suspend fun findByRunId(runId: String): CertificateEntity? = certificates.findByRunId(runId)

    suspend fun pendingUpload(limit: Int): List<CertificateEntity> =
        certificates.pendingUpload(limit)

    suspend fun markUploaded(certIds: List<String>) = certificates.markUploaded(certIds)

    suspend fun observeChainHead(siteId: String): ChainHeadEntity? = heads.find(siteId)

    /** How many certificates this handset holds for a site. Shown on the supervisor screen. */
    suspend fun certificateCountForSite(siteId: String): Long = certificates.countForSite(siteId)

    /**
     * Mints a certificate for a passed run.
     *
     * [outcomeFlags] is built by the caller from what the assessment engine actually observed —
     * buddy drill, site-scanned AR, assisted mode — and is signed, so the conditions a certificate
     * claims cannot be overstated after the fact.
     */
    suspend fun issue(
        siteId: String,
        workerId: String,
        moduleCode: Int,
        scorePermille: Int,
        medianLatencyMs: Long,
        outcomeFlags: OutcomeFlags,
        runId: String?,
    ): IssueResult {
        if (!keyStore.hasSiteKey()) return IssueResult.NoSigningKey

        val worker = workers.find(workerId)
            ?: return IssueResult.Rejected("worker $workerId is not on this device's roster")

        val issuedAtSec = clock.epochSeconds()
        val issuedAtMin = TimeUnits.epochSecondsToMinutes(issuedAtSec)

        return try {
            database.withTransaction {
                val head = currentHead(siteId)

                val attestation = org.jaagruk.core.crypto.CertificateChain.buildNext(
                    head = head,
                    workerIdHash = Hex.decode(worker.workerIdHash),
                    moduleCode = moduleCode,
                    scorePermille = scorePermille,
                    medianLatencyMs = medianLatencyMs,
                    outcomeFlags = outcomeFlags,
                    issuedAtEpochMin = issuedAtMin,
                )

                val canonical = AttestationCodec.canonicalBytes(attestation)
                val signature = keyStore.signWithSiteKey(canonical)
                    ?: return@withTransaction IssueResult.NoSigningKey

                val signed = SignedAttestation(
                    attestation = attestation,
                    signature = signature,
                    recordHash = AttestationCodec.recordHash(canonical, signature),
                )
                val qrText = QrCodec.encode(signed)
                val recordHashHex = Hex.encode(signed.recordHash)

                val entity = CertificateEntity(
                    certId = UUID.randomUUID().toString(),
                    siteId = siteId,
                    seq = attestation.seq,
                    keyEpoch = keyStore.keyEpoch,
                    workerId = workerId,
                    workerIdHashHex = worker.workerIdHash,
                    moduleCode = moduleCode,
                    scorePermille = scorePermille,
                    medianLatencyMs = medianLatencyMs,
                    outcomeFlags = outcomeFlags.bits,
                    issuedAtSec = issuedAtSec,
                    prevRecordHashHex = Hex.encode(attestation.prevRecordHash),
                    recordHashHex = recordHashHex,
                    signatureHex = Hex.encode(signature),
                    qrText = qrText,
                    runId = runId,
                    uploaded = false,
                )

                certificates.insertOrThrow(entity)
                heads.upsert(
                    ChainHeadEntity(
                        siteId = siteId,
                        lastSeq = attestation.seq,
                        lastRecordHashHex = recordHashHex,
                        updatedAtSec = issuedAtSec,
                    ),
                )
                queue.enqueue(
                    SyncKind.CERTIFICATE.queueEntry(
                        refId = entity.certId,
                        // The record hash is a natural idempotency key: unique per certificate, and
                        // identical whether the record reaches the server by direct upload or
                        // relayed through a supervisor's handset. That is what collapses the two
                        // delivery paths onto one row server-side.
                        idempotencyKey = "cert:$recordHashHex",
                        payloadJson = SyncKind.certificatePayload(
                            qrText = qrText,
                            workerId = workerId,
                            moduleCode = moduleCode,
                            keyEpoch = entity.keyEpoch,
                            runId = runId,
                        ),
                        nowMs = System.currentTimeMillis(),
                    ),
                )

                IssueResult.Issued(entity, signed)
            }
        } catch (e: SQLiteConstraintException) {
            // The unique (siteId, seq) index fired: another coroutine minted into this slot first.
            IssueResult.SlotTaken
        } catch (e: ChainAppendException) {
            IssueResult.SequenceExhausted(siteId)
        } catch (e: IllegalArgumentException) {
            // An out-of-range field. Cannot happen with engine-produced input, and is reported
            // rather than swallowed so a scenario-authoring bug surfaces instead of hiding.
            IssueResult.Rejected(e.message ?: "the certificate failed validation")
        }
    }

    private suspend fun currentHead(siteId: String): ChainHead {
        val stored = heads.find(siteId)
        return if (stored == null || stored.lastSeq == 0L) {
            ChainHead.empty(siteId)
        } else {
            ChainHead(siteId, stored.lastSeq, Hex.decode(stored.lastRecordHashHex))
        }
    }

    // -----------------------------------------------------------------------
    // Verification
    // -----------------------------------------------------------------------

    /**
     * Verifies scanned QR text with no network.
     *
     * Returns a status, never a boolean. "Signature valid but this device holds no copy of that
     * site's chain" is the normal case for an inspector visiting an unsynced site, and reporting it
     * as invalid would teach inspectors to ignore the tool.
     */
    suspend fun verifyQr(qrText: String): VerificationResult {
        // Decoded first, with no I/O, purely to learn which site's snapshot is needed. A decode
        // failure is handed straight back to the verifier so the MALFORMED reason comes from one
        // place rather than being duplicated here.
        val signed = QrCodec.decodeOrNull(qrText)
            ?: return ChainVerifier.verifyQr(qrText, EmptyChainView)

        return ChainVerifier.verify(signed, snapshotFor(signed.attestation.siteId))
    }

    /** Confirms a scanned certificate belongs to the id printed on a worker's physical card. */
    fun matchesWorkerId(signed: SignedAttestation, candidateWorkerId: String): Boolean =
        AttestationCodec.matchesWorkerId(signed.attestation, candidateWorkerId)

    /** Walks a site's whole local ledger. Backs the supervisor self-audit screen. */
    suspend fun auditSite(siteId: String): ChainAuditResult {
        val publicKey = sites.find(siteId)?.publicKeyHex?.let(Hex::decode)
            ?: return ChainAuditResult(
                siteId = siteId,
                recordsChecked = 0,
                status = ChainStatus.UNKNOWN_SITE_KEY,
                firstProblemSeq = null,
                reasons = listOf(
                    VerificationReason(
                        VerificationReasonCode.NO_PUBLIC_KEY_FOR_SITE,
                        "no public key held for site $siteId; sync once to fetch it",
                    ),
                ),
            )

        val records = certificates.forSite(siteId).map(::toSignedAttestation)
        return ChainVerifier.auditChain(siteId, publicKey, records)
    }

    /**
     * Snapshots the small amount of chain state the verifier reads.
     *
     * [org.jaagruk.core.crypto.ChainView] is synchronous by design — it has to be usable from pure
     * unit tests — so suspending DAO calls cannot happen inside it. Snapshotting first also makes a
     * verification a pure function of one consistent view, rather than of whatever the database
     * looked like partway through the check.
     */
    private suspend fun snapshotFor(siteId: String): InMemoryChainView {
        val publicKey = sites.find(siteId)?.publicKeyHex?.let(Hex::decode)
        val rows = certificates.forSite(siteId)
        val hashes = rows.associate { it.seq to Hex.decode(it.recordHashHex) }

        return InMemoryChainView(
            publicKeys = publicKey?.let { mapOf(siteId to it) } ?: emptyMap(),
            records = if (hashes.isEmpty()) emptyMap() else mapOf(siteId to hashes),
        )
    }

    private fun toSignedAttestation(entity: CertificateEntity): SignedAttestation {
        val attestation = Attestation(
            siteId = entity.siteId,
            seq = entity.seq,
            workerIdHash = Hex.decode(entity.workerIdHashHex),
            moduleCode = entity.moduleCode,
            scorePermille = entity.scorePermille,
            medianLatencyMs = entity.medianLatencyMs,
            outcomeFlags = OutcomeFlags.fromBits(entity.outcomeFlags),
            issuedAtEpochMin = TimeUnits.epochSecondsToMinutes(entity.issuedAtSec),
            prevRecordHash = Hex.decode(entity.prevRecordHashHex),
        )
        return SignedAttestation(
            attestation = attestation,
            signature = Hex.decode(entity.signatureHex),
            recordHash = Hex.decode(entity.recordHashHex),
        )
    }
}
