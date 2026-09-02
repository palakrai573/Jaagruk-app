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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
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

        if (state.siteId == null) {
            StatusBanner(
                text = stringResource(R.string.signin_no_site),
                tone = BannerTone.WARNING,
                pictogramDescription = stringResource(R.string.cd_warning),
            )
            Spacer(Modifier.height(12.dp))
        }

        if (state.pendingSyncCount > 0) {
            // Framed as a fact, not a failure. The records are signed and safe; this is a delivery note.
            StatusBanner(
                text = stringResource(R.string.signin_queued_records, state.pendingSyncCount),
                tone = BannerTone.INFO,
                pictogramDescription = stringResource(R.string.cd_info),
            )
            Spacer(Modifier.height(12.dp))
        }

        if (state.message != null) {
            MessageBanner(state.message, stringResource(R.string.cd_info))
            Spacer(Modifier.height(12.dp))
        }

        when (val step = state.step) {
            is SignInStep.PickWorker -> WorkerPicker(
                workers = step.workers,
                query = state.query,
                onQueryChange = viewModel::setQuery,
                onPick = viewModel::selectWorker,
            )

            is SignInStep.EnterPin -> PinEntry(
                workerName = step.workerName,
                pin = state.pin,
                settingNewPin = step.settingNewPin,
                lockedSeconds = step.lockedSecondsRemaining,
                onPinChange = viewModel::setPin,
                onSubmit = { viewModel.submitPin(onWorkerSignedIn) },
                onCancel = viewModel::backToPicker,
            )

            is SignInStep.SupervisorLogin -> SupervisorLogin(
                username = state.username,
                password = state.password,
                busy = state.busy,
                onUsernameChange = viewModel::setUsername,
                onPasswordChange = viewModel::setPassword,
                onSubmit = { viewModel.submitSupervisorLogin(onSupervisorTools) },
                onCancel = viewModel::backToPicker,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Verification needs no sign-in at all. An inspector arriving at a gate should be able to scan
            // a worker's card without an account on that handset, and the offline verifier is the
            // authoritative check either way.
            GloveOutlinedButton(
                text = stringResource(R.string.action_verify_certificate),
                onClick = onVerify,
                modifier = Modifier.weight(1f),
            )
            GloveOutlinedButton(
                text = stringResource(R.string.action_supervisor),
                onClick = viewModel::openSupervisorLogin,
                modifier = Modifier.weight(1f),
            )
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

@Composable
private fun WorkerPicker(
    workers: List<SignInViewModel.WorkerRow>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.signin_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (workers.isEmpty()) {
            StatusBanner(
                text = stringResource(R.string.signin_no_workers),
                tone = BannerTone.INFO,
                pictogramDescription = stringResource(R.string.cd_info),
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
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

@Composable
private fun SupervisorLogin(
    username: String,
    password: String,
    busy: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.signin_supervisor_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.signin_supervisor_explainer),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.signin_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.signin_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
            ),
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
                text = stringResource(R.string.action_sign_in),
                onClick = onSubmit,
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
