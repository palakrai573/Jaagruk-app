package org.jaagruk.safety.ui.supervisor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jaagruk.safety.R
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.ui.LocaleManager
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatTile
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.theme.ReadinessColors

/**
 * Supervisor tools: enrolment, integrity, sync, relay and diagnostics.
 *
 * Deliberately plain. This is the screen somebody uses once at the start of a posting and then only when
 * something is wrong, so it favours stating facts over looking tidy — the device id, the public key, the chain
 * head, the clock skew. When a certificate is questioned six months later, these are the numbers that answer
 * the question.
 */
@Composable
fun SupervisorScreen(
    onBack: () -> Unit,
    onSiteScan: () -> Unit,
    onVoiceEnroll: () -> Unit,
    onVerify: () -> Unit,
    viewModel: SupervisorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.supervisor_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = state.signedInAs ?: stringResource(R.string.supervisor_not_signed_in),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.message != null) {
            item { MessageBanner(state.message, stringResource(R.string.cd_info)) }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_site_key_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.supervisor_site_key_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))

                if (state.hasSiteKey) {
                    StatusBanner(
                        text = stringResource(
                            R.string.supervisor_key_present,
                            state.siteId.orEmpty(),
                            state.keyEpoch,
                        ),
                        tone = BannerTone.SUCCESS,
                        pictogramDescription = stringResource(R.string.cd_info),
                    )
                    state.sitePublicKeyHex?.let { hex ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = hex,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = state.siteIdInput,
                        onValueChange = viewModel::setSiteIdInput,
                        label = { Text(stringResource(R.string.supervisor_site_id_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    GloveButton(
                        text = stringResource(R.string.supervisor_generate_key),
                        onClick = viewModel::generateSiteKey,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Worker enrolment. Placed immediately after the site key because that is the order it has
        // to happen in: a worker is enrolled against a site, and the site id is signed into every
        // certificate they earn.
        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_worker_enrol_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.supervisor_worker_enrol_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))

                if (!state.canEnrolWorkers) {
                    StatusBanner(
                        text = stringResource(R.string.supervisor_worker_needs_site),
                        tone = BannerTone.WARNING,
                        pictogramDescription = stringResource(R.string.cd_warning),
                    )
                } else {
                    OutlinedTextField(
                        value = state.newWorkerId,
                        onValueChange = viewModel::setNewWorkerId,
                        label = {
                            Text(
                                stringResource(
                                    R.string.supervisor_worker_id_hint,
                                    WorkerRepository.WORKER_ID_EXAMPLE,
                                ),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.newWorkerName,
                        onValueChange = viewModel::setNewWorkerName,
                        label = { Text(stringResource(R.string.supervisor_worker_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.supervisor_worker_language),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocaleManager.supported.forEach { tag ->
                            if (tag == state.newWorkerLanguage) {
                                GloveButton(
                                    text = LocaleManager.endonym(tag),
                                    onClick = { viewModel.setNewWorkerLanguage(tag) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                GloveOutlinedButton(
                                    text = LocaleManager.endonym(tag),
                                    onClick = { viewModel.setNewWorkerLanguage(tag) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.supervisor_worker_pictogram),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.newWorkerPictogramMode,
                            onCheckedChange = viewModel::setNewWorkerPictogramMode,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    GloveButton(
                        text = stringResource(R.string.supervisor_enrol_worker),
                        onClick = viewModel::registerWorker,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (state.workersNotOnServer > 0) {
                            stringResource(
                                R.string.supervisor_workers_pending_upload,
                                state.workersNotOnServer,
                            )
                        } else {
                            stringResource(R.string.supervisor_workers_all_uploaded)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_readiness_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                val readiness = state.readiness
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        value = readiness?.ready?.toString() ?: "0",
                        label = stringResource(R.string.band_ready),
                        accent = ReadinessColors.ready,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = readiness?.due?.toString() ?: "0",
                        label = stringResource(R.string.band_due),
                        accent = ReadinessColors.due,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = readiness?.expired?.toString() ?: "0",
                        label = stringResource(R.string.band_expired),
                        accent = ReadinessColors.expired,
                        modifier = Modifier.weight(1f),
                    )
                }

                // The cohort that matters most, called out separately. Legally clear to work and practically
                // unprepared is precisely the group a merged score would hide.
                if ((readiness?.statutorilyValidButStale ?: 0) > 0) {
                    Spacer(Modifier.height(10.dp))
                    StatusBanner(
                        text = stringResource(
                            R.string.supervisor_valid_but_stale,
                            readiness?.statutorilyValidButStale ?: 0,
                        ),
                        tone = BannerTone.WARNING,
                        pictogramDescription = stringResource(R.string.cd_warning),
                    )
                }
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_chain_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.supervisor_chain_summary,
                        state.certificateCount,
                        state.chainHeadSeq,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.chainFirstProblemSeq?.let { seq ->
                    Spacer(Modifier.height(8.dp))
                    StatusBanner(
                        text = stringResource(R.string.supervisor_chain_problem, seq),
                        tone = BannerTone.ERROR,
                        pictogramDescription = stringResource(R.string.cd_stop),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GloveButton(
                    text = stringResource(R.string.supervisor_audit_chain),
                    onClick = viewModel::auditChain,
                    enabled = !state.busy && state.siteId != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_ar_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.supervisor_anchor_count, state.anchorCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Stated plainly rather than implied. Without a Cloud Anchor key, a scan lasts for the session
                // and no longer, and a supervisor needs to know that before relying on it.
                Spacer(Modifier.height(8.dp))
                StatusBanner(
                    text = stringResource(
                        if (state.cloudAnchorsEnabled) {
                            R.string.supervisor_cloud_anchors_on
                        } else {
                            R.string.supervisor_cloud_anchors_off
                        },
                    ),
                    tone = if (state.cloudAnchorsEnabled) BannerTone.INFO else BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_info),
                )
                Spacer(Modifier.height(10.dp))
                GloveButton(
                    text = stringResource(R.string.supervisor_scan_site),
                    onClick = onSiteScan,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_voice_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.supervisor_voice_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                GloveButton(
                    text = stringResource(R.string.supervisor_enrol_voice),
                    onClick = onVoiceEnroll,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_sync_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.supervisor_sync_summary,
                        state.pendingSync,
                        state.abandonedSync,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.neverSynced) {
                    Spacer(Modifier.height(8.dp))
                    StatusBanner(
                        text = stringResource(R.string.supervisor_never_synced),
                        tone = BannerTone.INFO,
                        pictogramDescription = stringResource(R.string.cd_info),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GloveButton(
                    text = stringResource(R.string.action_sync_now),
                    onClick = viewModel::syncNow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_relay_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.supervisor_relay_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.supervisor_relay_progress,
                        state.relayState.recordsSent,
                        state.relayState.recordsReceived,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GloveButton(
                        text = stringResource(R.string.supervisor_relay_collect),
                        onClick = viewModel::startRelayCollecting,
                        modifier = Modifier.weight(1f),
                    )
                    GloveOutlinedButton(
                        text = stringResource(R.string.supervisor_relay_offer),
                        onClick = viewModel::startRelayOffering,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GloveOutlinedButton(
                    text = stringResource(R.string.supervisor_relay_stop),
                    onClick = viewModel::stopRelay,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.supervisor_diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                DiagnosticRow(
                    stringResource(R.string.supervisor_device_id),
                    state.deviceId.take(16),
                )
                DiagnosticRow(
                    stringResource(R.string.supervisor_device_registered),
                    state.deviceRegistered.toString(),
                )
                DiagnosticRow(
                    stringResource(R.string.supervisor_clock_skew),
                    "${state.clockSkewSeconds}s",
                )
                DiagnosticRow(
                    stringResource(R.string.supervisor_worker_count),
                    "${state.workerCount} (${state.workersWithPin} with PIN)",
                )
                DiagnosticRow(
                    stringResource(R.string.supervisor_site_scanned),
                    state.siteScanned.toString(),
                )
            }
        }

        item {
            Column {
                GloveOutlinedButton(
                    text = stringResource(R.string.action_verify_certificate),
                    onClick = onVerify,
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

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
