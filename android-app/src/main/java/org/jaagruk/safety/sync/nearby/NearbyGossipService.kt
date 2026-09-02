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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.db.SyncQueueEntity
import org.jaagruk.safety.sync.SyncKind
import org.jaagruk.safety.sync.SyncPayloadFactory
import org.jaagruk.safety.sync.SyncScheduler

/**
 * Hands records from a handset with no uplink to one that will have an uplink.
 *
 * The problem this solves is specific and real: a worker's phone can accumulate a week of signed
 * certificates at the bottom of a shaft with no way to deliver them. A supervisor who walks the
 * section every shift *does* surface. So the records travel out on their phone.
 *
 * What makes this safe rather than a liability:
 *
 *  * **The courier cannot alter anything.** Every certificate is Ed25519-signed and chain-linked. A
 *    relaying handset that modified a score would produce a signature that fails at the server, and
 *    one that dropped records selectively would leave a sequence gap the chain audit surfaces.
 *  * **The courier does not append to the origin's chain.** Relayed items are stored as
 *    [SyncKind.RELAY] — finished DTOs, not local rows — so the supervisor's own chain head is
 *    untouched. A device inventing rows for another site's certificates is exactly the kind of
 *    contamination that would make the ledger meaningless.
 *  * **The idempotency key travels unchanged.** A certificate that arrives by relay *and* later by
 *    direct upload collapses onto one server-side row.
 *  * **The sender keeps its queue until it sees an ACK.** A transfer cut off halfway loses nothing;
 *    worst case a record is delivered twice, which the idempotency key absorbs.
 *
 * Deliberately manual. Both workers open the screen and confirm, rather than records moving between
 * phones in people's pockets. A safety tool that silently exchanges data over Bluetooth is one a site
 * IT department is right to refuse.
 */
