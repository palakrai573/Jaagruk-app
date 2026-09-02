package org.jaagruk.safety.ui.buddy

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.drill.BuddyDrillMachine
import org.jaagruk.core.drill.DrillAbortReason
import org.jaagruk.core.drill.DrillEffect
import org.jaagruk.core.drill.DrillPhase
import org.jaagruk.core.drill.DrillRole
import org.jaagruk.core.drill.PeerHealth
import org.jaagruk.safety.BuildConfig
import org.jaagruk.safety.R
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.sync.nearby.NearbyBuddyTransport
import org.jaagruk.safety.sync.nearby.NearbyPermissions
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.UiMessage
import javax.inject.Inject

/**
 * Pairing for a two-phone buddy drill.
 *
 * The buddy system is two people coordinating under stress. Simulating the partner as an NPC — the simpler
 * build, and the one most implementations settle for — trains none of the skill being certified, so this runs
 * across two real handsets over Nearby Connections: no internet, no cell signal, no shared Wi-Fi, because
 * underground sites have none of those.
 *
 * Neither phone is designated host. Whichever notices the other first requests the connection, and role
 * election is decided by device id inside the state machine — so there is no instruction to misremember in a
 * haulage road, and a failed pairing never looks like a broken app.
 */
@Composable
fun BuddyScreen(
    workerId: String,
    scenarioId: String,
    onPaired: (String, String) -> Unit,
    onCancelled: () -> Unit,
    viewModel: BuddyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var permissionsGranted by remember {
        mutableStateOf(NearbyPermissions.allGranted(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> permissionsGranted = results.values.all { it } }

    LaunchedEffect(permissionsGranted, workerId, scenarioId) {
        if (permissionsGranted) viewModel.begin(workerId, scenarioId)
    }

    LaunchedEffect(state.readyToStart) {
        if (state.readyToStart) onPaired(workerId, scenarioId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.buddy_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.buddy_explainer),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        if (!permissionsGranted) {
            SectionCard {
                Text(
                    text = stringResource(nearbyRationale(context)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                GloveButton(
                    text = stringResource(R.string.action_allow_nearby),
                    onClick = { permissionLauncher.launch(NearbyPermissions.required) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.message != null) {
            MessageBanner(state.message, stringResource(R.string.cd_info))
            Spacer(Modifier.height(12.dp))
        }

        SectionCard {
            Text(
                text = stringResource(phaseLabel(state.phase)),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(phaseExplanation(state.phase)),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.phase == DrillPhase.HANDSHAKE || state.phase == DrillPhase.IDLE) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.peerDeviceId?.let { peer ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.buddy_paired_with, peer.take(12)),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(
                        if (state.role == DrillRole.HOST) R.string.buddy_role_host else R.string.buddy_role_guest,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (state.peerHealth == PeerHealth.STALE) {
                Spacer(Modifier.height(10.dp))
                // Warned but not aborted. A momentary Bluetooth drop in a steel structure is ordinary; the
                // state machine's own ten-second timeout decides when the peer is genuinely gone, and the
                // partial run is saved either way.
                StatusBanner(
                    text = stringResource(R.string.buddy_peer_stale),
                    tone = BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_warning),
                )
            }

            state.abortReason?.let { reason ->
                Spacer(Modifier.height(10.dp))
                StatusBanner(
                    text = stringResource(abortLabel(reason)),
                    tone = BannerTone.ERROR,
                    pictogramDescription = stringResource(R.string.cd_stop),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        GloveOutlinedButton(
            text = stringResource(R.string.action_cancel),
            onClick = {
                viewModel.cancel()
                onCancelled()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun nearbyRationale(context: Context): Int =
    when (NearbyPermissions.rationaleKeyFor(context)) {
        "nearby_rationale_location" -> R.string.nearby_rationale_location
        else -> R.string.nearby_rationale_bluetooth
    }

private fun phaseLabel(phase: DrillPhase): Int = when (phase) {
    DrillPhase.IDLE -> R.string.buddy_phase_idle
    DrillPhase.HANDSHAKE -> R.string.buddy_phase_handshake
    DrillPhase.ROLE_ASSIGNED -> R.string.buddy_phase_ready
    DrillPhase.COUNTDOWN -> R.string.buddy_phase_countdown
    DrillPhase.RUNNING -> R.string.buddy_phase_running
    DrillPhase.DISTRESS_WINDOW -> R.string.buddy_phase_distress
    DrillPhase.RESULT_EXCHANGE -> R.string.buddy_phase_result
    DrillPhase.COMPLETE -> R.string.buddy_phase_complete
    DrillPhase.ABORTED -> R.string.buddy_phase_aborted
}

private fun phaseExplanation(phase: DrillPhase): Int = when (phase) {
    DrillPhase.IDLE -> R.string.buddy_explain_idle
    DrillPhase.HANDSHAKE -> R.string.buddy_explain_handshake
    DrillPhase.ROLE_ASSIGNED -> R.string.buddy_explain_ready
    DrillPhase.COUNTDOWN -> R.string.buddy_explain_countdown
    DrillPhase.RUNNING -> R.string.buddy_explain_running
    DrillPhase.DISTRESS_WINDOW -> R.string.buddy_explain_distress
    DrillPhase.RESULT_EXCHANGE -> R.string.buddy_explain_result
    DrillPhase.COMPLETE -> R.string.buddy_explain_complete
    DrillPhase.ABORTED -> R.string.buddy_explain_aborted
}

private fun abortLabel(reason: DrillAbortReason): Int = when (reason) {
    DrillAbortReason.PEER_LOST -> R.string.buddy_abort_peer_lost
    DrillAbortReason.PEER_VERSION_MISMATCH -> R.string.buddy_abort_version
    DrillAbortReason.SAME_WORKER_ON_BOTH_DEVICES -> R.string.buddy_abort_same_worker
    DrillAbortReason.SCENARIO_MISMATCH -> R.string.buddy_abort_scenario
    DrillAbortReason.USER_CANCELLED -> R.string.buddy_abort_cancelled
    DrillAbortReason.PEER_CANCELLED -> R.string.buddy_abort_peer_cancelled
    DrillAbortReason.TOO_MANY_MALFORMED_FRAMES -> R.string.buddy_abort_malformed
    DrillAbortReason.HANDSHAKE_TIMEOUT -> R.string.buddy_abort_handshake_timeout
    DrillAbortReason.RESULT_EXCHANGE_TIMEOUT -> R.string.buddy_abort_result_timeout
}

/**
 * Wires the Nearby transport to the protocol state machine in `:core`.
 *
 * The machine knows nothing about Nearby: bytes in, [DrillEffect] out. That separation is what lets the entire
 * protocol — role election, duplicate suppression, reordering, heartbeat loss, version mismatch, partner
 * abandonment — be driven deterministically by unit tests with two machines wired to each other and a fake
 * clock, which is not something a transport-coupled implementation could claim.
 */
@HiltViewModel
class BuddyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfile: DeviceProfile,
) : ViewModel() {

    data class State(
        val phase: DrillPhase = DrillPhase.IDLE,
        val role: DrillRole = DrillRole.UNDECIDED,
        val peerHealth: PeerHealth = PeerHealth.UNKNOWN,
        val peerDeviceId: String? = null,
        val abortReason: DrillAbortReason? = null,
        val readyToStart: Boolean = false,
        val message: UiMessage? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val transport = NearbyBuddyTransport(context)
    private var machine: BuddyDrillMachine? = null
    private var tickJob: Job? = null
    private var started = false

    fun begin(workerId: String, scenarioId: String) {
        if (started) return
        started = true

        viewModelScope.launch {
            val deviceId = deviceProfile.deviceId()
            val built = BuddyDrillMachine(
                localDeviceId = deviceId,
                localWorkerId = workerId,
                scenarioId = scenarioId,
                catalogVersion = org.jaagruk.core.catalog.ModuleCatalog.CATALOG_VERSION,
                appVersion = BuildConfig.VERSION_NAME,
            )
            machine = built
            apply(built.begin())

            transport.start(deviceProfile.nearbyDisplayName())
            observeTransport()
            startTicking()
        }
    }

    private fun observeTransport() {
        viewModelScope.launch {
            transport.events.collect { event ->
                val active = machine ?: return@collect
                when (event) {
                    is NearbyBuddyTransport.Event.Connected ->
                        apply(active.onPeerConnected(event.endpointId))

                    is NearbyBuddyTransport.Event.Disconnected ->
                        apply(active.onPeerDisconnected())

                    is NearbyBuddyTransport.Event.BytesReceived ->
                        apply(active.onBytesReceived(event.bytes))

                    is NearbyBuddyTransport.Event.Failed ->
                        _state.value = _state.value.copy(
                            message = UiMessage.warning(R.string.buddy_transport_problem),
                        )

                    NearbyBuddyTransport.Event.Searching ->
                        _state.value = _state.value.copy(message = null)
                }
            }
        }
    }

    /**
     * Drives heartbeats, the countdown and every timeout.
     *
     * A fixed 250 ms tick rather than a timer per deadline. The machine is written to be safe at any tick rate,
     * so one loop covers all of them and there is no scheduler state to get out of step.
     */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val active = machine ?: continue
                if (active.phase.isTerminal) break
                apply(active.onTick())
            }
        }
    }

    private fun apply(effects: List<DrillEffect>) {
        val active = machine ?: return

        for (effect in effects) {
            when (effect) {
                is DrillEffect.Send -> transport.send(effect.bytes)

                is DrillEffect.PhaseChanged ->
                    _state.value = _state.value.copy(phase = effect.phase)

                is DrillEffect.RoleAssigned ->
                    _state.value = _state.value.copy(
                        role = effect.role,
                        peerDeviceId = effect.peerDeviceId,
                    )

                is DrillEffect.PeerHealthChanged ->
                    _state.value = _state.value.copy(peerHealth = effect.health)

                is DrillEffect.StartScenario ->
                    // Both handsets reach this at the same logical moment, which is what makes the two runs
                    // comparable. The drill screen takes over from here.
                    _state.value = _state.value.copy(readyToStart = true)

                is DrillEffect.Aborted ->
                    _state.value = _state.value.copy(
                        phase = DrillPhase.ABORTED,
                        abortReason = effect.reason,
                    )

                is DrillEffect.CountdownStarted,
                is DrillEffect.PeerAction,
                is DrillEffect.PeerCheckedIn,
                is DrillEffect.DistressTriggered,
                is DrillEffect.PeerRescueAction,
                is DrillEffect.PeerResult,
                is DrillEffect.Completed,
                is DrillEffect.SequenceGapSkipped,
                -> Unit
            }
        }

        _state.value = _state.value.copy(
            phase = active.phase,
            role = active.role,
            peerHealth = active.peerHealth,
            peerDeviceId = active.connectedPeerDeviceId,
            abortReason = active.abortedBecause,
        )
    }

    fun cancel() {
        machine?.let { apply(it.onLocalAbort(DrillAbortReason.USER_CANCELLED)) }
        tickJob?.cancel()
        transport.stop()
    }

    override fun onCleared() {
        tickJob?.cancel()
        transport.stop()
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
