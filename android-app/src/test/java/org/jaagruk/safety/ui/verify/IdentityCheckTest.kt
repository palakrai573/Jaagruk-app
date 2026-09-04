package org.jaagruk.safety.ui.verify

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.util.FixedWallClock
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The identity half of verification: does this certificate belong to the person holding it?
 *
 * The QR carries only a SHA-256 of the worker id, so the check hashes what the inspector types and
 * compares. That makes the *exact bytes* of the id load-bearing, and it is why enrolment normalises
 * to upper case and why this screen has to as well. A case mismatch here would tell an inspector
 * that a genuine certificate does not belong to the worker in front of them — the screen would be
 * accusing the worker of something the keyboard did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IdentityCheckTest {

    private lateinit var database: JaagrukDatabase
    private lateinit var workers: WorkerRepository
    private lateinit var viewModel: VerifyViewModel

    private val canonicalId = "JH-DHN-001-W00042"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = TestDatabase.create()
        val clock = FixedWallClock(1_760_000_000_000L)
        workers = WorkerRepository(database, PinAuthenticator(database.workerDao()), clock)
        viewModel = VerifyViewModel(
            certificates = CertificateRepository(
                database,
                mockk<SiteKeyStore>(relaxed = true),
                clock,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the id an inspector types is normalised to the canonical form`() {
        viewModel.setCandidateWorkerId("  jh-dhn-001-w00042  ")

        assertThat(viewModel.state.value.candidateWorkerId).isEqualTo(canonicalId)
    }

    @Test
    fun `a lower-case id hashes to the same value as the canonical one after normalisation`() {
        // The bug this pins: matchesWorkerId hashes the exact bytes it is handed, so an un-normalised
        // lower-case entry produces a different hash and a false "does not match".
        val typed = "jh-dhn-001-w00042"
        assertThat(AttestationCodec.workerIdHash(typed))
            .isNotEqualTo(AttestationCodec.workerIdHash(canonicalId))

        viewModel.setCandidateWorkerId(typed)
        val normalised = viewModel.state.value.candidateWorkerId

        assertThat(AttestationCodec.workerIdHash(normalised))
            .isEqualTo(AttestationCodec.workerIdHash(canonicalId))
    }

    @Test
    fun `an enrolled worker's stored hash matches what the verify screen would compute`() = runTest {
        // Both ends of the round trip, with the supervisor and the inspector each typing lower case.
        workers.register(
            workerId = "jh-dhn-001-w00042",
            siteId = "JH-DHN-001",
            fullName = "Budhan Manjhi",
            preferredLanguage = "hi",
            pictogramMode = false,
        )
        val stored = workers.find(canonicalId)!!

        viewModel.setCandidateWorkerId("jh-dhn-001-w00042")
        val inspectorHash = AttestationCodec.workerIdHash(
            viewModel.state.value.candidateWorkerId,
        )

        assertThat(org.jaagruk.core.util.Hex.encode(inspectorHash))
            .isEqualTo(stored.workerIdHash)
    }

    @Test
    fun `changing the candidate id clears a previous verdict on identity`() {
        viewModel.setCandidateWorkerId(canonicalId)
        assertThat(viewModel.state.value.identityMatches).isNull()

        viewModel.setCandidateWorkerId("JH-DHN-001-W00043")
        assertThat(viewModel.state.value.identityMatches).isNull()
    }

    @Test
    fun `checking identity with no verdict on screen does nothing rather than crashing`() {
        viewModel.setCandidateWorkerId(canonicalId)

        viewModel.checkIdentity()

        assertThat(viewModel.state.value.identityMatches).isNull()
    }

    @Test
    fun `a malformed payload is reported rather than throwing`() = runTest {
        viewModel.verify("not-a-jaagruk-certificate")

        val verdict = viewModel.state.value.verdict
        assertThat(verdict).isNotNull()
        assertThat(verdict!!.status.name).isEqualTo("MALFORMED")
        // Scanning stops so the inspector can read the verdict instead of it flickering.
        assertThat(viewModel.state.value.scanning).isFalse()
    }

    @Test
    fun `a blank payload is ignored`() = runTest {
        viewModel.verify("   ")

        assertThat(viewModel.state.value.verdict).isNull()
    }

    @Test
    fun `resetting returns the screen to scanning with nothing carried over`() = runTest {
        viewModel.verify("not-a-jaagruk-certificate")
        viewModel.setCandidateWorkerId(canonicalId)

        viewModel.resetScanning()

        val state = viewModel.state.value
        assertThat(state.scanning).isTrue()
        assertThat(state.verdict).isNull()
        assertThat(state.reasons).isEmpty()
        assertThat(state.candidateWorkerId).isEmpty()
        assertThat(state.identityMatches).isNull()
        // Cleared, so re-scanning the same card is not skipped as a repeat.
        assertThat(state.lastVerifiedQr).isNull()
    }

    @Test
    fun `the same payload twice is not re-verified, so the verdict does not flicker`() = runTest {
        viewModel.verify("not-a-jaagruk-certificate")
        viewModel.setCandidateWorkerId(canonicalId)

        // A camera hands the same string over many times a second; re-running would wipe the
        // candidate id the inspector is part-way through typing.
        viewModel.verify("not-a-jaagruk-certificate")

        assertThat(viewModel.state.value.candidateWorkerId).isEqualTo(canonicalId)
    }
}
