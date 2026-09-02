package org.jaagruk.safety.ui.cert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.R
import org.jaagruk.safety.data.db.CertificateEntity
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.QrImage
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.catalogString
import javax.inject.Inject

/**
 * A worker's certificates, and the QR that proves them.
 *
 * The QR is the certificate. It carries the signed attestation itself — site, sequence, module, score,
 * median latency, outcome flags, the previous record hash and an Ed25519 signature — not a lookup key. That
 * is what lets an inspector verify it at a mine gate with no signal, on a handset that has never spoken to
 * this one, and it is why the worker's plaintext id is nowhere in it: only a SHA-256 hash, so a dropped card
 * leaks nothing.
 */
@Composable
fun CertificatesScreen(
    workerId: String,
    onBack: () -> Unit,
    viewModel: CertificatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(workerId) { viewModel.load(workerId) }

    val selected = state.selected
    if (selected != null) {
        CertificateDetail(
            certificate = selected,
            workerName = state.workerName,
            onBack = viewModel::clearSelection,
        )
        return
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
                text = stringResource(R.string.certificates_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(state.workerName, style = MaterialTheme.typography.bodyMedium)
        }

        if (state.certificates.isEmpty()) {
            item {
                StatusBanner(
                    text = stringResource(R.string.certificates_none),
                    tone = BannerTone.INFO,
                    pictogramDescription = stringResource(R.string.cd_info),
                )
            }
        }

        items(state.certificates, key = { it.certId }) { certificate ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = catalogString(
                                ModuleCatalog.byCode(certificate.moduleCode)?.titleKey
                                    ?: "module_fire_title",
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.certificates_seq_and_score,
                                certificate.seq,
                                certificate.scorePermille / 10,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        FlagRow(certificate.outcomeFlags)
                        if (!certificate.uploaded) {
                            // Not a failure. The certificate is already signed and verifiable offline; this
                            // is only a note about delivery.
                            Text(
                                text = stringResource(R.string.certificates_not_uploaded),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    GloveButton(
                        text = stringResource(R.string.action_show_qr),
                        onClick = { viewModel.select(certificate) },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            GloveOutlinedButton(
                text = stringResource(R.string.action_back),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FlagRow(flags: Int) {
    // A stored row with a reserved bit set would come from a newer format version than this build knows.
    // Falling back to no flags keeps the certificate visible and scannable rather than crashing the list.
    val outcome = runCatching { OutcomeFlags.fromBits(flags) }.getOrDefault(OutcomeFlags.NONE)
    val labels = buildList {
        if (outcome.has(OutcomeFlags.BUDDY_DRILL)) add(R.string.flag_buddy)
        if (outcome.has(OutcomeFlags.SITE_SCANNED_AR)) add(R.string.flag_site_scanned)
        if (outcome.has(OutcomeFlags.REFRESHER)) add(R.string.flag_refresher)
        if (outcome.has(OutcomeFlags.HESITATION)) add(R.string.flag_hesitation)
        if (outcome.has(OutcomeFlags.ASSISTED_MODE)) add(R.string.flag_assisted)
    }
    if (labels.isEmpty()) return

    // Resolved in a `map` rather than inside `joinToString`: `joinToString`'s transform is not inline, so a
    // composable call there is a compile error.
    val resolved = labels.map { stringResource(it) }

    Text(
        text = resolved.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun CertificateDetail(
    certificate: CertificateEntity,
    workerName: String,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = catalogString(
                    ModuleCatalog.byCode(certificate.moduleCode)?.titleKey ?: "module_fire_title",
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(workerName, style = MaterialTheme.typography.bodyMedium)
        }

        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrImage(
                    text = certificate.qrText,
                    contentDescription = stringResource(R.string.cd_certificate_qr),
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.certificates_offline_note),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                DetailRow(
                    label = stringResource(R.string.certificates_site),
                    value = certificate.siteId,
                )
                DetailRow(
                    label = stringResource(R.string.certificates_sequence),
                    value = certificate.seq.toString(),
                )
                DetailRow(
                    label = stringResource(R.string.certificates_score),
                    value = "${certificate.scorePermille / 10}%",
                )
                DetailRow(
                    label = stringResource(R.string.certificates_key_epoch),
                    value = certificate.keyEpoch.toString(),
                )
                // The record hash is the anchor an inspector can read out over a radio to compare against the
                // site ledger, so it is shown in full rather than truncated.
                DetailRow(
                    label = stringResource(R.string.certificates_record_hash),
                    value = certificate.recordHashHex,
                    monospace = true,
                )
            }
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.certificates_text_form),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                // Shown so a certificate can still be checked if the QR is damaged or the camera fails. It
                // is the same 216 characters the scanner reads.
                Text(
                    text = certificate.qrText,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
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

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}

@HiltViewModel
class CertificatesViewModel @Inject constructor(
    private val certificates: CertificateRepository,
    private val workers: WorkerRepository,
) : ViewModel() {

    data class State(
        val workerName: String = "",
        val certificates: List<CertificateEntity> = emptyList(),
        val selected: CertificateEntity? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(workerId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                workerName = workers.find(workerId)?.fullName.orEmpty(),
            )
        }
        viewModelScope.launch {
            certificates.observeForWorker(workerId).collect { rows ->
                _state.value = _state.value.copy(certificates = rows)
            }
        }
    }

    fun select(certificate: CertificateEntity) {
        _state.value = _state.value.copy(selected = certificate)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = null)
    }
}
