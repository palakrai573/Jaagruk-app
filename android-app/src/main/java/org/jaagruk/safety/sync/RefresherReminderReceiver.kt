package org.jaagruk.safety.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms background work after a reboot or an app update.
 *
 * WorkManager already persists its own schedules across both, so this is belt-and-braces rather than
 * load-bearing — and that is the point worth stating: the refresher schedule lives in the database as
 * timestamps, so nothing here can lose it. The receiver only makes sure the *check* is armed
 * promptly, instead of waiting up to six hours for the next periodic window on a handset that was
 * just powered on at the start of a shift.
 *
 * Deliberately does nothing else. A boot receiver that starts syncing would burn a shared handset's
 * charge before anybody has picked it up.
 */
class RefresherReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        // A receiver must not block. Enqueuing WorkManager jobs is cheap and non-blocking; the actual
        // check runs on WorkManager's own executor.
        try {
            val scheduler = SyncScheduler(context.applicationContext)
            scheduler.ensurePeriodicWork()
            scheduler.checkRefreshersNow()
            Log.i(TAG, "background work re-armed after $action")
        } catch (e: Exception) {
            // A failure here costs a delayed prompt, nothing more. Crashing in a boot receiver would
            // show the user an app-stopped dialog before they had even opened the app.
            Log.w(TAG, "could not re-arm background work after $action", e)
        }
    }

    private companion object {
        const val TAG = "RefresherReceiver"
    }
}