class NearbyGossipService(
    private val context: Context,
    private val database: JaagrukDatabase,
    private val deviceProfile: DeviceProfile,
    private val payloads: SyncPayloadFactory,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
) {

    /** Which side of the exchange this device is playing. */
    enum class Role {
        IDLE,

        /** Holding records and looking for a courier. */
        OFFERING,

        /** Willing to carry another handset's records out. */
        COLLECTING,
    }

    data class State(
        val role: Role = Role.IDLE,
        val connectedTo: String? = null,
        val recordsSent: Int = 0,
        val recordsReceived: Int = 0,
        val finished: Boolean = false,
        val message: String? = null,
    )

    sealed interface Event {
        data class Progress(val sent: Int, val received: Int) : Event

        data class Completed(val sent: Int, val received: Int) : Event

        data class Failed(val reason: String) : Event

        data object PeerDeclined : Event
    }

    private val queue = database.syncQueueDao()
    private val client by lazy { Nearby.getConnectionsClient(context) }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    private var role: Role = Role.IDLE

    @Volatile
    private var peerEndpointId: String? = null

    @Volatile
    private var localDeviceId: String = ""

    @Volatile
    private var siteId: String = ""

    /** Keys handed to the peer but not yet acknowledged. Not removed from the queue until ACK. */
    private val inFlightKeys = mutableSetOf<String>()

    private var sentCount = 0
    private var receivedCount = 0

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Advertises as a handset with records to hand over. */
    fun startOffering() = start(Role.OFFERING)

    /** Advertises as a handset willing to carry records out. */
    fun startCollecting() = start(Role.COLLECTING)

    private fun start(requested: Role) {
        if (role != Role.IDLE) return
        if (!NearbyPermissions.allGranted(context)) {
            _events.tryEmit(Event.Failed("nearby permissions have not been granted"))
            return
        }

        role = requested
        sentCount = 0
        receivedCount = 0
        inFlightKeys.clear()
        _state.value = State(role = requested)

        scope.launch {
            localDeviceId = deviceProfile.deviceId()
            siteId = deviceProfile.activeSiteId().orEmpty()
            val displayName = deviceProfile.nearbyDisplayName()

            client.startAdvertising(
                displayName,
                SERVICE_ID,
                lifecycle,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnFailureListener { error ->
                if (!error.isAlreadyRunning()) {
                    Log.w(TAG, "gossip advertising failed", error)
                    _events.tryEmit(Event.Failed("could not advertise to nearby devices"))
                }
            }

            client.startDiscovery(
                SERVICE_ID,
                discovery,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnFailureListener { error ->
                if (!error.isAlreadyRunning()) {
                    Log.w(TAG, "gossip discovery failed", error)
                    _events.tryEmit(Event.Failed("could not look for nearby devices"))
                }
            }
        }
    }

    fun stop() {
        role = Role.IDLE
        peerEndpointId = null
        inFlightKeys.clear()
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopAllEndpoints() }
        _state.value = State()
    }

    // -----------------------------------------------------------------------
    // Protocol
    // -----------------------------------------------------------------------

    private fun send(frame: GossipFrame) {
        val endpoint = peerEndpointId ?: return
        client.sendPayload(endpoint, Payload.fromBytes(GossipFrame.encode(frame)))
            .addOnFailureListener { error ->
                Log.w(TAG, "gossip send failed", error)
                _events.tryEmit(Event.Failed("the transfer was interrupted"))
            }
    }

    private fun onConnected(endpointId: String) {
        peerEndpointId = endpointId
        _state.value = _state.value.copy(connectedTo = endpointId)
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }

        if (role == Role.OFFERING) {
            scope.launch {
                val pending = queue.ready(Long.MAX_VALUE, PROBE_LIMIT).size
                send(
                    GossipFrame(
                        type = GossipFrame.Type.OFFER,
                        senderDeviceId = localDeviceId,
                        siteId = siteId,
                        payload = pending.toString(),
                    ),
                )
            }
        }
    }

    private fun handle(frame: GossipFrame) {
        when (frame.type) {
            GossipFrame.Type.OFFER -> respondToOffer(frame)

            GossipFrame.Type.ACCEPT -> scope.launch { sendNextBatch() }

            GossipFrame.Type.RECORDS -> scope.launch { ingest(frame) }

            GossipFrame.Type.ACK -> scope.launch { onAck(frame) }

            GossipFrame.Type.DONE -> finish()

            GossipFrame.Type.DECLINE -> {
                _events.tryEmit(Event.PeerDeclined)
                _state.value = _state.value.copy(
                    finished = true,
                    message = frame.payload.ifBlank { null },
                )
            }
        }
    }

    private fun respondToOffer(frame: GossipFrame) {
        if (role != Role.COLLECTING) {
            // Two handsets that both want to hand records over cannot help each other. Saying so
            // beats leaving both waiting.
            send(
                GossipFrame(
                    GossipFrame.Type.DECLINE,
                    localDeviceId,
                    siteId,
                    "this device is also waiting to hand records over",
                ),
            )
            return
        }
        if (siteId.isNotBlank() && frame.siteId.isNotBlank() && frame.siteId != siteId) {
            // Cross-site relay is refused. A courier can only upload under its own site scope, so
            // carrying another site's records would produce a guaranteed server-side rejection.
            send(
                GossipFrame(
                    GossipFrame.Type.DECLINE,
                    localDeviceId,
                    siteId,
                    "this device is enrolled to a different site",
                ),
            )
            return
        }
        send(GossipFrame(GossipFrame.Type.ACCEPT, localDeviceId, siteId, ""))
    }

    /**
     * Builds and sends one batch small enough to fit a single Nearby frame.
     *
     * Halves the batch and retries when the encoded bundle is over budget, rather than implementing
     * multi-frame reassembly. An assessment run with twelve steps of detail is the payload that pushes
     * a batch over, and halving converges in two or three steps; reassembly would add a whole class of
     * partial-transfer states to get wrong for no practical gain.
     */
    private suspend fun sendNextBatch(limit: Int = BATCH_SIZE) {
        val entries = queue.ready(Long.MAX_VALUE, limit)
            .filterNot { it.idempotencyKey in inFlightKeys }

        val envelopes = entries.mapNotNull { entry ->
            payloads.build(entry)?.let(payloads::envelopeFor)
        }
        if (envelopes.isEmpty()) {
            send(GossipFrame(GossipFrame.Type.DONE, localDeviceId, siteId, ""))
            finish()
            return
        }

        val payload = payloads.encodeBundle(
            SyncPayloadFactory.RelayBundle(
                originDeviceId = localDeviceId,
                siteId = siteId,
                items = envelopes,
            ),
        )

        if (payload.toByteArray(Charsets.UTF_8).size > GossipFrame.MAX_FRAME_BYTES - FRAME_HEADROOM) {
            if (entries.size <= 1) {
                // One record that will not fit at all. Marked in-flight so the loop moves past it,
                // and left in the queue so the direct-upload path — which has no frame budget — still
                // delivers it. Skipping a relay is never the same as dropping a record.
                Log.w(TAG, "a single record exceeds the relay frame budget; leaving it for direct upload")
                entries.firstOrNull()?.let { inFlightKeys += it.idempotencyKey }
                sendNextBatch(limit)
                return
            }
            sendNextBatch(entries.size / 2)
            return
        }

        entries.forEach { inFlightKeys += it.idempotencyKey }
        send(GossipFrame(GossipFrame.Type.RECORDS, localDeviceId, siteId, payload))
    }

    /**
     * Stores a received batch as relay entries and acknowledges the keys.
     *
     * The ACK is sent only after the rows are committed. Acknowledging first would let a crash between
     * ACK and commit lose records the sender has already dropped — the one failure mode this whole
     * design exists to prevent.
     */
    private suspend fun ingest(frame: GossipFrame) {
        val bundle = payloads.decodeBundle(frame.payload)
        if (bundle == null) {
            Log.w(TAG, "dropped a malformed relay bundle from ${frame.senderDeviceId}")
            // No ACK, so the sender keeps everything and can retry.
            return
        }

        val accepted = mutableListOf<String>()
        val nowMs = System.currentTimeMillis()

        for (envelope in bundle.items) {
            val key = envelope.certificate?.idempotencyKey
                ?: envelope.assessment?.idempotencyKey
                ?: envelope.hazard?.idempotencyKey
                ?: envelope.progress?.idempotencyKey
                ?: continue

            // A relay entry carries its whole payload, so `refId` is never used to look anything up.
            // The idempotency key is the honest value here: it identifies the record without implying
            // this device owns a row for it.
            val refId = key

            // The unique index on idempotencyKey makes a re-received record a no-op rather than a
            // duplicate row, so a retried transfer is harmless.
            queue.enqueue(
                SyncQueueEntity(
                    kind = SyncKind.RELAY.wireName,
                    refId = refId,
                    idempotencyKey = key,
                    payloadJson = payloads.encodeEnvelope(envelope),
                    attempts = 0,
                    nextAttemptAtMs = 0L,
                    createdAtMs = nowMs,
                ),
            )
            accepted += key
            receivedCount++
        }

        _state.value = _state.value.copy(recordsReceived = receivedCount)
        _events.tryEmit(Event.Progress(sentCount, receivedCount))

        send(
            GossipFrame(
                type = GossipFrame.Type.ACK,
                senderDeviceId = localDeviceId,
                siteId = siteId,
                payload = accepted.joinToString(","),
            ),
        )

        // The records are now this device's responsibility to deliver.
        scheduler.requestSyncNow()
    }

    /** Removes acknowledged entries and continues with the next batch. */
    private suspend fun onAck(frame: GossipFrame) {
        val keys = frame.payload.split(',').filter { it.isNotBlank() }
        if (keys.isNotEmpty()) {
            queue.removeByKeys(keys)
            sentCount += keys.size
            keys.forEach { inFlightKeys.remove(it) }
            _state.value = _state.value.copy(recordsSent = sentCount)
            _events.tryEmit(Event.Progress(sentCount, receivedCount))
            Log.i(TAG, "handed ${keys.size} record(s) to ${frame.senderDeviceId}")
        }
        sendNextBatch()
    }

    private fun finish() {
        _state.value = _state.value.copy(finished = true)
        _events.tryEmit(Event.Completed(sentCount, receivedCount))
    }

    // -----------------------------------------------------------------------
    // Nearby callbacks
    // -----------------------------------------------------------------------

    private val lifecycle = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { Log.w(TAG, "could not accept $endpointId", it) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                if (peerEndpointId != null && peerEndpointId != endpointId) {
                    runCatching { client.disconnectFromEndpoint(endpointId) }
                    return
                }
                onConnected(endpointId)
            } else {
                _events.tryEmit(Event.Failed("the link could not be established"))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (peerEndpointId != endpointId) return
            peerEndpointId = null
            // Unacknowledged keys go back into play automatically: they were never removed from the
            // queue, so the next pass — direct or relayed — picks them up again.
            inFlightKeys.clear()
            if (!_state.value.finished) {
                _events.tryEmit(Event.Failed("the other device went out of range"))
            }
            _state.value = _state.value.copy(connectedTo = null)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            if (peerEndpointId != null) return
            scope.launch {
                client.requestConnection(
                    deviceProfile.nearbyDisplayName(),
                    endpointId,
                    lifecycle,
                ).addOnFailureListener {
                    Log.i(TAG, "gossip connection request to $endpointId not accepted")
                }
            }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val frame = GossipFrame.decodeOrNull(bytes)
            if (frame == null) {
                Log.w(TAG, "dropped a malformed gossip frame from $endpointId")
                return
            }
            handle(frame)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.i(TAG, "gossip transfer with $endpointId failed")
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
        private const val TAG = "NearbyGossip"

        /** Distinct from the buddy-drill service id so a drill never pairs with a relay. */
        const val SERVICE_ID: String = "org.jaagruk.safety.gossip.v1"

        private val STRATEGY: Strategy = Strategy.P2P_STAR

        private const val BATCH_SIZE = 12
        private const val PROBE_LIMIT = 500
        private const val FRAME_HEADROOM = 512
    }
}
