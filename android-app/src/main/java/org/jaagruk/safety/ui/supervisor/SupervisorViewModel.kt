package org.jaagruk.safety.ui.supervisor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.crypto.ChainStatus
import org.jaagruk.core.util.Hex
import org.jaagruk.safety.R
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.data.repo.RetentionRepository
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.sync.SyncStatusProvider
import org.jaagruk.safety.sync.TimeSyncTracker
import org.jaagruk.safety.sync.api.SessionStore
import org.jaagruk.safety.sync.nearby.NearbyGossipService
import org.jaagruk.safety.ui.components.UiMessage
import javax.inject.Inject

/**
 * Supervisor tooling: key enrolment, chain integrity, sync and the peer-to-peer relay.
 *
 * The dangerous operation here is site key generation, and it is guarded accordingly. Generating a second key
 * for a site that already has one would fork the certificate chain — two devices issuing at the same sequence
 * number, producing two different records in one slot, which is indistinguishable from tampering. So the
 * action refuses when a key already exists and says why, rather than offering a confirm dialog somebody will
 * tap through.
 */
@HiltViewModel
class SupervisorViewModel @Inject constructor(
    private val keyStore: SiteKeyStore,
    private val deviceProfile: DeviceProfile,
    private val sites: SiteRepository,
    private val workers: WorkerRepository,
    private val certificates: CertificateRepository,
    private val retention: RetentionRepository,
    private val syncScheduler: SyncScheduler,
    private val timeSync: TimeSyncTracker,
    private val session: SessionStore,
    private val gossip: NearbyGossipService,
    syncStatus: SyncStatusProvider,
) : ViewModel() {

    data class State(
        val siteId: String? = null,
        val siteName: String? = null,
        val signedInAs: String? = null,
        val hasSiteKey: Boolean = false,
        val sitePublicKeyHex: String? = null,
        val keyEpoch: Int = 1,
        val deviceRegistered: Boolean = false,
        val deviceId: String = "",
        val anchorCount: Int = 0,
        val siteScanned: Boolean = false,
        val cloudAnchorsEnabled: Boolean = false,
        val workerCount: Int = 0,
        val workersWithPin: Int = 0,
        val certificateCount: Long = 0L,
        val chainHeadSeq: Long = 0L,
        val chainStatus: ChainStatus? = null,
        val chainFirstProblemSeq: Long? = null,
        val pendingSync: Int = 0,
        val abandonedSync: Int = 0,
        val clockSkewSeconds: Long = 0L,
        val neverSynced: Boolean = true,
        val readiness: RetentionRepository.ReadinessSummary? = null,
        val relayState: NearbyGossipService.State = NearbyGossipService.State(),
        val busy: Boolean = false,
        val message: UiMessage? = null,
        val siteIdInput: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            syncStatus.status.collect { status ->
                _state.value = _state.value.copy(
                    pendingSync = status.pending,
                    abandonedSync = status.abandoned,
                )
            }
        }
        viewModelScope.launch {
            gossip.state.collect { relay ->
                _state.value = _state.value.copy(relayState = relay)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val siteId = keyStore.siteId ?: deviceProfile.activeSiteId()
            val site = siteId?.let { sites.find(it) }
            val roster = workers.all()

            _state.value = _state.value.copy(
                siteId = siteId,
                siteName = site?.name,
                signedInAs = session.fullName,
                hasSiteKey = keyStore.hasSiteKey(),
                sitePublicKeyHex = keyStore.sitePublicKey()?.let(Hex::encode),
                keyEpoch = keyStore.keyEpoch,
                deviceRegistered = deviceProfile.isDeviceRegistered(),
                deviceId = deviceProfile.deviceId(),
                anchorCount = siteId?.let { sites.anchors(it).size } ?: 0,
                siteScanned = siteId?.let { sites.isSiteScanned(it) } == true,
                cloudAnchorsEnabled = org.jaagruk.safety.BuildConfig.CLOUD_ANCHORS_ENABLED,
                workerCount = roster.size,
                workersWithPin = workers.countWithPin(),
                certificateCount = siteId?.let { certificates.certificateCountForSite(it) } ?: 0L,
                chainHeadSeq = siteId?.let { certificates.observeChainHead(it)?.lastSeq } ?: 0L,
                clockSkewSeconds = timeSync.skewSeconds(),
                neverSynced = timeSync.hasNeverSynced(),
                readiness = retention.siteReadinessSummary(roster.map { it.workerId }),
                siteIdInput = siteId.orEmpty(),
            )
        }
    }

    fun setSiteIdInput(value: String) {
        _state.value = _state.value.copy(siteIdInput = value.trim().uppercase())
    }

    /**
     * Generates this handset's site signing identity.
     *
     * Refused outright when a key already exists. A second key for the same site means two devices issuing at
     * the same sequence number, which produces two different records in one chain slot — exactly the shape of
     * a tampered ledger. Rotation is a server-side operation with a new epoch, not something to do from a
     * phone at a gate.
     */
    fun generateSiteKey() {
        val siteId = _state.value.siteIdInput
        if (siteId.isBlank()) {
            _state.value = _state.value.copy(
                message = UiMessage.error(R.string.supervisor_site_id_required),
            )
            return
        }
        if (keyStore.hasSiteKey()) {
            _state.value = _state.value.copy(
                message = UiMessage.error(R.string.supervisor_key_exists),
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val pair = keyStore.generateSiteKey(siteId)
                deviceProfile.setActiveSiteId(siteId)
                sites.recordSiteKey(siteId, Hex.encode(pair.publicKey), 1)
                keyStore.ensureDeviceAttestationKey()
                // Registers the device and publishes the public key the moment there is a network.
                syncScheduler.requestSyncNow()
                _state.value = _state.value.copy(
                    busy = false,
                    message = UiMessage.success(R.string.supervisor_key_created),
                )
                refresh()
            } catch (e: Exception) {
                Log.w(TAG, "site key generation failed", e)
                _state.value = _state.value.copy(
                    busy = false,
                    message = UiMessage.error(R.string.supervisor_key_failed),
                )
            }
        }
    }

    /**
     * Walks this handset's whole chain for the site.
     *
     * Runs offline against stored records. A break is reported with the sequence number it starts at, because
     * "the chain is broken" is not actionable and "records from sequence 47 onward do not link" is.
     */
    fun auditChain() {
        val siteId = _state.value.siteId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = certificates.auditSite(siteId)
            _state.value = _state.value.copy(
                busy = false,
                chainStatus = result.status,
                chainFirstProblemSeq = result.firstProblemSeq,
                message = if (result.isClean) {
                    UiMessage.success(R.string.supervisor_chain_clean, result.recordsChecked)
                } else {
                    UiMessage.warning(
                        R.string.supervisor_chain_problem,
                        result.firstProblemSeq ?: 0L,
                    )
                },
            )
        }
    }

    fun syncNow() {
        syncScheduler.requestSyncNow()
        _state.value = _state.value.copy(
            message = UiMessage.info(R.string.supervisor_sync_requested),
        )
    }

    fun startRelayCollecting() {
        gossip.startCollecting()
        _state.value = _state.value.copy(
            message = UiMessage.info(R.string.supervisor_relay_collecting),
        )
    }

    fun startRelayOffering() {
        gossip.startOffering()
        _state.value = _state.value.copy(
            message = UiMessage.info(R.string.supervisor_relay_offering),
        )
    }

    fun stopRelay() {
        gossip.stop()
    }

    /**
     * Clears a worker's PIN so they can set a new one.
     *
     * Clears rather than sets, so the supervisor never learns the replacement. That distinction matters on a
     * shared handset where the supervisor could otherwise sign in as the worker and have a certificate issued
     * in their name.
     */
    fun resetWorkerPin(workerId: String) {
        viewModelScope.launch {
            val cleared = workers.clearPinForReset(workerId)
            _state.value = _state.value.copy(
                message = if (cleared) {
                    UiMessage.success(R.string.supervisor_pin_reset, workerId)
                } else {
                    UiMessage.error(R.string.supervisor_worker_unknown, workerId)
                },
            )
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        gossip.stop()
        super.onCleared()
    }

    private companion object {
        const val TAG = "SupervisorViewModel"
    }
}
