package org.jaagruk.safety

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jaagruk.safety.di.ApplicationScope
import org.jaagruk.safety.sync.SyncScheduler
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup is deliberately almost empty. A worker taps the icon at the start of a shift and needs the sign-in
 * screen immediately; anything that touches the network, the camera or the keystore here would delay that on
 * exactly the low-end handsets this app targets. Background schedules are installed after first frame, and
 * everything else is created on demand by Hilt.
 */
@HiltAndroidApp
class JaagrukApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /**
     * WorkManager configured by hand so Hilt can inject into workers.
     *
     * The manifest removes the default initialiser to make this the only path. Without it, `SyncWorker`
     * would be constructed by the default factory, which cannot supply its repositories, and every sync
     * pass would fail at instantiation — silently, because WorkManager swallows it.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.VERBOSE_LOGGING) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Off the main thread. Enqueuing unique periodic work touches WorkManager's database, and on a
        // slow handset that is tens of milliseconds of jank before the first frame.
        applicationScope.launch {
            runCatching { syncScheduler.ensurePeriodicWork() }
                .onFailure { Log.w(TAG, "could not install background schedules", it) }
        }
    }

    private companion object {
        const val TAG = "JaagrukApplication"
    }
}
