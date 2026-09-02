package org.jaagruk.safety.ui.supervisor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.catalog.ArTargets
import org.jaagruk.safety.R
import org.jaagruk.safety.ar.ArAvailability
import org.jaagruk.safety.ar.ArController
import org.jaagruk.safety.ar.ArControllerFactory
import org.jaagruk.safety.ar.ArSurfaceHost
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.UiMessage
import javax.inject.Inject

/**
 * Site scanning: pinning AR markers to the real objects they represent.
 *
 * This is what turns a generic drill into training for *this* corridor. The supervisor stands facing the real
 * primary exit, taps it, and from then on every worker's fire drill puts the exit marker on that doorway. A
 * worker who learns "the exit is through there" has learned something about their own workplace rather than
 * about a template.
 *
 * Only the semantically anchored targets are offered. Pinning a toolbox changes nothing about what the drill
 * teaches, and a list of thirty-one targets would make the four that matter harder to find.
 */
@Composable
fun SiteScanScreen(
    onBack: () -> Unit,
    viewModel: SiteScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = viewModel.controller

    Box(Modifier.fillMaxSize()) {
        if (controller != null && controller.capability.usesCamera) {
            ArSurfaceHost(controller = controller)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                SectionCard {
                    Text(
                        text = stringResource(R.string.sitescan_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.sitescan_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.sitescan_progress,
                            state.placedSemanticCount,
                            SiteRepository.MIN_SEMANTIC_ANCHORS_FOR_SCANNED,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (state.message != null) {
                item { MessageBanner(state.message, stringResource(R.string.cd_info)) }
            }

            if (controller != null && !controller.capability.usesCamera) {
                item {
                    StatusBanner(
                        text = stringResource(R.string.sitescan_no_camera),
                        tone = BannerTone.WARNING,
                        pictogramDescription = stringResource(R.string.cd_warning),
                    )
                }
            }

            if (state.capability == ArAvailability.Capability.SENSOR_FALLBACK) {
                item {
                    // Said plainly. A sensor-only anchor has no world registration, so it would not survive
                    // the supervisor turning around — storing one would put a row in the database claiming
                    // this site is scanned when nothing is pinned to anything.
                    StatusBanner(
                        text = stringResource(R.string.sitescan_needs_arcore),
                        tone = BannerTone.WARNING,
                        pictogramDescription = stringResource(R.string.cd_warning),
                    )
                }
            }

            items(state.targets, key = { it.targetKey }) { target ->
                SectionCard {
                    Text(
                        text = stringResource(targetLabel(target.targetKey)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (target.placed) {
                            stringResource(
                                if (target.deviceScoped) {
                                    R.string.sitescan_placed_session
                                } else {
                                    R.string.sitescan_placed_cloud
                                },
                            )
                        } else {
                            stringResource(R.string.sitescan_not_placed)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    GloveButton(
                        text = stringResource(
                            if (target.placed) R.string.sitescan_replace else R.string.sitescan_place,
                        ),
                        onClick = { viewModel.place(target.targetKey) },
                        enabled = state.canPlace && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Column {
                    // Loud on purpose. Training against anchors that no longer match the real layout is worse
                    // than a generic template, because it teaches a wrong location confidently.
                    GloveOutlinedButton(
                        text = stringResource(R.string.sitescan_clear_all),
                        onClick = viewModel::clearAll,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    GloveOutlinedButton(
                        text = stringResource(R.string.action_back),
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

private fun targetLabel(targetKey: String): Int = when (targetKey) {
    ArTargets.EXIT_PRIMARY -> R.string.target_exit_primary
    ArTargets.EXIT_SECONDARY -> R.string.target_exit_secondary
    ArTargets.ASSEMBLY_POINT -> R.string.target_assembly_point
    ArTargets.EXTINGUISHER_STATION -> R.string.target_extinguisher_station
    ArTargets.FIRE_ALARM_POINT -> R.string.target_fire_alarm_point
    ArTargets.GAS_ZONE -> R.string.target_gas_zone
    ArTargets.VENT_FAN -> R.string.target_vent_fan
    ArTargets.CONFINED_SPACE_ENTRY -> R.string.target_confined_space_entry
    ArTargets.ISOLATOR_SWITCH -> R.string.target_isolator_switch
    ArTargets.MACHINE_NIP_POINT -> R.string.target_machine_nip_point
    ArTargets.REFUGE_CHAMBER -> R.string.target_refuge_chamber
    ArTargets.ANCHOR_POINT_VALID -> R.string.target_anchor_point_valid
    ArTargets.DAMAGED_CABLE -> R.string.target_damaged_cable
    else -> R.string.target_generic
}

@HiltViewModel
class SiteScanViewModel @Inject constructor(
    private val sites: SiteRepository,
    private val deviceProfile: DeviceProfile,
    arFactory: ArControllerFactory,
) : ViewModel() {

    data class TargetRow(
        val targetKey: String,
        val placed: Boolean,
        val deviceScoped: Boolean,
    )

    data class State(
        val siteId: String? = null,
        val targets: List<TargetRow> = emptyList(),
        val placedSemanticCount: Int = 0,
        val capability: ArAvailability.Capability = ArAvailability.Capability.PICTOGRAM_ONLY,
        val canPlace: Boolean = false,
        val busy: Boolean = false,
        val message: UiMessage? = null,
    )

    /** Created once, so the AR session survives recomposition and list scrolling. */
    val controller: ArController = arFactory.create()

    private val _state = MutableStateFlow(State(capability = controller.capability))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val siteId = deviceProfile.activeSiteId()
            _state.value = _state.value.copy(
                siteId = siteId,
                // Only the ARCore path can place a persistent anchor. The others report false rather than
                // storing something that would not survive the supervisor turning around.
                canPlace = controller.capability == ArAvailability.Capability.ARCORE_READY &&
                    siteId != null,
            )
            refresh()
        }
    }

    private suspend fun refresh() {
        val siteId = _state.value.siteId ?: return
        val stored = sites.anchors(siteId).associateBy { it.targetKey }

        val rows = ArTargets.SEMANTIC_ANCHORED.sorted().map { targetKey ->
            val anchor = stored[targetKey]
            TargetRow(
                targetKey = targetKey,
                placed = anchor != null,
                deviceScoped = anchor?.deviceScoped == true,
            )
        }

        _state.value = _state.value.copy(
            targets = rows,
            placedSemanticCount = rows.count { it.placed },
        )
    }

    fun place(targetKey: String) {
        val siteId = _state.value.siteId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val placed = controller.placeSiteAnchor(siteId, targetKey, label = null)
            _state.value = _state.value.copy(
                busy = false,
                message = if (placed) {
                    UiMessage.success(R.string.sitescan_placed_ok)
                } else {
                    UiMessage.error(R.string.sitescan_place_failed)
                },
            )
            refresh()
        }
    }

    fun clearAll() {
        val siteId = _state.value.siteId ?: return
        viewModelScope.launch {
            sites.clearAnchors(siteId)
            _state.value = _state.value.copy(
                message = UiMessage.warning(R.string.sitescan_cleared),
            )
            refresh()
        }
    }

    override fun onCleared() {
        controller.detach()
        super.onCleared()
    }
}
