package org.jaagruk.safety.sync.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Nearby Connections transport for the two-phone buddy drill.
 *
 * The buddy system is two people coordinating under stress. Simulating the partner as an NPC — the
 * simpler build, and the one most implementations settle for — trains none of the skill being
 * certified, so this runs across two real handsets with no internet, no cell signal and no shared
 * Wi-Fi, because underground sites have none of those.
 *
 * All protocol logic lives in `org.jaagruk.core.drill.BuddyDrillMachine`, which knows nothing about
 * Nearby. This class is only bytes in and bytes out, which is what lets the entire protocol —
 * role election, duplicate suppression, reordering, heartbeat loss, version mismatch, partner
 * abandonment — be driven deterministically by unit tests with two machines wired together and a
 * fake clock. None of that would be testable if the state machine held a `ConnectionsClient`.
 *
 * `P2P_STAR` rather than `P2P_POINT_TO_POINT`: point-to-point restricts a device to one connection
 * and, on several OEM firmwares, refuses a second advertisement in the same process. Star tolerates a
 * stray third handset discovering the pair, and the machine politely ignores anything after the first
 * accepted peer.
 */
class NearbyBuddyTransport(private val context: Context) {

    sealed interface Event {
        data class Connected(val endpointId: String, val endpointName: String) : Event

        data class Disconnected(val endpointId: String) : Event

        data class BytesReceived(val endpointId: String, val bytes: ByteArray) : Event {
            // Generated equals on a ByteArray field compares references, which would make two
            // identical frames unequal and is a classic source of confusing test failures.
            override fun equals(other: Any?): Boolean =
                other is BytesReceived &&
                    endpointId == other.endpointId &&
                    bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = 31 * endpointId.hashCode() + bytes.contentHashCode()
        }

        data class Failed(val reason: String, val recoverable: Boolean) : Event

        data object Searching : Event
    }

    private val client by lazy { Nearby.getConnectionsClient(context) }

    /**
     * Replay of one so a subscriber that attaches a moment late still sees the connection event.
     *
     * `DROP_OLDEST` rather than suspending: this flow is emitted into from Nearby's own callback
     * thread, and blocking that thread stalls the transport itself.
     */
    private val _events = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    private var localName: String = "jaagruk"

    @Volatile
    private var connectedEndpointId: String? = null

    @Volatile
    private var running: Boolean = false

    val isConnected: Boolean get() = connectedEndpointId != null

    val peerEndpointId: String? get() = connectedEndpointId

