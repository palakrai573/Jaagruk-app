package org.jaagruk.safety.ui.verify

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jaagruk.core.util.FixedWallClock
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.sync.api.JaagrukApi
import org.jaagruk.safety.sync.api.VerifyResponse
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

/**
 * The optional online cross-check.
 *
 * `JaagrukApi.verifyCertificate` was declared and documented as adding what only the server knows, and
 * nothing called it. Wiring it up is only safe under one rule, which every test here defends: it adds
 * information and can never change the verdict. The verdict is an Ed25519 check over bytes the
 * inspector is physically holding, and a server that disagreed would either be looking at a different
 * record or be wrong. Letting the network overrule that would turn a tool that works underground into
 * one that needs a signal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossCheckTest {

    private lateinit var database: JaagrukDatabase
    private lateinit var api: JaagrukApi
    private lateinit var viewModel: VerifyViewModel

    private val malformed = "not-a-jaagruk-certificate"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = TestDatabase.create()
        val clock = FixedWallClock(1_760_000_000_000L)
        api = mockk()
        viewModel = VerifyViewModel(
            certificates = CertificateRepository(
                database,
                mockk<SiteKeyStore>(relaxed = true),
                clock,
            ),
            api = api,
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun serverSays(
        status: String = "MALFORMED",
        workerFullName: String? = null,
        readinessPermille: Int? = null,
        readinessBand: String? = null,
        statutoryValid: Boolean? = null,
    ) {
        coEvery { api.verifyCertificate(any()) } returns Response.success(
            VerifyResponse(
                status = status,
                trustworthy = false,
                indicatesTampering = false,
                reasons = emptyList(),
                workerFullName = workerFullName,
                readinessPermille = readinessPermille,
                readinessBand = readinessBand,
                statutoryValid = statutoryValid,
            ),
        )
    }

    @Test
    fun `the offline verdict is reached with no network at all`() = runTest {
        coEvery { api.verifyCertificate(any()) } throws IOException("no signal")

        viewModel.verify(malformed)

        // The verdict is present and correct despite the network having failed.
        assertThat(viewModel.state.value.verdict!!.status.name).isEqualTo("MALFORMED")
        assertThat(viewModel.state.value.crossCheck)
            .isEqualTo(VerifyViewModel.CrossCheck.UNAVAILABLE)
        assertThat(viewModel.state.value.insight).isNull()
    }

    @Test
    fun `no signal is reported plainly rather than as a failure`() = runTest {
        coEvery { api.verifyCertificate(any()) } throws IOException("no signal")

        viewModel.verify(malformed)

        // UNAVAILABLE, not an error state and not an empty RECEIVED. At a mine gate this is the
        // ordinary case and the UI says the verdict stands on its own.
        assertThat(viewModel.state.value.crossCheck)
            .isEqualTo(VerifyViewModel.CrossCheck.UNAVAILABLE)
    }

    @Test
    fun `a server error leaves the verdict untouched`() = runTest {
        coEvery { api.verifyCertificate(any()) } returns Response.error(
            500,
            okhttp3.ResponseBody.create(null, ""),
        )

        viewModel.verify(malformed)

        assertThat(viewModel.state.value.verdict!!.status.name).isEqualTo("MALFORMED")
        assertThat(viewModel.state.value.crossCheck)
            .isEqualTo(VerifyViewModel.CrossCheck.UNAVAILABLE)
    }

    @Test
    fun `server detail is attached when it arrives`() = runTest {
        serverSays(
            workerFullName = "Budhan Manjhi",
            readinessPermille = 613,
            readinessBand = "DUE",
            statutoryValid = true,
        )

        viewModel.verify(malformed)

        val insight = viewModel.state.value.insight!!
        assertThat(viewModel.state.value.crossCheck)
            .isEqualTo(VerifyViewModel.CrossCheck.RECEIVED)
        assertThat(insight.workerFullName).isEqualTo("Budhan Manjhi")
        assertThat(insight.readinessPermille).isEqualTo(613)
        assertThat(insight.readinessBand).isEqualTo("DUE")
        assertThat(insight.statutoryValid).isTrue()
    }

    @Test
    fun `a server that agrees is not reported as disagreeing`() = runTest {
        serverSays(status = "MALFORMED")

        viewModel.verify(malformed)

        assertThat(viewModel.state.value.insight!!.disagreesWithStatus).isNull()
    }

    @Test
    fun `a server that disagrees is reported but does not change the verdict`() = runTest {
        // The whole point. The server claims this is VERIFIED; the device decoded the bytes itself and
        // found them malformed. The device wins, and the discrepancy is surfaced for follow-up.
        serverSays(status = "VERIFIED")

        viewModel.verify(malformed)

        assertThat(viewModel.state.value.verdict!!.status.name).isEqualTo("MALFORMED")
        assertThat(viewModel.state.value.insight!!.disagreesWithStatus).isEqualTo("VERIFIED")
    }

    @Test
    fun `the cross-check never touches the identity result`() = runTest {
        serverSays(status = "VERIFIED", workerFullName = "Somebody Else")

        viewModel.verify(malformed)

        // Identity is a constant-time hash comparison against what the inspector typed. The server has
        // no part in it and must not be able to imply a match.
        assertThat(viewModel.state.value.identityMatches).isNull()
    }

    @Test
    fun `resetting clears the server detail along with the verdict`() = runTest {
        serverSays(workerFullName = "Budhan Manjhi")
        viewModel.verify(malformed)
        assertThat(viewModel.state.value.insight).isNotNull()

        viewModel.resetScanning()

        val state = viewModel.state.value
        assertThat(state.insight).isNull()
        assertThat(state.crossCheck).isEqualTo(VerifyViewModel.CrossCheck.NOT_ATTEMPTED)
        assertThat(state.verdict).isNull()
    }

    @Test
    fun `a blank payload is not sent to the server`() = runTest {
        // api is a strict mock with no stub for this call, so any attempt would fail the test.
        viewModel.verify("   ")

        assertThat(viewModel.state.value.crossCheck)
            .isEqualTo(VerifyViewModel.CrossCheck.NOT_ATTEMPTED)
    }
}
