package org.jaagruk.safety.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.cert.QrCodec
import org.jaagruk.core.crypto.ChainStatus
import org.jaagruk.core.util.Hex
import org.jaagruk.safety.data.repo.CertificateRepository
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

    data class State(
        val scanning: Boolean = true,
        val manualEntry: String = "",
        val verdict: Verdict? = null,
        val reasons: List<String> = emptyList(),
        val candidateWorkerId: String = "",
        val identityMatches: Boolean? = null,
        val lastVerifiedQr: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setManualEntry(value: String) {
        _state.value = _state.value.copy(manualEntry = value.trim())
    }

    fun toggleScanning() {
        _state.value = _state.value.copy(scanning = !_state.value.scanning)
    }

    fun setCandidateWorkerId(value: String) {
        _state.value = _state.value.copy(candidateWorkerId = value.trim(), identityMatches = null)
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
            )
        }
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
        )
    }
}
