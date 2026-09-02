package org.jaagruk.safety.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.catalog.SafetyModule
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.core.retention.RequiredAction
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.data.repo.RetentionRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.sync.SyncStatusProvider
import org.jaagruk.safety.sync.TimeSyncTracker
import org.jaagruk.safety.ui.components.UiMessage
import javax.inject.Inject

/**
 * The worker's home screen state.
 *
 * The screen answers one question — "what should I do next?" — and the ordering here reflects that: modules
 * whose readiness has decayed come first, then never-attempted ones, then everything current. A list sorted
 * by module code would bury the one thing that actually needs attention.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val retention: RetentionRepository,
    private val workers: WorkerRepository,
    private val assessments: AssessmentRepository,
    private val deviceProfile: DeviceProfile,
    private val syncScheduler: SyncScheduler,
    private val timeSync: TimeSyncTracker,
    syncStatus: SyncStatusProvider,
) : ViewModel() {

    data class ModuleRow(
        val moduleId: String,
        val moduleCode: Int,
        val titleKey: String,
        val descriptionKey: String,
        val standing: RetentionRepository.ModuleStanding,
        val recommendedMode: AssessmentMode,
        val fullScenarioId: String,
        val refresherScenarioId: String?,
        val buddyScenarioId: String?,
        /** True for the two modules that ship with complete bespoke AR scenes. */
        val fullyImplemented: Boolean,
    ) {
        val needsAttention: Boolean
            get() = standing.band != ReadinessBand.READY ||
                standing.requiredAction == RequiredAction.NEVER_CERTIFIED
    }

    data class State(
        val workerName: String = "",
        val workerId: String = "",
        val siteId: String? = null,
        val pictogramMode: Boolean = false,
        val modules: List<ModuleRow> = emptyList(),
        val dueCount: Int = 0,
        val pendingSyncCount: Int = 0,
        val abandonedSyncCount: Int = 0,
        val clockSkewSeconds: Long = 0L,
        val neverSynced: Boolean = true,
        val resumableRunId: String? = null,
        val message: UiMessage? = null,
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var workerId: String = ""

    init {
        viewModelScope.launch {
            syncStatus.status.collect { status ->
                _state.value = _state.value.copy(
                    pendingSyncCount = status.pending,
                    abandonedSyncCount = status.abandoned,
                )
            }
        }
    }

    fun load(workerId: String) {
        if (this.workerId == workerId && !_state.value.loading) return
        this.workerId = workerId

        viewModelScope.launch {
            val worker = workers.find(workerId)
            _state.value = _state.value.copy(
                workerId = workerId,
                workerName = worker?.fullName.orEmpty(),
                siteId = worker?.siteId ?: deviceProfile.activeSiteId(),
                pictogramMode = worker?.pictogramMode == true,
                clockSkewSeconds = timeSync.skewSeconds(),
                neverSynced = timeSync.hasNeverSynced(),
                // A run interrupted by process death is offered back rather than lost. The steps already
                // sealed keep their measured latencies; only the unreached ones are still open.
                resumableRunId = assessments.findResumable(workerId)?.runId,
            )
        }

        viewModelScope.launch {
            retention.observeStandings(workerId).collect { standings ->
                val rows = standings.mapNotNull { standing ->
                    val module = ModuleCatalog.byId(standing.moduleId) ?: return@mapNotNull null
                    ModuleRow(
                        moduleId = module.moduleId,
                        moduleCode = module.moduleCode,
                        titleKey = module.titleKey,
                        descriptionKey = module.descriptionKey,
                        standing = standing,
                        recommendedMode = retention.recommendedMode(workerId, module.moduleId),
                        fullScenarioId = module.fullScenarioId(),
                        refresherScenarioId = module.refresherScenarioId(),
                        buddyScenarioId = module.buddyScenarioId(),
                        fullyImplemented = standing.fullyImplemented,
                    )
                }.sortedWith(
                    // Worst readiness first, then never-certified, then by module code so the order is
                    // stable between refreshes.
                    compareBy(
                        { if (it.needsAttention) 0 else 1 },
                        { it.standing.readinessPermille },
                        { it.moduleCode },
                    ),
                )

                _state.value = _state.value.copy(
                    modules = rows,
                    dueCount = rows.count { it.needsAttention },
                    loading = false,
                )
            }
        }
    }

    fun requestSync() {
        syncScheduler.requestSyncNow()
        _state.value = _state.value.copy(
            message = UiMessage.info(org.jaagruk.safety.R.string.home_sync_requested),
        )
    }

    fun discardResumableRun() {
        val runId = _state.value.resumableRunId ?: return
        viewModelScope.launch {
            assessments.discard(runId)
            _state.value = _state.value.copy(resumableRunId = null)
        }
    }

    fun signOut() {
        viewModelScope.launch { deviceProfile.setActiveWorkerId(null) }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/**
 * Scenario lookups by role.
 *
 * The catalog stores an ordered list, and these pick out the one the UI means. Doing it by position would
 * break the moment a scenario is inserted; doing it by naming convention would break silently. Matching on
 * the scenario's own declared properties is the only version that stays correct.
 */
private fun SafetyModule.fullScenarioId(): String =
    scenarios.firstOrNull { !it.isRefresherVariant && !it.requiresBuddy }?.scenarioId
        ?: scenarios.first().scenarioId

private fun SafetyModule.refresherScenarioId(): String? =
    scenarios.firstOrNull { it.isRefresherVariant }?.scenarioId

private fun SafetyModule.buddyScenarioId(): String? =
    scenarios.firstOrNull { it.requiresBuddy }?.scenarioId
