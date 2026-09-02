package org.jaagruk.safety.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns every background schedule in the app.
 *
 * Centralised so the trade-offs are visible together rather than scattered across call sites:
 *
 *  * **Sync is periodic *and* triggerable.** The periodic job catches the handset that came into
 *    Wi-Fi range while in a pocket. The one-shot trigger catches the supervisor who just tapped
 *    "sync now" and is standing there watching. Both use the same unique name family, so they cannot
 *    stack into a herd of overlapping passes.
 *  * **Backoff is exponential from thirty seconds.** Fifty handsets arriving at the site office at
 *    shift change must not retry in lockstep; WorkManager's own jitter plus the queue-level jitter in
 *    [SyncKind.nextAttemptAt] spreads them.
 *  * **Media waits for an unmetered connection; text does not.** Two different constraint sets, which
 *    is the whole reason they are two workers.
 */
class SyncScheduler(private val context: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Installs the recurring jobs. Idempotent, so calling it on every app start is correct.
     *
     * `KEEP` rather than `UPDATE` for the periodic sync: replacing it would reset its interval every
     * launch, and on a handset that is opened twenty times a shift the job would never actually come
     * due.
     */
    fun ensurePeriodicWork() {
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            MediaUploadWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MediaUploadWorker>(
                MEDIA_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        // Unmetered, and not while the battery is low: a hazard photo is not worth
                        // the last 10 % of a shared handset's charge at the bottom of a shaft.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )

        ensureRefresherCheck()
    }

    /**
     * Runs a sync pass as soon as a network is available.
     *
     * `APPEND_OR_REPLACE` on a distinct unique name: a second tap while a pass is running replaces
     * the queued follow-up rather than launching a parallel pass that would fight over the same
     * queue rows.
     */
    fun requestSyncNow() {
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
        Log.i(TAG, "sync requested")
    }

    fun requestMediaUploadNow() {
        workManager.enqueueUniqueWork(
            "${MediaUploadWorker.UNIQUE_WORK_NAME}-now",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * Re-arms the refresher check.
     *
     * The schedule itself lives in the database as timestamps, so this job only decides *when to
     * look*. A missed run therefore delays a prompt and can never corrupt or lose a schedule — which
     * is what makes the retention model correct on a handset that was switched off for a month.
     */
    fun ensureRefresherCheck() {
        workManager.enqueueUniquePeriodicWork(
            RefresherReminderWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefresherReminderWorker>(
                REFRESHER_CHECK_HOURS,
                TimeUnit.HOURS,
            ).build(),
        )
    }

    /** Runs the refresher check immediately. Used after a boot and after a completed run. */
    fun checkRefreshersNow() {
        workManager.enqueueUniqueWork(
            "${RefresherReminderWorker.UNIQUE_WORK_NAME}-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefresherReminderWorker>().build(),
        )
    }

    /**
     * Cancels everything. Used when a handset is handed on to another site.
     *
     * Deliberately does not touch the queue or the database. Cancelling background work must not
     * discard a worker's unsynced training record — that is the one thing this app must never do.
     */
    fun cancelAll() {
        workManager.cancelUniqueWork(SyncWorker.PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(SyncWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(MediaUploadWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(RefresherReminderWorker.UNIQUE_WORK_NAME)
    }

    private companion object {
        const val TAG = "SyncScheduler"

        /**
         * Fifteen minutes is WorkManager's floor for periodic work. Anything shorter is silently
         * clamped, so it is stated rather than pretended.
         */
        const val SYNC_INTERVAL_MINUTES = 15L

        const val MEDIA_INTERVAL_MINUTES = 60L
        const val REFRESHER_CHECK_HOURS = 6L
        const val BACKOFF_SECONDS = 30L
    }
}
