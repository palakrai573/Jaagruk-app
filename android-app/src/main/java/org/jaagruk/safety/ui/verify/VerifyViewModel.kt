package org.jaagruk.safety.ui.verify

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.cert.QrCodec
import org.jaagruk.core.crypto.ChainStatus
import org.jaagruk.core.util.Hex
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.sync.api.JaagrukApi
import org.jaagruk.safety.sync.api.VerifyRequest
import javax.inject.Inject

/**
 * Runs a verification and holds its verdict.
 *
 * All of it is local. [CertificateRepository.verifyQr] decodes the payload, checks the Ed25519 signature
 * against the site's stored public key, and cross-checks the chain link against whatever records this handset
 * holds. No network, no account, no server round trip — which is the whole point, because the place a
 * certificate needs checking is a mine gate with no signal.
 */
@HiltViewModel
class VerifyViewModel @Inject constructor(
    private val certificates: CertificateRepository,
    private val api: JaagrukApi,
) : ViewModel() {

    data class Verdict(
        val status: ChainStatus,
        val siteId: String? = null,
        val seq: Long? = null,
        val moduleCode: Int? = null,
        val scorePermille: Int? = null,
        val workerIdHashHex: String? = null,
        /**
         * Issuance time, to the minute.
         *
         * Minutes rather than seconds because that is what the signed payload carries: a `u32` of minutes
         * covers until 10136 in four bytes, and second-level precision would have cost two more bytes of an
         * already tight QR budget for information nobody needs.
         */
        val issuedAtEpochMin: Long? = null,
    )

    /**
     * What only the server knows, added to a verdict the device already reached on its own.
     *
     * Everything here is *additional*: the worker's readiness across every handset that has synced,
     * their name, and whether the statutory clock has run out. None of it can change the verdict,
     * because the verdict is a signature check over bytes the inspector is holding and the server has
     * no better claim on that than the phone does.
     */
    data class ServerInsight(
        val workerFullName: String? = null,
        val readinessPermille: Int? = null,
        val readinessBand: String? = null,
        val statutoryValid: Boolean? = null,
        /** The server's own status, kept only when it differs. Reported, never obeyed. */
        val disagreesWithStatus: String? = null,
    )

    /** How the optional online cross-check is getting on. Never gates the verdict. */
    enum class CrossCheck { NOT_ATTEMPTED, CHECKING, UNAVAILABLE, RECEIVED }

    data class State(
        val scanning: Boolean = true,
        val manualEntry: String = "",
        val verdict: Verdict? = null,
        val reasons: List<String> = emptyList(),
        val candidateWorkerId: String = "",
        val identityMatches: Boolean? = null,
        val lastVerifiedQr: String? = null,
        val crossCheck: CrossCheck = CrossCheck.NOT_ATTEMPTED,
        val insight: ServerInsight? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setManualEntry(value: String) {
        _state.value = _state.value.copy(manualEntry = value.trim())
    }

    fun toggleScanning() {
        _state.value = _state.value.copy(scanning = !_state.value.scanning)
    }

    /**
     * Upper-cased, because the hash in the certificate is over the canonical id.
     *
     * Worker ids are upper-case everywhere they are stored — the server enforces it and enrolment
     * normalises to it — and [AttestationCodec.workerIdHash] hashes the exact bytes it is given. An
     * inspector who typed the id off the card in lower case would otherwise get "does not match" for
     * a perfectly genuine certificate, which is the worst possible failure for this screen: it
     * accuses the worker rather than the keyboard.
     */
    fun setCandidateWorkerId(value: String) {
        _state.value = _state.value.copy(
            candidateWorkerId = value.trim().uppercase(),
            identityMatches = null,
        )
    }

    fun verifyManualEntry() = verify(_state.value.manualEntry)

    /**
     * Verifies a payload.
     *
     * Skips a repeat of the payload already on screen. An inspector holding a card in front of the camera
     * produces the same string thirty times a second, and re-running the verdict each time would make the
     * result flicker and would waste an Ed25519 check per frame.
     */
    fun verify(qrText: String) {
        if (qrText.isBlank()) return
        if (qrText == _state.value.lastVerifiedQr) return

        viewModelScope.launch {
            val result = certificates.verifyQr(qrText)
            val attestation = result.attestation

            _state.value = _state.value.copy(
                lastVerifiedQr = qrText,
                // Scanning stops once there is a verdict, so the camera is not left running while the
                // inspector reads it. Restarting is one tap.
                scanning = false,
                verdict = Verdict(
                    status = result.status,
                    siteId = attestation?.siteId,
                    seq = attestation?.seq,
                    moduleCode = attestation?.moduleCode,
                    scorePermille = attestation?.scorePermille,
                    workerIdHashHex = attestation?.workerIdHash?.let(Hex::encode),
                    issuedAtEpochMin = attestation?.issuedAtEpochMin,
                ),
                // Machine-readable codes are turned into readable detail here rather than shown raw. An
                // inspector should never see an enum name.
                reasons = result.reasons.map { it.detail },
                identityMatches = null,
                candidateWorkerId = "",
                crossCheck = CrossCheck.CHECKING,
                insight = null,
            )

            // Started only after the verdict is already on screen, and in a child coroutine so a slow
            // uplink cannot delay it. An inspector at a gate gets their answer from the phone.
            crossCheck(qrText, result.status)
        }
    }

    /**
     * Asks the server what it knows about this certificate, if it can be reached.
     *
     * Strictly additive. It cannot change [State.verdict], [State.reasons] or
     * [State.identityMatches] — those come from an Ed25519 check over the bytes in the inspector's
     * hand, and a server that disagreed would either be looking at a different record or be wrong.
     * A disagreement is surfaced as a note to follow up, not as a correction.
     *
     * Bounded by its own timeout rather than the OkHttp call timeout, which is two minutes and tuned
     * for uploading a batch over a bad uplink. Nobody stands at a gate for two minutes.
     */
    private suspend fun crossCheck(qrText: String, offlineStatus: ChainStatus) {
        val response = try {
            withTimeoutOrNull(CROSS_CHECK_TIMEOUT_MS) {
                api.verifyCertificate(VerifyRequest(qrText = qrText))
            }
        } catch (e: Exception) {
            // No signal is the ordinary case here, not an error worth alarming language.
            Log.i(TAG, "online cross-check unavailable: ${e.message}")
            null
        }

        // The payload may have changed under us while the request was in flight — a camera hands over
        // a new one constantly. Anything that arrives for a superseded scan is dropped.
        if (_state.value.lastVerifiedQr != qrText) return

        val body = response?.body()
        if (response == null || !response.isSuccessful || body == null) {
            _state.value = _state.value.copy(crossCheck = CrossCheck.UNAVAILABLE)
            return
        }

        _state.value = _state.value.copy(
            crossCheck = CrossCheck.RECEIVED,
            insight = ServerInsight(
                workerFullName = body.workerFullName,
                readinessPermille = body.readinessPermille,
                readinessBand = body.readinessBand,
                statutoryValid = body.statutoryValid,
                disagreesWithStatus = body.status.takeIf { it != offlineStatus.name },
            ),
        )
    }

    /**
     * Confirms the certificate belongs to the person holding it.
     *
     * The QR carries only a SHA-256 of the worker id, never the id itself, so a dropped card identifies
     * nobody. The check hashes what the inspector types and compares in constant time, which also stops the
     * handset being used as an oracle to enumerate worker ids by timing.
     */
    fun checkIdentity() {
        val verdict = _state.value.verdict ?: return
        val candidate = _state.value.candidateWorkerId
        if (candidate.isBlank() || verdict.workerIdHashHex == null) return

        viewModelScope.launch {
            val signed = QrCodec.decodeOrNull(_state.value.lastVerifiedQr.orEmpty())
            val matches = signed != null &&
                AttestationCodec.matchesWorkerId(signed.attestation, candidate)
            _state.value = _state.value.copy(identityMatches = matches)
        }
    }

    fun resetScanning() {
        _state.value = _state.value.copy(
            scanning = true,
            verdict = null,
            reasons = emptyList(),
            lastVerifiedQr = null,
            identityMatches = null,
            candidateWorkerId = "",
            crossCheck = CrossCheck.NOT_ATTEMPTED,
            insight = null,
        )
    }

    private companion object {
        const val TAG = "VerifyViewModel"

        /**
         * How long to wait for the server before giving up on the extra detail.
         *
         * Short on purpose. The OkHttp call timeout is two minutes, which is right for pushing a batch
         * of records over a mine-site uplink and wrong for a person standing at a gate holding a card.
         */
        const val CROSS_CHECK_TIMEOUT_MS = 4_000L
    }
}
