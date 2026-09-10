package org.jaagruk.safety.ui.signin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.util.Hex
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.R
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.SiteEntity
import org.jaagruk.safety.data.db.WorkerEntity
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.sync.SyncStatusProvider
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
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val workers: WorkerRepository,
    private val deviceProfile: DeviceProfile,
    private val keyStore: SiteKeyStore,
    private val sites: SiteRepository,
    private val clock: WallClock,
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
            message = null,
            step = SignInStep.PickWorker(filter(_state.value.allWorkers, _state.value.query)),
        )
    }

    /**
     * Provisions a sample site and roster so the app can be used immediately.
     *
     * This exists because a fresh handset is otherwise a locked door: nothing seeds the database, so the
     * worker picker is empty, and reaching training means enrolling a site key and a worker through
     * Supervisor tools first. That is the correct flow for a real posting and the wrong first experience
     * for anybody evaluating the app, who has thirty seconds of patience and no site to enrol.
     *
     * It is deliberately explicit about being sample data, and deliberately not silent: a real site
     * enrols real worker numbers from the cards, and a demo roster in a live deployment would put
     * fictitious names on real certificates.
     *
     * The PIN is pre-set here, which is the one place this diverges from the real flow on purpose — the
     * normal path has the worker choose their own so the supervisor never learns it.
     */
    fun setUpDemoSite() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                if (!keyStore.hasSiteKey()) {
                    val pair = keyStore.generateSiteKey(DEMO_SITE_ID)
                    sites.upsert(
                        SiteEntity(
                            siteId = DEMO_SITE_ID,
                            name = DEMO_SITE_NAME,
                            district = DEMO_DISTRICT,
                            sector = DEMO_SECTOR,
                            publicKeyHex = Hex.encode(pair.publicKey),
                            createdAtSec = clock.epochSeconds(),
                        ),
                    )
                    sites.recordSiteKey(DEMO_SITE_ID, Hex.encode(pair.publicKey), 1)
                    keyStore.ensureDeviceAttestationKey()
                }
                deviceProfile.setActiveSiteId(DEMO_SITE_ID)

                for ((workerId, fullName, language) in DEMO_WORKERS) {
                    val result = workers.register(
                        workerId = workerId,
                        siteId = DEMO_SITE_ID,
                        fullName = fullName,
                        preferredLanguage = language,
                        pictogramMode = false,
                    )
                    if (result is WorkerRepository.RegisterResult.Registered) {
                        workers.setPin(workerId, DEMO_PIN)
                    }
                }

                _state.value = _state.value.copy(siteId = DEMO_SITE_ID)
                refreshRoster()
                _state.value = _state.value.copy(
                    busy = false,
                    message = UiMessage.success(R.string.signin_demo_ready, DEMO_PIN),
                )
            } catch (e: Exception) {
                // The realistic failure is SiteKeyStore.KeyStoreUnavailable on a device whose keystore
                // was reset. Reported rather than swallowed, because without a key the certificates the
                // demo is meant to show cannot be issued.
                Log.w(TAG, "demo setup failed", e)
                _state.value = _state.value.copy(
                    busy = false,
                    message = UiMessage.error(R.string.signin_demo_failed),
                )
            }
        }
    }

    fun setPin(pin: String) {
        // Digits only, capped. Filtering at the source means the validator never has to reject something
        // the keyboard should not have offered.
        _state.value = _state.value.copy(pin = pin.filter(Char::isDigit).take(MAX_PIN_INPUT))
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

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun fail(message: UiMessage) {
        _state.value = _state.value.copy(busy = false, pin = "", message = message)
    }

    companion object {
        private const val TAG = "SignInViewModel"
        private const val ROSTER_PAGE = 60
        private const val MAX_PIN_INPUT = 8

        // --- demo provisioning ------------------------------------------------
        // A real district code and worker-number shape, because the ids have to satisfy the same
        // validation the server enforces. Anything looser would demo a path that cannot sync.

        const val DEMO_SITE_ID = "JH-DHN-001"
        const val DEMO_SITE_NAME = "Demo Colliery (sample data)"
        private const val DEMO_DISTRICT = "Dhanbad"
        private const val DEMO_SECTOR = "coal"

        /** Shared across the sample roster and shown on screen, since nobody can be asked for it. */
        const val DEMO_PIN = "2846"

        val DEMO_WORKERS: List<Triple<String, String, String>> = listOf(
            Triple("JH-DHN-001-W00001", "Budhan Manjhi", LocaleManager.HINDI),
            Triple("JH-DHN-001-W00002", "Sita Kumari", LocaleManager.HINDI),
            Triple("JH-DHN-001-W00003", "Rupai Hembram", LocaleManager.SANTALI),
        )
    }
}
