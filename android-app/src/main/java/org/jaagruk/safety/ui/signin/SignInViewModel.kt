package org.jaagruk.safety.ui.signin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.safety.R
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.WorkerEntity
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.sync.SyncStatusProvider
import org.jaagruk.safety.sync.api.JaagrukApi
import org.jaagruk.safety.sync.api.LoginRequest
import org.jaagruk.safety.sync.api.SessionStore
import org.jaagruk.safety.ui.LocaleManager
import org.jaagruk.safety.ui.components.UiMessage
import javax.inject.Inject

/** Where the sign-in flow currently is. */
sealed interface SignInStep {

    data class PickWorker(val workers: List<SignInViewModel.WorkerRow>) : SignInStep

    data class EnterPin(
        val workerId: String,
        val workerName: String,
        /** True when this worker has no PIN yet and is choosing one. */
        val settingNewPin: Boolean,
        val lockedSecondsRemaining: Long? = null,
    ) : SignInStep

    data object SupervisorLogin : SignInStep
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val workers: WorkerRepository,
    private val deviceProfile: DeviceProfile,
    private val api: JaagrukApi,
    private val session: SessionStore,
    private val syncScheduler: SyncScheduler,
    syncStatus: SyncStatusProvider,
) : ViewModel() {

    data class WorkerRow(
        val workerId: String,
        val fullName: String,
        val hasPin: Boolean,
    )

    data class State(
        val step: SignInStep = SignInStep.PickWorker(emptyList()),
        val allWorkers: List<WorkerRow> = emptyList(),
        val query: String = "",
        val pin: String = "",
        val username: String = "",
        val password: String = "",
        val siteId: String? = null,
        val languageTag: String = LocaleManager.ENGLISH,
        val pendingSyncCount: Int = 0,
        val busy: Boolean = false,
        val message: UiMessage? = null,
    )

    private val _state = MutableStateFlow(State(languageTag = LocaleManager.current()))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(siteId = deviceProfile.activeSiteId())
            refreshRoster()
        }
        viewModelScope.launch {
            syncStatus.status.collect { status ->
                _state.value = _state.value.copy(pendingSyncCount = status.pending)
            }
        }
    }

    private suspend fun refreshRoster() {
        val rows = workers.all()
            .filter { it.active }
            .map { WorkerRow(it.workerId, it.fullName, it.pinHash?.isNotBlank() == true) }
            .sortedBy { it.fullName }

        _state.value = _state.value.copy(
            allWorkers = rows,
            step = SignInStep.PickWorker(filter(rows, _state.value.query)),
        )
    }

    private fun filter(rows: List<WorkerRow>, query: String): List<WorkerRow> {
        if (query.isBlank()) return rows.take(ROSTER_PAGE)
        val needle = query.trim().lowercase()
        return rows.filter {
            it.fullName.lowercase().contains(needle) || it.workerId.lowercase().contains(needle)
        }.take(ROSTER_PAGE)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(
            query = query,
            step = SignInStep.PickWorker(filter(_state.value.allWorkers, query)),
        )
    }

    fun setLanguage(tag: String) {
        LocaleManager.apply(tag)
        _state.value = _state.value.copy(languageTag = tag)
    }

    fun selectWorker(workerId: String) {
        viewModelScope.launch {
            val worker = workers.find(workerId) ?: return@launch
            _state.value = _state.value.copy(
                pin = "",
                message = null,
                step = SignInStep.EnterPin(
                    workerId = worker.workerId,
                    workerName = worker.fullName,
                    settingNewPin = worker.pinHash.isNullOrBlank(),
                    lockedSecondsRemaining = workers.lockoutRemainingSeconds(workerId),
                ),
            )
        }
    }

    fun backToPicker() {
        _state.value = _state.value.copy(
            pin = "",
            password = "",
            message = null,
            step = SignInStep.PickWorker(filter(_state.value.allWorkers, _state.value.query)),
        )
    }

    fun openSupervisorLogin() {
        _state.value = _state.value.copy(message = null, step = SignInStep.SupervisorLogin)
    }

    fun setPin(pin: String) {
        // Digits only, capped. Filtering at the source means the validator never has to reject something
        // the keyboard should not have offered.
        _state.value = _state.value.copy(pin = pin.filter(Char::isDigit).take(MAX_PIN_INPUT))
    }

    fun setUsername(value: String) {
        _state.value = _state.value.copy(username = value.trim())
    }

    fun setPassword(value: String) {
        _state.value = _state.value.copy(password = value)
    }

    /**
     * Either sets a first PIN or checks an existing one.
     *
     * Both paths run entirely on device. A worker signing in at the start of a shift, 400 metres
     * underground, cannot wait on a server — and making them would mean the app is unusable exactly where
     * it is needed most.
     */
    fun submitPin(onSignedIn: (String) -> Unit) {
        val step = _state.value.step as? SignInStep.EnterPin ?: return
        val pin = _state.value.pin

        viewModelScope.launch {
            if (step.settingNewPin) {
                setFirstPin(step, pin, onSignedIn)
                return@launch
            }

            when (val result = workers.authenticate(step.workerId, pin)) {
                is PinAuthenticator.Result.Success -> completeSignIn(step.workerId, onSignedIn)

                is PinAuthenticator.Result.WrongPin ->
                    fail(UiMessage.error(R.string.signin_wrong_pin, result.attemptsRemaining))

                is PinAuthenticator.Result.LockedOut -> {
                    _state.value = _state.value.copy(
                        pin = "",
                        message = UiMessage.error(
                            R.string.signin_locked_out,
                            result.secondsRemaining,
                        ),
                        step = step.copy(lockedSecondsRemaining = result.secondsRemaining),
                    )
                }

                // The row lost its PIN — a supervisor reset it. Sending the worker straight into choosing a
                // new one is the whole point of a reset.
                PinAuthenticator.Result.NoPinSet -> {
                    _state.value = _state.value.copy(step = step.copy(settingNewPin = true))
                }

                PinAuthenticator.Result.UnknownWorker ->
                    fail(UiMessage.error(R.string.signin_unknown_worker))
            }
        }
    }

    private suspend fun setFirstPin(
        step: SignInStep.EnterPin,
        pin: String,
        onSignedIn: (String) -> Unit,
    ) {
        when (val validity = workers.validatePin(pin)) {
            is PinAuthenticator.PinValidity.Acceptable ->
                if (workers.setPin(step.workerId, pin)) {
                    completeSignIn(step.workerId, onSignedIn)
                } else {
                    fail(UiMessage.error(R.string.signin_pin_not_saved))
                }

            is PinAuthenticator.PinValidity.TooShort ->
                fail(UiMessage.error(R.string.signin_pin_too_short, validity.minimum))

            is PinAuthenticator.PinValidity.TooLong ->
                fail(UiMessage.error(R.string.signin_pin_too_long, validity.maximum))

            PinAuthenticator.PinValidity.NotDigits ->
                fail(UiMessage.error(R.string.signin_pin_not_digits))

            // Refused with a reason rather than silently accepted. On a shared handset a PIN of 1111 is one
            // guess away from a certificate being issued in somebody else's name.
            PinAuthenticator.PinValidity.TooGuessable ->
                fail(UiMessage.error(R.string.signin_pin_too_guessable))
        }
    }

    private suspend fun completeSignIn(workerId: String, onSignedIn: (String) -> Unit) {
        deviceProfile.setActiveWorkerId(workerId)
        applyWorkerLanguage(workerId)
        _state.value = _state.value.copy(pin = "", message = null)
        onSignedIn(workerId)
    }

    /**
     * Switches the app into the worker's own language on sign-in.
     *
     * On a shared handset this is the difference between a usable app and an unusable one: the previous
     * worker's language is not a sensible default for the next one.
     */
    private suspend fun applyWorkerLanguage(workerId: String) {
        val worker: WorkerEntity = workers.find(workerId) ?: return
        val tag = worker.preferredLanguage.takeIf { it in LocaleManager.supported } ?: return
        if (tag != LocaleManager.current()) {
            LocaleManager.apply(tag)
            _state.value = _state.value.copy(languageTag = tag)
        }
    }

    /**
     * Signs a supervisor in against the server.
     *
     * The one flow in the app that genuinely needs connectivity, and it says so: enrolling a site signing
     * key and authorising uploads are decisions the server has to make. Everything a worker does still works
     * with the radio off.
     */
    fun submitSupervisorLogin(onSignedIn: () -> Unit) {
        val current = _state.value
        _state.value = current.copy(busy = true, message = null)

        viewModelScope.launch {
            try {
                val response = api.login(LoginRequest(current.username, current.password))
                val body = response.body()
                when {
                    response.isSuccessful && body != null -> {
                        session.save(body)
                        body.siteId?.let { deviceProfile.setActiveSiteId(it) }
                        // Records queued while nobody was signed in can go out now.
                        syncScheduler.requestSyncNow()
                        _state.value = _state.value.copy(busy = false, password = "", message = null)
                        onSignedIn()
                    }

                    response.isSuccessful -> fail(UiMessage.error(R.string.signin_empty_session))

                    response.code() == HTTP_UNAUTHORIZED ->
                        fail(UiMessage.error(R.string.signin_bad_credentials))

                    response.code() == HTTP_TOO_MANY_REQUESTS ->
                        fail(UiMessage.error(R.string.signin_rate_limited))

                    else -> fail(UiMessage.error(R.string.signin_failed_code, response.code()))
                }
            } catch (e: Exception) {
                // No connectivity is the ordinary case here and does not deserve alarming language.
                Log.i(TAG, "supervisor sign-in could not reach the server", e)
                fail(UiMessage.warning(R.string.signin_offline))
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun fail(message: UiMessage) {
        _state.value = _state.value.copy(busy = false, pin = "", message = message)
    }

    private companion object {
        const val TAG = "SignInViewModel"
        const val ROSTER_PAGE = 60
        const val MAX_PIN_INPUT = 8
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