    /**
     * Starts advertising and discovering at the same time.
     *
     * Symmetric on purpose: neither handset is designated initiator. Requiring one worker to press
     * "host" and the other "join" is an instruction that gets misremembered in a haulage road, and it
     * makes a failed pairing look like a broken app. Whichever device notices the other first requests
     * the connection, and role election in the state machine is decided by device id, not by who
     * connected.
     */
    fun start(localDisplayName: String) {
        if (running) return
        if (!NearbyPermissions.allGranted(context)) {
            _events.tryEmit(
                Event.Failed("nearby permissions have not been granted", recoverable = true),
            )
            return
        }

        localName = localDisplayName
        running = true
        _events.tryEmit(Event.Searching)

        client.startAdvertising(
            localDisplayName,
            SERVICE_ID,
            connectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { error ->
            // 8001 STATUS_ALREADY_ADVERTISING is benign — a previous session's advertisement is still
            // up. Anything else is reported so the UI can offer the single-device fallback rather than
            // leaving two workers staring at a spinner.
            if (error.isAlreadyRunning()) return@addOnFailureListener
            Log.w(TAG, "advertising failed", error)
            _events.tryEmit(
                Event.Failed(error.localizedMessage ?: "could not advertise", recoverable = true),
            )
        }

        client.startDiscovery(
            SERVICE_ID,
            endpointDiscovery,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { error ->
            if (error.isAlreadyRunning()) return@addOnFailureListener
            Log.w(TAG, "discovery failed", error)
            _events.tryEmit(
                Event.Failed(error.localizedMessage ?: "could not discover", recoverable = true),
            )
        }
    }

    /**
     * Stops discovery and advertising but keeps an established connection.
     *
     * Called once a peer is connected. Leaving both running would keep the radios busy for the whole
     * drill, and on a mid-range handset that costs frames in the AR scene — where the decision latency
     * being measured actually lives.
     */
    private fun stopSearching() {
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }
    }

    fun send(bytes: ByteArray) {
        val endpoint = connectedEndpointId ?: return
        client.sendPayload(endpoint, Payload.fromBytes(bytes))
            .addOnFailureListener { error ->
                // A send failure is not fatal to the drill. The state machine's heartbeat timeout is
                // what decides the peer is gone, and it will fire on its own if this keeps happening.
                Log.w(TAG, "payload send failed", error)
            }
    }

    /** Tears everything down. Safe to call repeatedly and from any lifecycle callback. */
    fun stop() {
        running = false
        stopSearching()
        runCatching { client.stopAllEndpoints() }
        connectedEndpointId = null
    }

    // -----------------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------------

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Accepted without a confirmation code. A six-digit token exchange is the right call for
            // pairing arbitrary strangers; here both handsets are already running Jaagruk, advertising
            // the same site service id, held by two workers standing together. The drill's own
            // handshake then rejects a mismatched scenario, catalog version or duplicate worker id,
            // which is the check that actually matters.
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    Log.w(TAG, "could not accept connection from $endpointId", error)
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val existing = connectedEndpointId
                    if (existing != null && existing != endpointId) {
                        // A third handset joined the star. Politely dropped rather than allowed to
                        // interfere with a drill already in progress.
                        Log.i(TAG, "ignoring extra peer $endpointId; already paired with $existing")
                        runCatching { client.disconnectFromEndpoint(endpointId) }
                        return
                    }
                    connectedEndpointId = endpointId
                    stopSearching()
                    _events.tryEmit(Event.Connected(endpointId, localName))
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    _events.tryEmit(
                        Event.Failed("the other device declined the connection", recoverable = true),
                    )

                ConnectionsStatusCodes.STATUS_ERROR ->
                    _events.tryEmit(
                        Event.Failed("the link could not be established", recoverable = true),
                    )

                else -> _events.tryEmit(
                    Event.Failed(
                        "connection failed (${resolution.status.statusCode})",
                        recoverable = true,
                    ),
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpointId == endpointId) connectedEndpointId = null
            _events.tryEmit(Event.Disconnected(endpointId))
            // Search again if the drill has not been torn down. A momentary Bluetooth drop in a steel
            // structure is common, and re-pairing is better than aborting the run outright — the state
            // machine's own PEER_LOST timeout decides when to give up, and it saves the partial run.
            if (running) restartSearching()
        }
    }

    private fun restartSearching() {
        _events.tryEmit(Event.Searching)
        client.startAdvertising(
            localName,
            SERVICE_ID,
            connectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { if (!it.isAlreadyRunning()) Log.w(TAG, "re-advertise failed", it) }

        client.startDiscovery(
            SERVICE_ID,
            endpointDiscovery,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { if (!it.isAlreadyRunning()) Log.w(TAG, "re-discover failed", it) }
    }

    private val endpointDiscovery = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            if (connectedEndpointId != null) return

            client.requestConnection(localName, endpointId, connectionLifecycle)
                .addOnFailureListener { error ->
                    // Both sides requesting simultaneously is normal with symmetric discovery: one
                    // request loses. Nearby resolves it, so this is logged and not surfaced.
                    Log.i(TAG, "connection request to $endpointId not accepted: ${error.message}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "endpoint $endpointId went out of range")
        }
    }

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            _events.tryEmit(Event.BytesReceived(endpointId, bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Byte payloads arrive whole, so there is nothing useful to report per chunk. A failed
            // transfer is left to the drill's heartbeat timeout rather than second-guessed here.
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.i(TAG, "payload transfer to/from $endpointId failed")
            }
        }
    }

    private fun Exception.isAlreadyRunning(): Boolean {
        val message = message ?: return false
        return message.contains("STATUS_ALREADY_ADVERTISING") ||
            message.contains("STATUS_ALREADY_DISCOVERING") ||
            message.contains("8001") ||
            message.contains("8002")
    }

    companion object {
        private const val TAG = "NearbyBuddy"

        /**
         * Service id. Namespaced and version-suffixed so a future protocol change cannot pair with an
         * older build and desynchronise mid-drill — two handsets on different versions simply do not
         * find each other, which is a clearer failure than a mismatched frame stream.
         */
        const val SERVICE_ID: String = "org.jaagruk.safety.buddy.v1"

        private val STRATEGY: Strategy = Strategy.P2P_STAR
    }
}
