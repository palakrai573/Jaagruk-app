package org.jaagruk.safety.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.core.retention.RequiredAction
import org.jaagruk.safety.R
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.PictogramIcon
import org.jaagruk.safety.ui.components.ReadinessBadge
import org.jaagruk.safety.ui.components.ReadinessBar
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatTile
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.catalogString
import org.jaagruk.safety.ui.theme.ReadinessColors

/**
 * The worker's home screen: what is current, what has gone stale, and what to do about it.
 *
 * Readiness is shown as a live figure rather than a pass/fail badge, because "certified eleven months ago"
 * and "would act correctly right now" are different questions and the second one is what keeps people alive.
 * The module list is ordered by what needs attention, not by module number.
 */
@Composable
fun HomeScreen(
    workerId: String,
    highlightRefreshers: Boolean,
    onStartDrill: (scenarioId: String, mode: String) -> Unit,
    onStartBuddyDrill: (scenarioId: String) -> Unit,
    onCertificates: () -> Unit,
    onReportHazard: () -> Unit,
    onVerify: () -> Unit,
    onSupervisorTools: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(workerId) { viewModel.load(workerId) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.workerName, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = state.siteId ?: stringResource(R.string.home_no_site),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                GloveOutlinedButton(
                    text = stringResource(R.string.action_sign_out),
                    onClick = {
                        viewModel.signOut()
                        onSignOut()
                    },
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = state.dueCount.toString(),
                    label = stringResource(R.string.home_needs_attention),
                    accent = if (state.dueCount > 0) ReadinessColors.due else ReadinessColors.ready,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.modules.count { it.standing.band == ReadinessBand.READY }.toString(),
                    label = stringResource(R.string.home_ready_modules),
                    accent = ReadinessColors.ready,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.message != null) {
            item { MessageBanner(state.message, stringResource(R.string.cd_info)) }
        }

        // Deliberately not phrased as a failure. The training is already recorded and signed; this is a
        // delivery note, and wording it as an error would teach workers to distrust a working system.
        if (state.pendingSyncCount > 0) {
            item {
                StatusBanner(
                    text = stringResource(R.string.home_queued_records, state.pendingSyncCount),
                    tone = BannerTone.INFO,
                    pictogramDescription = stringResource(R.string.cd_info),
                )
            }
        }

        if (state.abandonedSyncCount > 0) {
            item {
                StatusBanner(
                    text = stringResource(R.string.home_rejected_records, state.abandonedSyncCount),
                    tone = BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_warning),
                )
            }
        }

        // A clock that is badly wrong changes the date signed into a certificate, so the worker is told
        // rather than left to find out when an inspector questions the issue date.
        if (state.clockSkewSeconds != 0L &&
            kotlin.math.abs(state.clockSkewSeconds) > CLOCK_SKEW_WARN_SECONDS
        ) {
            item {
                StatusBanner(
                    text = stringResource(
                        R.string.home_clock_skew,
                        kotlin.math.abs(state.clockSkewSeconds) / 60,
                    ),
                    tone = BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_warning),
                )
            }
        }

        state.resumableRunId?.let { runId ->
            item {
                SectionCard {
                    Text(
                        text = stringResource(R.string.home_resume_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_resume_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    GloveOutlinedButton(
                        text = stringResource(R.string.action_discard_run),
                        onClick = viewModel::discardResumableRun,
                    )
                }
            }
        }

        if (highlightRefreshers && state.dueCount > 0) {
            item {
                StatusBanner(
                    text = stringResource(R.string.home_refreshers_due, state.dueCount),
                    tone = BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_warning),
                )
            }
        }

        items(state.modules, key = { it.moduleId }) { row ->
            ModuleCard(
                row = row,
                pictogramMode = state.pictogramMode,
                onStart = { scenarioId, mode -> onStartDrill(scenarioId, mode) },
                onStartBuddy = onStartBuddyDrill,
            )
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.home_other_actions),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GloveButton(
                        text = stringResource(R.string.action_report_hazard),
                        onClick = onReportHazard,
                        modifier = Modifier.weight(1f),
                    )
                    GloveOutlinedButton(
                        text = stringResource(R.string.action_my_certificates),
                        onClick = onCertificates,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GloveOutlinedButton(
                        text = stringResource(R.string.action_verify_certificate),
                        onClick = onVerify,
                        modifier = Modifier.weight(1f),
                    )
                    GloveOutlinedButton(
                        text = stringResource(R.string.action_sync_now),
                        onClick = viewModel::requestSync,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GloveOutlinedButton(
                    text = stringResource(R.string.action_supervisor),
                    onClick = onSupervisorTools,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModuleCard(
    row: HomeViewModel.ModuleRow,
    pictogramMode: Boolean,
    onStart: (scenarioId: String, mode: String) -> Unit,
    onStartBuddy: (String) -> Unit,
) {
    val title = catalogString(row.titleKey)
    val description = catalogString(row.descriptionKey)

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PictogramIcon(
                pictogram = modulePictogram(row),
                contentDescription = title,
                size = 52.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!pictogramMode) {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        ReadinessBar(
            permille = row.standing.readinessPermille,
            band = row.standing.band,
            contentDescription = stringResource(
                R.string.cd_readiness_bar,
                row.standing.readinessPermille / 10,
            ),
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessBadge(
                band = row.standing.band,
                permille = row.standing.readinessPermille,
                label = stringResource(bandLabel(row.standing.band)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(actionLabel(row.standing.requiredAction)),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // A refresher renews readiness but never the statutory clock. Saying so on the button is what stops
        // a worker assuming a two-minute check has extended a twelve-month certificate.
        if (row.standing.validity.statutorilyValidButStale) {
            Spacer(Modifier.height(8.dp))
            StatusBanner(
                text = stringResource(R.string.home_valid_but_stale),
                tone = BannerTone.WARNING,
                pictogramDescription = stringResource(R.string.cd_warning),
            )
        }

        if (!row.fullyImplemented) {
            Spacer(Modifier.height(8.dp))
            StatusBanner(
                text = stringResource(R.string.home_module_no_bespoke_ar),
                tone = BannerTone.INFO,
                pictogramDescription = stringResource(R.string.cd_info),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val refresherAvailable = row.refresherScenarioId != null &&
                row.recommendedMode == AssessmentMode.REFRESHER

            GloveButton(
                text = stringResource(
                    if (refresherAvailable) R.string.action_start_refresher else R.string.action_start_module,
                ),
                onClick = {
                    if (refresherAvailable) {
                        onStart(row.refresherScenarioId!!, AssessmentMode.REFRESHER.name)
                    } else {
                        onStart(row.fullScenarioId, AssessmentMode.INITIAL.name)
                    }
                },
                modifier = Modifier.weight(1f),
            )

            if (refresherAvailable) {
                GloveOutlinedButton(
                    text = stringResource(R.string.action_full_module),
                    onClick = { onStart(row.fullScenarioId, AssessmentMode.INITIAL.name) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        row.buddyScenarioId?.let { buddyScenario ->
            Spacer(Modifier.height(10.dp))
            GloveOutlinedButton(
                text = stringResource(R.string.action_buddy_drill),
                onClick = { onStartBuddy(buddyScenario) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Practice never certifies and never touches readiness. Offered anyway, because a worker who wants
        // to rehearse before their assessed run should be able to.
        Spacer(Modifier.height(10.dp))
        GloveOutlinedButton(
            text = stringResource(R.string.action_practice),
            onClick = { onStart(row.fullScenarioId, AssessmentMode.PRACTICE.name) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun modulePictogram(row: HomeViewModel.ModuleRow) =
    org.jaagruk.core.catalog.ModuleCatalog.byId(row.moduleId)?.pictogram
        ?: org.jaagruk.core.catalog.Pictogram.WARNING_GENERAL

private fun bandLabel(band: ReadinessBand): Int = when (band) {
    ReadinessBand.READY -> R.string.band_ready
    ReadinessBand.DUE -> R.string.band_due
    ReadinessBand.STALE -> R.string.band_stale
    ReadinessBand.EXPIRED -> R.string.band_expired
}

private fun actionLabel(action: RequiredAction): Int = when (action) {
    RequiredAction.NONE -> R.string.action_required_none
    RequiredAction.REFRESHER_DUE -> R.string.action_required_refresher
    RequiredAction.FULL_RERUN_REQUIRED -> R.string.action_required_full
    RequiredAction.NEVER_CERTIFIED -> R.string.action_required_never
}

/** Skew beyond which the worker is warned. Matches [org.jaagruk.safety.sync.TimeSyncTracker]. */
private const val CLOCK_SKEW_WARN_SECONDS = 15 * 60L
