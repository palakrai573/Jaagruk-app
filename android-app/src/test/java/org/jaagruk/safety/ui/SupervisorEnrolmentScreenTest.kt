package org.jaagruk.safety.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jaagruk.core.util.FixedWallClock
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.data.repo.RetentionRepository
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.sync.SyncStatusProvider
import org.jaagruk.safety.sync.TimeSyncTracker
import org.jaagruk.safety.sync.api.SessionStore
import org.jaagruk.safety.sync.nearby.NearbyGossipService
import org.jaagruk.safety.testing.TestDatabase
import org.jaagruk.safety.ui.supervisor.SupervisorScreen
import org.jaagruk.safety.ui.supervisor.SupervisorViewModel
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The supervisor enrolment UI, composed and driven for real.
 *
 * This is the screen a fresh handset has to get through before anybody can train, so it is worth
 * asserting that it actually renders and that typing into it reaches the database. Composing it also
 * exercises every `stringResource` on the screen, which is how a missing or misnumbered format
 * argument shows up as a failure rather than as a crash in somebody's hand.
 *
 * [SiteKeyStore] and [NearbyGossipService] are faked because they need the Android Keystore and
 * Google Play services respectively. Everything else is real, including the database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SupervisorEnrolmentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: JaagrukDatabase
    private lateinit var workers: WorkerRepository
    private lateinit var deviceProfile: DeviceProfile
    private lateinit var keyStore: SiteKeyStore

    private val siteId = "JH-DHN-001"
    private val clock = FixedWallClock(1_760_000_000_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = TestDatabase.create()
        workers = WorkerRepository(database, PinAuthenticator(database.workerDao()), clock)
        deviceProfile = DeviceProfile(database)

        keyStore = mockk(relaxed = true)
        every { keyStore.siteId } returns siteId
        every { keyStore.hasSiteKey() } returns true
        every { keyStore.sitePublicKey() } returns ByteArray(32) { it.toByte() }
        every { keyStore.keyEpoch } returns 1
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): SupervisorViewModel {
        val gossip = mockk<NearbyGossipService>(relaxed = true)
        every { gossip.state } returns MutableStateFlow(NearbyGossipService.State())

        return SupervisorViewModel(
            keyStore = keyStore,
            deviceProfile = deviceProfile,
            sites = SiteRepository(database, clock),
            workers = workers,
            certificates = CertificateRepository(database, keyStore, clock),
            retention = RetentionRepository(database, clock),
            syncScheduler = mockk<SyncScheduler>(relaxed = true),
            timeSync = TimeSyncTracker(database, clock),
            session = mockk<SessionStore>(relaxed = true),
            gossip = gossip,
            syncStatus = SyncStatusProvider(database),
        )
    }

    private fun setScreen(viewModel: SupervisorViewModel) {
        compose.setContent {
            JaagrukTheme {
                SupervisorScreen(
                    onBack = {},
                    onSiteScan = {},
                    onVoiceEnroll = {},
                    onVerify = {},
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun `the screen composes without throwing`() {
        setScreen(buildViewModel())

        compose.onNodeWithText("Supervisor tools", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the add-a-worker section is present`() {
        setScreen(buildViewModel())

        compose.onNodeWithText("Add a worker", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `typing a worker and tapping add writes them to the database`() = runTest {
        val viewModel = buildViewModel()
        setScreen(viewModel)

        compose.onNodeWithText("Worker ID", substring = true)
            .performScrollTo()
            .performTextInput("JH-DHN-001-W00042")
        compose.onNodeWithText("Full name", substring = true)
            .performScrollTo()
            .performTextInput("Budhan Manjhi")
        compose.onNodeWithText("Add worker", substring = true).performScrollTo().performClick()
        compose.waitForIdle()

        val saved = workers.all()
        assertThat(saved).hasSize(1)
        assertThat(saved.single().workerId).isEqualTo("JH-DHN-001-W00042")
        assertThat(saved.single().fullName).isEqualTo("Budhan Manjhi")
        assertThat(saved.single().siteId).isEqualTo(siteId)
    }

    @Test
    fun `a lower-case id typed by a supervisor is upper-cased in the field as they type`() {
        val viewModel = buildViewModel()
        setScreen(viewModel)

        compose.onNodeWithText("Worker ID", substring = true)
            .performScrollTo()
            .performTextInput("jh-dhn-001-w00042")
        compose.waitForIdle()

        // Shown in the canonical form so the supervisor can check it against the physical card,
        // rather than being silently rewritten on save.
        assertThat(viewModel.state.value.newWorkerId).isEqualTo("JH-DHN-001-W00042")
    }

    @Test
    fun `a badly formed id is refused on screen and nothing is written`() = runTest {
        val viewModel = buildViewModel()
        setScreen(viewModel)

        compose.onNodeWithText("Worker ID", substring = true)
            .performScrollTo()
            .performTextInput("ABCDEFGH")
        compose.onNodeWithText("Full name", substring = true)
            .performScrollTo()
            .performTextInput("Budhan Manjhi")
        compose.onNodeWithText("Add worker", substring = true).performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(workers.all()).isEmpty()
        assertThat(viewModel.state.value.message).isNotNull()
    }

    @Test
    fun `the refusal banner renders with its format argument filled in`() = runTest {
        // Driven through the view model rather than the UI, then composed. The banner is the first
        // item in the list, so it is on screen immediately — whereas scrolling down to tap the
        // button disposes it, since a LazyColumn does not keep off-screen items composed.
        val viewModel = buildViewModel()
        viewModel.setNewWorkerId("ABCDEFGH")
        viewModel.setNewWorkerName("Budhan Manjhi")
        viewModel.registerWorker()

        setScreen(viewModel)
        compose.waitForIdle()

        // The banner is on screen, which means the string resolved rather than throwing on a
        // missing format argument.
        compose.onNodeWithText("must look like", substring = true).assertIsDisplayed()
        // And it was given the example to substitute, so the supervisor is shown the shape to copy
        // rather than a bare "%1$s".
        assertThat(viewModel.state.value.message!!.args)
            .containsExactly(WorkerRepository.WORKER_ID_EXAMPLE)
        assertThat(workers.all()).isEmpty()
    }

    @Test
    fun `enrolment is blocked with an explanation when the handset has no site`() {
        every { keyStore.siteId } returns null
        every { keyStore.hasSiteKey() } returns false

        val viewModel = buildViewModel()
        setScreen(viewModel)

        assertThat(viewModel.state.value.canEnrolWorkers).isFalse()
        compose.onNodeWithText("Generate the site key first", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the outstanding upload count is shown once a worker is enrolled offline`() = runTest {
        workers.register(
            workerId = "JH-DHN-001-W00042",
            siteId = siteId,
            fullName = "Budhan Manjhi",
            preferredLanguage = "hi",
            pictogramMode = false,
        )

        setScreen(buildViewModel())
        compose.waitForIdle()

        compose.onNodeWithText("waiting to reach the server", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
