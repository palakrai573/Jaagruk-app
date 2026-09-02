package org.jaagruk.safety.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What kind of connectivity, not merely whether there is any.
 *
 * The distinction matters here more than in most apps. A mine-site handset is regularly attached to
 * a network that routes nowhere — a site Wi-Fi with no uplink, a captive portal at a canteen, a
 * cell registration with no data. `NET_CAPABILITY_VALIDATED` is the signal that Android has actually
 * confirmed internet reachability, and sync waits for it rather than burning battery on uploads that
 * cannot complete.
 *
 * [Quality.METERED] is reported separately so a 900 kB hazard photo can wait for Wi-Fi while the one
 * line of text that says an exit is blocked goes out immediately over mobile data.
 */
class ConnectivityObserver(context: Context) {

    enum class Quality {
        /** No network at all, or a network Android has not validated. */
        NONE,

        /** Reachable, but on a metered connection. Text syncs; large media waits. */
        METERED,

        /** Reachable and unmetered. Everything syncs. */
        UNMETERED,
        ;

        val isOnline: Boolean get() = this != NONE
        val allowsLargeUploads: Boolean get() = this == UNMETERED
    }

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Current quality, sampled synchronously.
     *
     * Used by the sync worker at the start of a pass. A worker that has already been scheduled by
     * WorkManager's own network constraint still checks, because the constraint was evaluated when
     * the job was dispatched and the handset may have walked back into a drift since.
     */
    fun current(): Quality {
        val cm = manager ?: return Quality.NONE
        val network = cm.activeNetwork ?: return Quality.NONE
        val capabilities = cm.getNetworkCapabilities(network) ?: return Quality.NONE
        return classify(capabilities)
    }

    /**
     * Live quality.
     *
     * `distinctUntilChanged` is not cosmetic: Android emits capability changes several times per
     * association, and without it a supervisor walking into Wi-Fi range would kick off three sync
     * passes.
     */
    val quality: Flow<Quality> = callbackFlow {
        val cm = manager
        if (cm == null) {
            trySend(Quality.NONE)
            awaitClose { }
            return@callbackFlow
        }

        trySend(current())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(classify(networkCapabilities))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: SecurityException) {
            // Seen on locked-down enterprise builds. Reporting NONE keeps sync queued rather than
            // crashing the app on a device where the callback is simply not permitted.
            Log.w(TAG, "network callback registration refused; reporting offline", e)
            trySend(Quality.NONE)
        }

        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun classify(capabilities: NetworkCapabilities): Quality {
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!hasInternet || !validated) return Quality.NONE

        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (unmetered) Quality.UNMETERED else Quality.METERED
    }

    private companion object {
        const val TAG = "ConnectivityObserver"
    }
}
