package org.jaagruk.safety.ui.signin

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jaagruk.safety.R
import org.jaagruk.safety.ui.LocaleManager
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatusBanner

/**
 * Shift sign-in.
 *
 * The flow is built around what is actually true at a mine gate: no network, a shared handset, and a worker
 * who may not read. So the roster is local, authentication is a local PIN, and the language switcher is on
 * this screen — the first thing a worker touches — rather than buried in settings, because the handset was
 * probably left in someone else's language.
 *
 * Supervisor sign-in is a separate, deliberately less prominent path. It authenticates against the server
 * because it grants the ability to enrol keys and upload, and that is a decision the server has to make.
 */
@Composable
fun SignInScreen(
    onWorkerSignedIn: (String) -> Unit,
    onSupervisorTools: () -> Unit,
    onVerify: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A LazyColumn rather than a Column, so the screen scrolls. In Santali, on a 5-inch handset, with a
    // no-site banner and a queued-records notice both showing, this content is taller than the display —
    // and a fixed Column silently clips the sign-in button off the bottom with no way to reach it.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.signin_tagline),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(12.dp))
            LanguageRow(
                current = state.languageTag,
                onSelect = viewModel::setLanguage,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (state.siteId == null) {
            item {
                StatusBanner(
                    text = stringResource(R.string.signin_no_site),
                    tone = BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_warning),
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        if (state.pendingSyncCount > 0) {
            // Framed as a fact, not a failure. The records are signed and safe; this is a delivery note.
            item {
                StatusBanner(
                    text = stringResource(R.string.signin_queued_records, state.pendingSyncCount),
                    tone = BannerTone.INFO,
                    pictogramDescription = stringResource(R.string.cd_info),
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        if (state.message != null) {
            item {
                MessageBanner(state.message, stringResource(R.string.cd_info))
                Spacer(Modifier.height(12.dp))
            }
        }

        when (val step = state.step) {
            is SignInStep.PickWorker -> workerPicker(
                workers = step.workers,
                query = state.query,
                onQueryChange = viewModel::setQuery,
                onPick = viewModel::selectWorker,
            )

            is SignInStep.EnterPin -> item {
                PinEntry(
                    workerName = step.workerName,
                    pin = state.pin,
                    settingNewPin = step.settingNewPin,
                    lockedSeconds = step.lockedSecondsRemaining,
                    onPinChange = viewModel::setPin,
                    onSubmit = { viewModel.submitPin(onWorkerSignedIn) },
                    onCancel = viewModel::backToPicker,
                )
            }
        }

        // Offered only while the handset has nobody on it. A fresh install is otherwise a locked door:
        // reaching training means enrolling a site key and a worker through Supervisor tools first, which
        // is right for a real posting and hopeless as a first experience. Labelled as sample data so it
        // can never be mistaken for a real roster.
        if (state.step is SignInStep.PickWorker && state.allWorkers.isEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                SectionCard {
                    Text(
                        text = stringResource(R.string.signin_demo_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.signin_demo_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    GloveButton(
                        text = stringResource(R.string.signin_demo_action),
                        onClick = viewModel::setUpDemoSite,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Verification needs no sign-in at all. An inspector arriving at a gate should be able to
                // scan a worker's card without an account on that handset, and the offline verifier is
                // the authoritative check either way.
                GloveOutlinedButton(
                    text = stringResource(R.string.action_verify_certificate),
                    onClick = onVerify,
                    modifier = Modifier.weight(1f),
                )
                // Straight to the local tools. Everything a supervisor does on device — generating the
                // site key, enrolling workers, auditing the chain, handing records to another handset —
                // needs no server. Only uploading does, and that login now lives inside that screen.
                // Routing this through a network login was what made a fresh offline handset unusable.
                GloveOutlinedButton(
                    text = stringResource(R.string.action_supervisor),
                    onClick = onSupervisorTools,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LanguageRow(current: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocaleManager.supported.forEach { tag ->
            if (tag == current) {
                GloveButton(
                    text = LocaleManager.endonym(tag),
                    onClick = { onSelect(tag) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                GloveOutlinedButton(
                    text = LocaleManager.endonym(tag),
                    onClick = { onSelect(tag) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The roster, emitted into the parent list rather than into a nested one.
 *
 * A `LazyColumn` inside a scrolling parent is a crash, not a layout quirk — the child is measured with
 * an unbounded height and Compose throws. Contributing items to the one list that already exists is
 * also what lets a long roster scroll together with the search field above it.
 */
private fun LazyListScope.workerPicker(
    workers: List<SignInViewModel.WorkerRow>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    item {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.signin_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }

    if (workers.isEmpty()) {
        item {
            StatusBanner(
                text = stringResource(R.string.signin_no_workers),
                tone = BannerTone.INFO,
                pictogramDescription = stringResource(R.string.cd_info),
            )
        }
        return
    }

    items(workers, key = { it.workerId }) { worker ->
        SectionCard {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(worker.fullName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = worker.workerId,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (!worker.hasPin) {
                        Text(
                            text = stringResource(R.string.signin_pin_not_set),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                GloveButton(
                    text = stringResource(R.string.action_sign_in),
                    onClick = { onPick(worker.workerId) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PinEntry(
    workerName: String,
    pin: String,
    settingNewPin: Boolean,
    lockedSeconds: Long?,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard {
        Text(workerName, style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(
                if (settingNewPin) R.string.signin_choose_pin else R.string.signin_enter_pin,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        if (lockedSeconds != null && lockedSeconds > 0) {
            StatusBanner(
                text = stringResource(R.string.signin_locked_out, lockedSeconds),
                tone = BannerTone.ERROR,
                pictogramDescription = stringResource(R.string.cd_stop),
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = pin,
            onValueChange = onPinChange,
            label = { Text(stringResource(R.string.signin_pin_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            enabled = lockedSeconds == null || lockedSeconds <= 0,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GloveOutlinedButton(
                text = stringResource(R.string.action_back),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            GloveButton(
                text = stringResource(
                    if (settingNewPin) R.string.action_save_pin else R.string.action_sign_in,
                ),
                onClick = onSubmit,
                enabled = pin.isNotBlank() && (lockedSeconds == null || lockedSeconds <= 0),
                modifier = Modifier.weight(1f),
            )
        }
    }
}


