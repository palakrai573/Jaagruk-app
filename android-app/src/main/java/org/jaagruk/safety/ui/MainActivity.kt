package org.jaagruk.safety.ui

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.ui.components.CatalogStrings
import org.jaagruk.safety.ui.components.runResourceAudit
import org.jaagruk.safety.ui.nav.JaagrukNavHost
import org.jaagruk.safety.ui.theme.JaagrukTheme

/**
 * The only activity.
 *
 * Single-activity on purpose. The AR session, the GL surface and the drill's monotonic clock all live for
 * the duration of a scenario, and an activity boundary mid-drill would tear down the first two and stall
 * the third — and the thing being measured is decision latency, so a scheduling gap is a corrupted
 * measurement rather than a cosmetic glitch. `configChanges` in the manifest exists for the same reason.
 *
 * `AppCompatActivity`, not `ComponentActivity`, because per-app locales are delivered through
 * `AppCompatDelegate` on Android 12 and below — which is most of the installed base this app targets.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Installed before super so the system splash hands over cleanly rather than flashing.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // A drill takes several minutes of looking at the screen without touching it. The default screen
        // timeout would blank the display mid-scenario and pause a worker's run for no reason.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (runResourceAudit) auditCatalogStrings()

        val openRefreshers = intent?.getBooleanExtra(EXTRA_OPEN_REFRESHERS, false) == true
        val notifiedWorkerId = intent?.getStringExtra(EXTRA_WORKER_ID)

        setContent {
            JaagrukTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JaagrukNavHost(
                        openRefreshers = openRefreshers,
                        notifiedWorkerId = notifiedWorkerId,
                    )
                }
            }
        }
    }

    /**
     * Fails loudly in debug when the catalog references a string that does not exist.
     *
     * Lint's `MissingTranslation` checks that a *declared* string is translated. It cannot know that
     * `ModuleCatalog` expects `opt_raise_alarm` to be declared in the first place, which is the failure
     * that would put a blank button in front of a worker who cannot read the fallback. This closes that
     * gap, against the merged resources of the variant actually running.
     */
    private fun auditCatalogStrings() {
        val required = ModuleCatalog.requiredStringKeys()
        val missing = CatalogStrings.audit(this, required)
        if (missing.isEmpty()) {
            Log.i(TAG, "catalog resource audit: all ${required.size} string keys present")
            return
        }
        Log.e(
            TAG,
            "catalog resource audit FAILED: ${missing.size} of ${required.size} keys have no string. " +
                "Missing: ${missing.take(20).joinToString()}" +
                if (missing.size > 20) " ... and ${missing.size - 20} more" else "",
        )
        // Deliberately not a crash. A developer sees the log and the app still runs, showing the key
        // itself in place of the label — which is more useful for finding the gap than a stack trace.
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Set by the refresher notification so the app opens on the right worker's due list. */
        const val EXTRA_WORKER_ID: String = "org.jaagruk.safety.extra.WORKER_ID"
        const val EXTRA_OPEN_REFRESHERS: String = "org.jaagruk.safety.extra.OPEN_REFRESHERS"
    }
}
