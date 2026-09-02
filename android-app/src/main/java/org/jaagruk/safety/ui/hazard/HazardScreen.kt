package org.jaagruk.safety.ui.hazard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jaagruk.safety.R
import org.jaagruk.safety.data.hazard.HazardCategory
import org.jaagruk.safety.data.hazard.HazardSeverity
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.OptionCard
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.catalogString

/**
 * Near-miss and hazard reporting.
 *
 * Designed around the moment it is actually used: a worker standing in front of the hazard, one-handed, in
 * gloves, with no signal. So the category is a grid of pictograms rather than a dropdown, the note is
 * optional, a voice note replaces typing entirely, and the report is durable the instant it is stored —
 * upload is a separate concern that may happen hours later, possibly relayed through a supervisor's handset.
 *
 * Position is optional and usually absent, because there is no GPS fix underground. A zone label covers it,
 * and a report with neither is still accepted: "blocked exit, somewhere on this site" is worth having, and
 * refusing it would teach people to stop reporting.
 */
@Composable
fun HazardScreen(
    reporterWorkerId: String?,
    onDone: () -> Unit,
    viewModel: HazardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(reporterWorkerId) { viewModel.load(reporterWorkerId) }

    LaunchedEffect(state.filed) {
        if (state.filed) onDone()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.hazard_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.hazard_offline_note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.message != null) {
            item { MessageBanner(state.message, stringResource(R.string.cd_info)) }
        }

        item {
            Text(
                text = stringResource(R.string.hazard_what),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        item {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            ) {
                items(HazardCategory.entries.toList(), key = { it.name }) { category ->
                    OptionCard(
                        label = catalogString(category.labelKey),
                        pictogram = category.pictogram,
                        onClick = { viewModel.selectCategory(category) },
                        selected = state.category == category,
                        showLabel = !state.pictogramMode,
                    )
                }
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.hazard_how_bad),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HazardSeverity.entries.forEach { severity ->
                        if (state.severity == severity) {
                            GloveButton(
                                text = catalogString(severity.labelKey),
                                onClick = { viewModel.selectSeverity(severity) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            GloveOutlinedButton(
                                text = catalogString(severity.labelKey),
                                onClick = { viewModel.selectSeverity(severity) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.hazard_where),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                // A zone label, not coordinates. There is no GPS fix underground, and "3 North, near the
                // conveyor head" is what a supervisor can act on anyway.
                OutlinedTextField(
                    value = state.zoneLabel,
                    onValueChange = viewModel::setZoneLabel,
                    label = { Text(stringResource(R.string.hazard_zone_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.hazard_note),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    label = { Text(stringResource(R.string.hazard_note_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                // The voice note is the important half of this card. A worker who cannot comfortably write
                // will say it in fifteen seconds, and a supervisor understands it immediately.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.recording) {
                        GloveButton(
                            text = stringResource(
                                R.string.hazard_stop_recording,
                                state.recordedSeconds,
                            ),
                            onClick = viewModel::stopRecording,
                            modifier = Modifier.weight(1f),
                            destructive = true,
                        )
                    } else {
                        GloveOutlinedButton(
                            text = stringResource(
                                if (state.hasVoiceNote) {
                                    R.string.hazard_rerecord_voice
                                } else {
                                    R.string.hazard_record_voice
                                },
                            ),
                            onClick = viewModel::startRecording,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            Column {
                GloveButton(
                    text = stringResource(R.string.hazard_submit),
                    onClick = viewModel::submit,
                    enabled = state.category != null && !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                GloveOutlinedButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = {
                        viewModel.cancel()
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
