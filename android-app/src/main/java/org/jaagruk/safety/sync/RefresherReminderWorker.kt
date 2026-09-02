package org.jaagruk.safety.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.R
import org.jaagruk.safety.data.repo.RetentionRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.ui.MainActivity

/**
 * Prompts workers whose spaced-repetition refresher has come due.
 *
 * The notification is a convenience, not the mechanism. The schedule lives in the database as
 * timestamps and readiness is computed on read, so a notification that never arrives — permission
 * refused, battery optimisation, handset off for a fortnight — delays a prompt and nothing more. The
 * worker still sees the correct due state the moment they open the app.
 *
 * That property is deliberate. Building the retention model on delivered notifications would mean an
 * OEM's aggressive doze implementation could silently stop a site's refresher programme, and nobody
 * would find out until an audit.
 */
@HiltWorker
class RefresherReminderWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val retention: RetentionRepository,
    private val workers: WorkerRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val due = retention.dueNow()
        if (due.isEmpty()) return Result.success()

        if (!canPostNotifications()) {
            // No permission, or notifications disabled at the OS level. Not a failure: the in-app
            // list is authoritative and already shows these as due.
            Log.i(TAG, "${due.size} refresher(s) due but notifications are unavailable")
            return Result.success()
        }

        ensureChannel()

        // Grouped per worker rather than one notification per module. A worker with four overdue
        // modules needs one prompt to open the app, not four to dismiss.
        val grouped = due.groupBy { it.workerId }
        var posted = 0

        for ((workerId, rows) in grouped) {
            val notifiedRecently = rows.all { it.notifiedAtSec > 0L }
            if (notifiedRecently) continue

            val worker = workers.find(workerId) ?: continue

            // A due row naming a module the catalog does not know means the schedule outlived a
            // catalog change. Worth saying out loud, because the prompt would otherwise send a worker
            // to a module that cannot be started.
            val unknownModules = rows.filter { ModuleCatalog.byId(it.moduleId) == null }
            if (unknownModules.isNotEmpty()) {
                Log.w(
                    TAG,
                    "refresher due for $workerId names unknown module(s): " +
                        unknownModules.joinToString { it.moduleId },
                )
            }

            val text = appContext.resources.getQuantityString(
                R.plurals.refresher_due_body,
                rows.size,
                rows.size,
            )

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_refresher)
                .setContentTitle(appContext.getString(R.string.refresher_due_title, worker.fullName))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(workerId))
                .build()

            // Guarded even though [canPostNotifications] already checked. The permission can be revoked from
            // the notification shade between that check and this call, and a SecurityException thrown inside a
            // WorkManager worker would look like a background crash rather than a revoked permission.
            try {
                NotificationManagerCompat.from(appContext)
                    .notify(workerId.hashCode(), notification)
                posted++
            } catch (e: SecurityException) {
                Log.i(TAG, "notification permission was revoked mid-pass", e)
                return Result.success()
            }

            rows.forEach { retention.markNotified(it.workerId, it.moduleId) }
        }

        Log.i(TAG, "posted $posted refresher reminder(s)")
        return Result.success()
    }

    private fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.refresher_channel_name),
                // Default, not high. A refresher is due "some time today", and a full-screen
                // interruption underground would be both useless and resented.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.refresher_channel_description)
                setShowBadge(true)
            },
        )
    }

    private fun openAppIntent(workerId: String): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_WORKER_ID, workerId)
            putExtra(MainActivity.EXTRA_OPEN_REFRESHERS, true)
        }
        return PendingIntent.getActivity(
            appContext,
            workerId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "RefresherReminder"

        const val UNIQUE_WORK_NAME: String = "jaagruk-refresher-check"
        const val CHANNEL_ID: String = "jaagruk_refreshers"
    }
}
