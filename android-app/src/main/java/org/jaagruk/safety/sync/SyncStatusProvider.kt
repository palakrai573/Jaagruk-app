package org.jaagruk.safety.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.jaagruk.safety.data.db.JaagrukDatabase

/**
 * What the sync system is doing, in a form the UI can show honestly.
 *
 * The single most important thing this communicates is that **nothing is waiting on the network to
 * be safe**. A worker who sees "3 records waiting to upload" needs to understand that their training
 * is already recorded and signed, and the queue is a delivery detail. A status line that reads like a
 * failure would teach people to distrust a system that is working exactly as designed.
 *
 * The counts come from the database rather than from in-memory state, so they survive process death
 * and are correct on a cold start with no sync having run.
 */
class SyncStatusProvider(database: JaagrukDatabase) {

    private val queue = database.syncQueueDao()

    enum class Phase {
        /** Nothing queued. Everything this device holds has reached the server. */
        IDLE,

        /** Records are queued, waiting for connectivity or the next backoff window. */
        WAITING,

        /** A pass is running now. */
        UPLOADING,

        /** The last pass failed for a retryable reason. The queue is intact. */
        RETRYING,

        /** Items were rejected outright by the server and kept for diagnosis. */
        ATTENTION,
    }

    data class Status(
        val phase: Phase,
        val pending: Int,
        val abandoned: Int,
        val lastError: String?,
        val lastSuccessAtMs: Long?,
        val uploadedInLastPass: Int,
    ) {
        /**
         * True when there is nothing at all outstanding.
         *
         * Deliberately not called "healthy": a device with fifty queued records at the bottom of a
         * shaft is healthy too, and the UI wording follows from that.
         */
        val isFullySynced: Boolean get() = pending == 0 && abandoned == 0
    }

    private val runtime = MutableStateFlow(
        RuntimeState(
            uploading = false,
            lastError = null,
            lastSuccessAtMs = null,
            uploadedInLastPass = 0,
            hadRetryableFailure = false,
        ),
    )

    private data class RuntimeState(
        val uploading: Boolean,
        val lastError: String?,
        val lastSuccessAtMs: Long?,
        val uploadedInLastPass: Int,
        val hadRetryableFailure: Boolean,
    )

    val status: Flow<Status> = combine(
        queue.observePendingCount(),
        queue.observeAbandonedCount(),
        runtime,
    ) { pending, abandoned, state ->
        val phase = when {
            state.uploading -> Phase.UPLOADING
            abandoned > 0 -> Phase.ATTENTION
            pending > 0 && state.hadRetryableFailure -> Phase.RETRYING
            pending > 0 -> Phase.WAITING
            else -> Phase.IDLE
        }
        Status(
            phase = phase,
            pending = pending,
            abandoned = abandoned,
            lastError = state.lastError,
            lastSuccessAtMs = state.lastSuccessAtMs,
            uploadedInLastPass = state.uploadedInLastPass,
        )
    }

    fun onPassStarted() {
        runtime.value = runtime.value.copy(uploading = true)
    }

    fun onPassSucceeded(uploaded: Int) {
        runtime.value = RuntimeState(
            uploading = false,
            lastError = null,
            lastSuccessAtMs = System.currentTimeMillis(),
            uploadedInLastPass = uploaded,
            hadRetryableFailure = false,
        )
    }

    /**
     * A pass that failed for a reason worth retrying.
     *
     * The error text is kept for the diagnostics screen but is never the primary message. "No signal"
     * is not an error a worker needs to act on, and presenting it as one is how a functioning offline
     * app gets reported as broken.
     */
    fun onPassFailed(error: String?) {
        runtime.value = runtime.value.copy(
            uploading = false,
            lastError = error,
            hadRetryableFailure = true,
        )
    }

    fun onPassSkipped() {
        runtime.value = runtime.value.copy(uploading = false)
    }
}
