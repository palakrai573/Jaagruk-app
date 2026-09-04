package org.jaagruk.safety.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.jaagruk.core.util.Hex
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.db.SyncQueueEntity
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.sync.api.AssessmentUpload
import org.jaagruk.safety.sync.api.CertificateUpload
import org.jaagruk.safety.sync.api.DeviceRegisterRequest
import org.jaagruk.safety.sync.api.HazardUpload
import org.jaagruk.safety.sync.api.JaagrukApi
import org.jaagruk.safety.sync.api.ProgressUpload
import org.jaagruk.safety.sync.api.SessionStore
import org.jaagruk.safety.sync.api.SyncBatchRequest
import org.jaagruk.safety.sync.api.SyncBatchResponse
import org.jaagruk.safety.sync.api.SyncItemResult
import org.jaagruk.safety.sync.api.WorkerRegisterRequest
import retrofit2.Response
import java.io.IOException
import java.util.UUID

/**
 * Drains the outbound queue and pulls down the roster.
 *
 * The contract this worker keeps, in order of importance:
 *
 *  1. **It never destroys a record.** A queue entry is removed only when the server has confirmed it
 *     — accepted, duplicate, or quarantined. "Quarantined" still counts: the server stored the
 *     certificate and flagged the chain break. Discarding a broken-link certificate would destroy
 *     exactly the tamper evidence the chain exists to preserve.
 *  2. **It distinguishes "rejected" from "failed".** A 5xx, a timeout, a device not yet registered,
 *     a missing site key — all retryable, and none of them count toward the abandon threshold. Only
 *     a definite server verdict on a specific item does. A fortnight of bad uplink must not cost a
 *     worker their certificate.
 *  3. **It is safe to run twice.** Each batch carries a `client_batch_id`; replaying it returns the
 *     stored response and ingests nothing. Each item carries an idempotency key that is identical
 *     whether the record arrives by direct upload or relayed through a supervisor's handset.
 *
 * Nothing in the training path waits on this. Drills run, are scored, and produce signed
 * certificates with no network at all; this worker only hands over what has already happened.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val database: JaagrukDatabase,
    private val api: JaagrukApi,
    private val session: SessionStore,
    private val keyStore: SiteKeyStore,
    private val deviceProfile: DeviceProfile,
    private val siteRepository: SiteRepository,
    private val assessments: AssessmentRepository,
    private val payloads: SyncPayloadFactory,
    private val connectivity: ConnectivityObserver,
    private val statusProvider: SyncStatusProvider,
    private val timeSync: TimeSyncTracker,
) : CoroutineWorker(appContext, params) {

    private val queue = database.syncQueueDao()
    private val certificateDao = database.certificateDao()
    private val runDao = database.assessmentRunDao()
    private val hazardDao = database.hazardTagDao()
    private val workerDao = database.workerDao()

    override suspend fun doWork(): Result {
        if (!connectivity.current().isOnline) {
            // Not a failure. WorkManager re-runs this when its network constraint is next satisfied.
            statusProvider.onPassSkipped()
            return Result.success()
        }

        if (!session.isAuthenticated) {
            // A supervisor has to sign in once per posting for uploads to be authorised. Until then
            // the queue simply accumulates, which is the designed behaviour, not a stalled state.
            Log.i(TAG, "no supervisor session; leaving ${pendingCount()} record(s) queued")
            statusProvider.onPassSkipped()
            return Result.success()
        }

        statusProvider.onPassStarted()

        return try {
            ensureDeviceRegistered()

            // Before the queue, not after. A certificate or an assessment naming a worker the server
            // has never heard of is rejected — retryably, so the device would keep re-sending it
            // forever while the roster row that would have fixed it sat in the database untouched.
            pushOfflineEnrolments()

            var uploaded = 0
            var pass = 0
            while (pass < MAX_BATCHES_PER_RUN) {
                val batch = queue.ready(System.currentTimeMillis(), BATCH_SIZE)
                if (batch.isEmpty()) break
                val result = uploadBatch(batch) ?: break
                uploaded += result
                pass++
            }

            downSync()

            statusProvider.onPassSucceeded(uploaded)
            Result.success()
        } catch (e: RetryableSyncFailure) {
            Log.i(TAG, "sync deferred: ${e.message}")
            statusProvider.onPassFailed(e.message)
            Result.retry()
        } catch (e: IOException) {
            // The ordinary case at a mine portal. Retry with WorkManager's backoff; queue untouched.
            statusProvider.onPassFailed("network unavailable")
            Result.retry()
        } catch (e: Exception) {
            // Anything unexpected still must not lose the queue. Retrying is the conservative
            // choice: the worst outcome is a wasted attempt, versus records stranded forever.
            Log.e(TAG, "unexpected sync failure", e)
            statusProvider.onPassFailed(e.message ?: e::class.java.simpleName)
            Result.retry()
        }
    }

    private suspend fun pendingCount(): Int = queue.ready(Long.MAX_VALUE, 1_000).size

    /**
     * Hands over workers enrolled on this handset while it had no uplink.
     *
     * Not part of the batch queue, and deliberately so. The queue exists to carry *records of things
     * that happened* — a run, a certificate, a hazard — under an idempotency key, and it abandons an
     * item after a definite server verdict. A roster row is not that kind of record: it is state that
     * should converge, so the right behaviour on a conflict is "the server already has it, move on"
     * rather than "abandon it".
     *
     * The PIN is not sent. It never leaves the handset.
     *
     * A single failure here does not fail the pass. The records in the queue are worth more than the
     * roster row, and a worker whose row is late still trains, is scored and gets a signed
     * certificate — the row catches up on the next pass.
     */
    private suspend fun pushOfflineEnrolments() {
        val pending = workerDao.notYetOnServer(WorkerRepository.UPLOAD_BATCH)
        if (pending.isEmpty()) return

        for (worker in pending) {
            val response = try {
                api.registerWorker(
                    WorkerRegisterRequest(
                        id = worker.workerId,
                        siteId = worker.siteId,
                        fullName = worker.fullName,
                        preferredLanguage = worker.preferredLanguage,
                        pictogramMode = worker.pictogramMode,
                    ),
                )
            } catch (e: IOException) {
                // No uplink mid-pass. Leave the flag alone and stop trying this time round.
                Log.i(TAG, "worker enrolment upload deferred: ${e.message}")
                return
            }

            when {
                response.isSuccessful -> {
                    workerDao.markServerSynced(worker.workerId)
                    Log.i(TAG, "enrolled ${worker.workerId} on the server")
                }

                // Already there. That is the desired end state, so record it as done rather than
                // re-posting the same row on every pass for the life of the handset.
                response.code() == HTTP_CONFLICT -> {
                    workerDao.markServerSynced(worker.workerId)
                }

                // A definite refusal: the id or the name is not something the server will accept.
                // Left unsynced and logged rather than retried forever or silently dropped — the
                // supervisor screen shows the outstanding count, which is what surfaces it.
                response.code() in 400..499 -> {
                    Log.w(
                        TAG,
                        "server refused worker ${worker.workerId} with ${response.code()}; " +
                            "left for a supervisor to correct",
                    )
                }

                // 5xx or anything else: server-side and transient. Try again next pass.
                else -> {
                    Log.i(TAG, "worker enrolment deferred, server said ${response.code()}")
                    return
                }
            }
        }
    }

    /** Raised for conditions where retrying later is right and the queue must be preserved. */
    private class RetryableSyncFailure(message: String) : Exception(message)

    // -----------------------------------------------------------------------
    // Device registration
    // -----------------------------------------------------------------------

    /**
     * Registers this handset and its site public key.
     *
     * The server refuses uploads from an unknown device with a 403 whose message tells the device to
     * keep its queue. Registering here rather than at login means a handset that was enrolled offline
     * self-registers the first time it sees the network, with no supervisor action.
     */
    private suspend fun ensureDeviceRegistered() {
        if (deviceProfile.isDeviceRegistered()) return

        val siteId = keyStore.siteId ?: deviceProfile.activeSiteId() ?: return
        val sitePublicKey = keyStore.sitePublicKey() ?: return

        keyStore.ensureDeviceAttestationKey()

        val request = DeviceRegisterRequest(
            deviceId = deviceProfile.deviceId(),
            siteId = siteId,
            sitePublicKeyHex = Hex.encode(sitePublicKey),
            attestPublicKeyHex = keyStore.deviceAttestationPublicKey()?.let(Hex::encode),
            keyEpoch = keyStore.keyEpoch,
            model = deviceProfile.model,
            androidRelease = deviceProfile.androidRelease,
            appVersion = deviceProfile.appVersion,
        )

        val response = api.registerDevice(request)
        when {
            response.isSuccessful -> {
                deviceProfile.markDeviceRegistered()
                Log.i(TAG, "device registered for site $siteId")
            }

            response.code() == HTTP_CONFLICT -> {
                // Already registered under a different session. Nothing to do, and definitely not
                // an error worth blocking uploads over.
                deviceProfile.markDeviceRegistered()
            }

            response.code() in RETRYABLE_CODES ->
                throw RetryableSyncFailure("device registration deferred (${response.code()})")

            else -> Log.w(
                TAG,
                "device registration refused (${response.code()}); uploads will be attempted anyway",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Upload
    // -----------------------------------------------------------------------

    /** @return records confirmed by the server, or null when the batch could not be built. */
    private suspend fun uploadBatch(entries: List<SyncQueueEntity>): Int? {
        val certificates = mutableListOf<CertificateUpload>()
        val assessmentUploads = mutableListOf<AssessmentUpload>()
        val hazards = mutableListOf<HazardUpload>()
        val progress = mutableListOf<ProgressUpload>()

        // Entries whose underlying row has gone. Removed rather than retried forever: there is
        // nothing left to upload, and keeping them would block the queue head indefinitely.
        val orphaned = mutableListOf<Long>()
        val byKey = HashMap<String, SyncQueueEntity>()

        for (entry in entries) {
            byKey[entry.idempotencyKey] = entry
            when (val item = payloads.build(entry)) {
                is SyncPayloadFactory.Item.Certificate -> certificates += item.upload
                is SyncPayloadFactory.Item.Assessment -> assessmentUploads += item.upload
                is SyncPayloadFactory.Item.Hazard -> hazards += item.upload
                is SyncPayloadFactory.Item.Progress -> progress += item.upload
                null -> orphaned += entry.queueId
            }
        }

        if (orphaned.isNotEmpty()) {
            queue.remove(orphaned)
            Log.i(TAG, "dropped ${orphaned.size} queue entr(ies) whose record no longer exists")
        }

        val itemCount = certificates.size + assessmentUploads.size + hazards.size + progress.size
        if (itemCount == 0) return null

        val clientBatchId = UUID.randomUUID().toString()
        val deviceId = deviceProfile.deviceId()

        // The device signature covers the batch identity, not its contents: the certificates inside
        // already carry their own Ed25519 signatures, and the server re-verifies those. What this
        // adds is proof of *which handset* is uploading, which is what makes a stolen password
        // insufficient to inject records.
        val signaturePayload = "$deviceId|$clientBatchId|$itemCount".toByteArray(Charsets.UTF_8)
        val deviceSignature = keyStore.signWithDeviceKey(signaturePayload)?.let(Hex::encode)

        val response: Response<SyncBatchResponse> = api.uploadBatch(
            SyncBatchRequest(
                deviceId = deviceId,
                clientBatchId = clientBatchId,
                deviceSignatureHex = deviceSignature,
                certificates = certificates,
                assessments = assessmentUploads,
                hazards = hazards,
                progress = progress,
            ),
        )

        if (!response.isSuccessful) {
            handleBatchFailure(response.code(), entries)
            return null
        }

        val body = response.body() ?: throw RetryableSyncFailure("empty sync response body")
        timeSync.record(body.serverTimeSec)
        return applyResults(body, byKey)
    }

    /**
     * Reacts to a whole-batch failure.
     *
     * Nothing is abandoned here. A batch-level failure says nothing about any individual item's
     * validity — it means the request did not get processed — so every entry keeps its place and
     * backs off.
     */
    private suspend fun handleBatchFailure(code: Int, entries: List<SyncQueueEntity>) {
        val nowMs = System.currentTimeMillis()
        when (code) {
            HTTP_PAYLOAD_TOO_LARGE -> {
                // Smaller batches next pass. Recorded against the entries so the backoff applies.
                Log.w(TAG, "batch rejected as too large; will retry in smaller batches")
                entries.forEach { entry ->
                    queue.recordFailure(
                        queueId = entry.queueId,
                        nextAttemptAtMs = nowMs + SMALL_RETRY_DELAY_MS,
                        error = "batch too large",
                    )
                }
            }

            // Device not registered yet, or its registration was revoked. Explicitly retryable: the
            // server's own message instructs the device to keep its queue.
            HTTP_FORBIDDEN -> throw RetryableSyncFailure("device not authorised to sync yet (403)")

            HTTP_UNAUTHORIZED -> throw RetryableSyncFailure("supervisor session expired (401)")

            in RETRYABLE_CODES -> throw RetryableSyncFailure("server unavailable ($code)")

            else -> {
                entries.forEach { entry ->
                    queue.recordFailure(
                        queueId = entry.queueId,
                        nextAttemptAtMs = SyncKind.nextAttemptAt(nowMs, entry.attempts),
                        error = "HTTP $code",
                    )
                }
                Log.w(TAG, "batch rejected with HTTP $code; entries backed off")
            }
        }
    }

    /**
     * Applies per-item verdicts.
     *
     * Per item rather than per batch, because one malformed record must not cost a device the other
     * ninety-nine. A handset returning after six weeks offline is exactly the case this protects.
     */
    private suspend fun applyResults(
        body: SyncBatchResponse,
        byKey: Map<String, SyncQueueEntity>,
    ): Int {
        val settledQueueIds = mutableListOf<Long>()
        val certificateIds = mutableListOf<String>()
        val runIds = mutableListOf<String>()
        val hazardIds = mutableListOf<String>()
        val hazardsWithPendingMedia = mutableListOf<String>()
        var confirmed = 0

        for (item in body.results) {
            val entry = byKey[item.idempotencyKey] ?: continue
            when (item.status) {
                STATUS_ACCEPTED, STATUS_DUPLICATE, STATUS_QUARANTINED -> {
                    settledQueueIds += entry.queueId
                    confirmed++
                    recordSettled(entry, item, certificateIds, runIds, hazardIds, hazardsWithPendingMedia)
                    if (item.status == STATUS_QUARANTINED) {
                        // Stored server-side and flagged. Worth a log line because it means a chain
                        // break reached the server, which an officer will be asked about.
                        Log.w(
                            TAG,
                            "server quarantined ${item.kind} ${item.refId}: ${item.reason}",
                        )
                    }
                }

                STATUS_REJECTED -> if (item.retryable) {
                    queue.recordFailure(
                        queueId = entry.queueId,
                        nextAttemptAtMs = SyncKind.nextAttemptAt(
                            System.currentTimeMillis(),
                            entry.attempts,
                        ),
                        error = item.reason,
                    )
                } else {
                    // A definite verdict. Kept, marked abandoned, never resent — so it can be
                    // explained on the diagnostics screen rather than vanishing.
                    queue.abandon(entry.queueId, item.reason)
                    Log.w(TAG, "server rejected ${item.kind} ${item.refId}: ${item.reason}")
                }

                else -> Log.w(TAG, "unrecognised sync status '${item.status}'; leaving queued")
            }
        }

        if (settledQueueIds.isNotEmpty()) queue.remove(settledQueueIds)
        if (certificateIds.isNotEmpty()) certificateDao.markUploaded(certificateIds)
        if (runIds.isNotEmpty()) runDao.markUploaded(runIds)
        if (hazardIds.isNotEmpty()) {
            // Text confirmed. Media is tracked separately so a large photo on a weak uplink cannot
            // hold up the line of text that says an exit is blocked.
            hazardDao.markUploaded(hazardIds, mediaPending = false)
        }
        if (hazardsWithPendingMedia.isNotEmpty()) {
            hazardDao.markUploaded(hazardsWithPendingMedia, mediaPending = true)
        }

        if (body.replayed) {
            Log.i(TAG, "batch ${body.batchId} was a replay; nothing re-ingested")
        }
        return confirmed
    }

    private suspend fun recordSettled(
        entry: SyncQueueEntity,
        item: SyncItemResult,
        certificateIds: MutableList<String>,
        runIds: MutableList<String>,
        hazardIds: MutableList<String>,
        hazardsWithPendingMedia: MutableList<String>,
    ) {
        when (SyncKind.fromWireName(entry.kind)) {
            SyncKind.CERTIFICATE -> certificateIds += entry.refId
            SyncKind.ASSESSMENT -> runIds += entry.refId
            SyncKind.HAZARD -> {
                val hazard = hazardDao.find(entry.refId)
                val hasLocalMedia = hazard?.photoPath != null || hazard?.voiceNotePath != null
                if (hasLocalMedia) hazardsWithPendingMedia += entry.refId else hazardIds += entry.refId
            }

            // Progress has no "uploaded" column — the row is authoritative locally and simply
            // re-uploads on the next change. A relayed record has no local row at all: this handset
            // was a courier, and marking somebody else's certificate as uploaded here would be a
            // claim it has no standing to make.
            SyncKind.PROGRESS, SyncKind.RELAY, null -> Unit
        }
        if (item.reason != null && item.status == STATUS_DUPLICATE) {
            Log.d(TAG, "duplicate ${item.kind} ${item.refId}: ${item.reason}")
        }
    }

    // -----------------------------------------------------------------------
    // Down-sync
    // -----------------------------------------------------------------------

    /**
     * Pulls the roster, module catalog, chain head and every site key epoch.
     *
     * Every epoch, not just the active one: a device must be able to verify a certificate signed
     * under a previous key entirely offline, and rotating a key must never invalidate history.
     *
     * A failure here is logged and swallowed. The upload half already succeeded, and reporting the
     * whole pass as failed would trigger a retry that re-uploads nothing.
     */
    private suspend fun downSync() {
        val siteId = deviceProfile.activeSiteId() ?: keyStore.siteId ?: return
        try {
            val response = api.bootstrap(siteId = siteId, includeRoster = true)
            if (!response.isSuccessful) {
                Log.i(TAG, "bootstrap skipped (${response.code()})")
                return
            }
            val body = response.body() ?: return
            timeSync.record(body.serverTimeSec)

            val outcome = siteRepository.applyBootstrap(body)
            Log.i(
                TAG,
                "bootstrap: ${outcome.workersWritten} worker(s), ${outcome.modulesWritten} module(s), " +
                    "${outcome.keyEpochsSeen} key epoch(s), local seq ${outcome.localSeq} vs " +
                    "server ${outcome.serverSeq}",
            )

            // A site key may have arrived for the first time. Any pass that was stored without a
            // certificate can now be certified, which is what stops an enrolment gap costing a
            // worker a re-run.
            val scanned = siteRepository.isSiteScanned(siteId)
            val issued = assessments.issuePendingCertificates(siteId, scanned)
            if (issued > 0) Log.i(TAG, "minted $issued deferred certificate(s)")
        } catch (e: IOException) {
            Log.i(TAG, "bootstrap deferred: no usable connection")
        } catch (e: Exception) {
            Log.w(TAG, "bootstrap failed", e)
        }
    }

    companion object {
        private const val TAG = "SyncWorker"

        /** Unique work name, so a manual trigger cannot stack up alongside the periodic job. */
        const val UNIQUE_WORK_NAME: String = "jaagruk-sync"
        const val PERIODIC_WORK_NAME: String = "jaagruk-sync-periodic"

        /** Half the server's 100-item cap, leaving headroom for a large step payload. */
        const val BATCH_SIZE: Int = 50

        /** Bounded so one pass cannot run for minutes on a device that has been offline for weeks. */
        const val MAX_BATCHES_PER_RUN: Int = 6

        private const val SMALL_RETRY_DELAY_MS = 60_000L

        private const val STATUS_ACCEPTED = "accepted"
        private const val STATUS_DUPLICATE = "duplicate"
        private const val STATUS_QUARANTINED = "quarantined"
        private const val STATUS_REJECTED = "rejected"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409
        private const val HTTP_PAYLOAD_TOO_LARGE = 413

        /** Codes that mean "try again", never "this record is bad". */
        private val RETRYABLE_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
