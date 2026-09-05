package org.jaagruk.safety.ui.verify

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.catalog.Pictogram
import org.jaagruk.core.crypto.ChainStatus
import org.jaagruk.safety.R
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.PictogramIcon
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.catalogString

/**
 * Certificate verification, for an inspector at a gate.
 *
 * Everything on this screen happens on device. The QR carries the signed attestation itself, so the
 * signature is checked against the site's stored Ed25519 public key with no network, no account and no
 * lookup. That is the property that makes the whole scheme worth having: a verdict that does not depend on
 * the gatehouse having signal.
 *
 * The seven-state verdict is shown as-is rather than collapsed into valid/invalid. "Signature valid, chain
 * unknown on this device" is a genuinely different situation from "chain broken", and flattening them would
 * either cry wolf on a fresh handset or hide real tampering — either way, inspectors would stop trusting the
 * tool.
 */
@Composable
fun VerifyScreen(
    incomingQr: String?,
    onBack: () -> Unit,
    viewModel: VerifyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(incomingQr) {
        if (!incomingQr.isNullOrBlank()) viewModel.verify(incomingQr)
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
                text = stringResource(R.string.verify_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.verify_offline_note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.scanning) {
            item {
                if (cameraGranted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f),
                    ) {
                        QrScanner(onQrDetected = viewModel::verify)
                    }
                } else {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.verify_camera_needed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        GloveButton(
                            text = stringResource(R.string.action_allow_camera),
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Manual entry is not a fallback nobody uses. A laminated card two years old gets scratched, and an
        // inspector reading the text form off it is the difference between a verdict and a shrug.
        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.verify_manual_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.manualEntry,
                    onValueChange = viewModel::setManualEntry,
                    label = { Text(stringResource(R.string.verify_manual_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GloveButton(
                        text = stringResource(R.string.action_verify),
                        onClick = viewModel::verifyManualEntry,
                        enabled = state.manualEntry.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                    GloveOutlinedButton(
                        text = stringResource(
                            if (state.scanning) R.string.action_stop_scanning else R.string.action_scan,
                        ),
                        onClick = viewModel::toggleScanning,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        state.verdict?.let { verdict ->
            item { VerdictCard(verdict = verdict, state = state, viewModel = viewModel) }
            item { ServerInsightCard(state = state) }
        }

        if (state.reasons.isNotEmpty()) {
            item {
                SectionCard {
                    Text(
                        text = stringResource(R.string.verify_details_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            items(state.reasons) { reason ->
                StatusBanner(
                    text = reason,
                    tone = BannerTone.INFO,
                    pictogramDescription = stringResource(R.string.cd_info),
                )
            }
        }

        item {
            GloveOutlinedButton(
                text = stringResource(R.string.action_back),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * What the server adds, when it can be reached.
 *
 * Presented below the verdict and visibly secondary to it. The heading says the decision above is the
 * one that counts, because an inspector who starts treating this card as the answer has been given a
 * verification tool that stops working the moment they walk underground.
 */
@Composable
private fun ServerInsightCard(state: VerifyViewModel.State) {
    SectionCard {
        Text(
            text = stringResource(R.string.verify_server_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.verify_server_explainer),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        when (state.crossCheck) {
            VerifyViewModel.CrossCheck.NOT_ATTEMPTED,
            VerifyViewModel.CrossCheck.CHECKING,
            -> Text(
                text = stringResource(R.string.verify_server_checking),
                style = MaterialTheme.typography.bodyMedium,
            )

            VerifyViewModel.CrossCheck.UNAVAILABLE -> StatusBanner(
                text = stringResource(R.string.verify_server_unavailable),
                tone = BannerTone.INFO,
                pictogramDescription = stringResource(R.string.cd_info),
            )

            VerifyViewModel.CrossCheck.RECEIVED -> {
                val insight = state.insight
                if (insight == null) {
                    Text(
                        text = stringResource(R.string.verify_server_nothing_extra),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    insight.workerFullName?.let { name ->
                        Text(
                            text = stringResource(R.string.verify_server_worker, name),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    insight.readinessPermille?.let { permille ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.verify_server_readiness,
                                permille,
                                insight.readinessBand ?: "",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    insight.statutoryValid?.let { valid ->
                        Spacer(Modifier.height(8.dp))
                        StatusBanner(
                            text = stringResource(
                                if (valid) {
                                    R.string.verify_server_statutory_valid
                                } else {
                                    R.string.verify_server_statutory_expired
                                },
                            ),
                            tone = if (valid) BannerTone.SUCCESS else BannerTone.WARNING,
                            pictogramDescription = stringResource(R.string.cd_info),
                        )
                    }
                    // A disagreement is reported, never obeyed. The signature check on the bytes in
                    // the inspector's hand is the verdict; this means the two are looking at
                    // different records and somebody should find out why.
                    insight.disagreesWithStatus?.let { serverStatus ->
                        Spacer(Modifier.height(8.dp))
                        StatusBanner(
                            text = stringResource(
                                R.string.verify_server_disagrees,
                                serverStatus,
                            ),
                            tone = BannerTone.WARNING,
                            pictogramDescription = stringResource(R.string.cd_warning),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(
    verdict: VerifyViewModel.Verdict,
    state: VerifyViewModel.State,
    viewModel: VerifyViewModel,
) {
    val tone = when (verdict.status) {
        ChainStatus.VERIFIED -> BannerTone.SUCCESS
        ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN -> BannerTone.INFO
        ChainStatus.BROKEN_LINK, ChainStatus.BAD_SIGNATURE -> BannerTone.ERROR
        else -> BannerTone.WARNING
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PictogramIcon(
                pictogram = when (tone) {
                    BannerTone.SUCCESS -> Pictogram.ANSWER_YES
                    BannerTone.ERROR -> Pictogram.STOP_HAND
                    else -> Pictogram.WARNING_GENERAL
                },
                contentDescription = stringResource(statusLabel(verdict.status)),
                size = 48.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(statusLabel(verdict.status)),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(statusExplanation(verdict.status)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (verdict.siteId != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.verify_site, verdict.siteId),
                style = MaterialTheme.typography.labelMedium,
            )
            verdict.moduleCode?.let { code ->
                Text(
                    text = catalogString(
                        ModuleCatalog.byCode(code)?.titleKey ?: "module_fire_title",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(R.string.verify_sequence, verdict.seq ?: 0L),
                style = MaterialTheme.typography.labelMedium,
            )
            verdict.scorePermille?.let { score ->
                Text(
                    text = stringResource(R.string.verify_score, score / 10),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            // Shown as a hash, never as a name or an id. A dropped card must not identify its holder.
            verdict.workerIdHashHex?.let { hash ->
                Text(
                    text = stringResource(R.string.verify_worker_hash, hash.take(16)),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Confirms identity without the certificate ever carrying a name. The inspector types the number off
        // the physical card and the app hashes it, in constant time, so the device cannot be used as an
        // oracle to enumerate worker ids.
        if (verdict.status.isTrustworthy) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.verify_confirm_identity),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            IdentityCheck(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun IdentityCheck(state: VerifyViewModel.State, viewModel: VerifyViewModel) {
    Column {
        OutlinedTextField(
            value = state.candidateWorkerId,
            onValueChange = viewModel::setCandidateWorkerId,
            label = { Text(stringResource(R.string.verify_worker_id_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        GloveButton(
            text = stringResource(R.string.action_check_identity),
            onClick = viewModel::checkIdentity,
            enabled = state.candidateWorkerId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        state.identityMatches?.let { matches ->
            Spacer(Modifier.height(8.dp))
            StatusBanner(
                text = stringResource(
                    if (matches) R.string.verify_identity_match else R.string.verify_identity_mismatch,
                ),
                tone = if (matches) BannerTone.SUCCESS else BannerTone.ERROR,
                pictogramDescription = stringResource(R.string.cd_info),
            )
        }
    }
}

private fun statusLabel(status: ChainStatus): Int = when (status) {
    ChainStatus.VERIFIED -> R.string.verify_status_verified
    ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN -> R.string.verify_status_signature_only
    ChainStatus.UNKNOWN_SITE_KEY -> R.string.verify_status_unknown_site
    ChainStatus.BAD_SIGNATURE -> R.string.verify_status_bad_signature
    ChainStatus.BROKEN_LINK -> R.string.verify_status_broken_link
    ChainStatus.MALFORMED -> R.string.verify_status_malformed
    ChainStatus.SEQUENCE_GAP -> R.string.verify_status_sequence_gap
}

private fun statusExplanation(status: ChainStatus): Int = when (status) {
    ChainStatus.VERIFIED -> R.string.verify_explain_verified
    ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN -> R.string.verify_explain_signature_only
    ChainStatus.UNKNOWN_SITE_KEY -> R.string.verify_explain_unknown_site
    ChainStatus.BAD_SIGNATURE -> R.string.verify_explain_bad_signature
    ChainStatus.BROKEN_LINK -> R.string.verify_explain_broken_link
    ChainStatus.MALFORMED -> R.string.verify_explain_malformed
    ChainStatus.SEQUENCE_GAP -> R.string.verify_explain_sequence_gap
}
