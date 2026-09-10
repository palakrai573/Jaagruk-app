package org.jaagruk.safety.ui.signin

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jaagruk.core.util.FixedWallClock
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.sync.SyncStatusProvider
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first-run journey on a handset that has never had a network.
 *
 * This is the scenario the whole offline design exists for, and until worker enrolment was wired up
 * it did not work: nothing seeds the database, the roster only arrived from the server, and the
 * worker picker was therefore empty forever. A supervisor could generate a site key and then had
 * nowhere to go.
 *
 * The test drives the real [SignInViewModel] against a real Room database. Only the three
 * collaborators that would genuinely need a server or the Android framework are faked, and the
 * fakes are relaxed mocks that are never told to return anything — if the flow touched the network
 * the test would still pass, so every assertion below is about local behaviour only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineFirstRunFlowTest {

    private lateinit var database: JaagrukDatabase
    private lateinit var workers: WorkerRepository
    private lateinit var deviceProfile: DeviceProfile
    private lateinit var viewModel: SignInViewModel

    private val clock = FixedWallClock(1_760_000_000_000L)
    private val siteId = "JH-DHN-001"
    private val workerId = "JH-DHN-001-W00042"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        database = TestDatabase.create()

        workers = WorkerRepository(
            database = database,
            pinAuthenticator = PinAuthenticator(database.workerDao()),
            clock = clock,
        )
        deviceProfile = DeviceProfile(database)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = SignInViewModel(
        workers = workers,
        deviceProfile = deviceProfile,
        // Relaxed: the Android Keystore is not available on the JVM, so generateSiteKey is faked. What
        // the demo tests below assert is the roster and the site id, which are real database writes.
        keyStore = mockk(relaxed = true),
        sites = SiteRepository(database, clock),
        clock = clock,
        syncStatus = SyncStatusProvider(database),
    )

    private suspend fun enrol(id: String = workerId, name: String = "Budhan Manjhi") =
        workers.register(
            workerId = id,
            siteId = siteId,
            fullName = name,
            preferredLanguage = "hi",
            pictogramMode = false,
        )

    @Test
    fun `before any enrolment the picker is empty, which is the dead end that was fixed`() =
        runTest {
            viewModel = buildViewModel()

            val step = viewModel.state.value.step
            assertThat(step).isInstanceOf(SignInStep.PickWorker::class.java)
            assertThat((step as SignInStep.PickWorker).workers).isEmpty()
        }

    @Test
    fun `a worker enrolled offline appears in the picker`() = runTest {
        enrol()
        viewModel = buildViewModel()

        val rows = (viewModel.state.value.step as SignInStep.PickWorker).workers
        assertThat(rows).hasSize(1)
        assertThat(rows.single().workerId).isEqualTo(workerId)
        assertThat(rows.single().fullName).isEqualTo("Budhan Manjhi")
        // No PIN yet, so the UI offers "choose a PIN" rather than "enter your PIN".
        assertThat(rows.single().hasPin).isFalse()
    }

    @Test
    fun `the full first-run journey completes with no network at all`() = runTest {
        enrol()
        viewModel = buildViewModel()

        viewModel.selectWorker(workerId)
        val pinStep = viewModel.state.value.step
        assertThat(pinStep).isInstanceOf(SignInStep.EnterPin::class.java)
        assertThat((pinStep as SignInStep.EnterPin).settingNewPin).isTrue()

        viewModel.setPin("4291")
        var signedInAs: String? = null
        viewModel.submitPin { signedInAs = it }

        assertThat(signedInAs).isEqualTo(workerId)
        assertThat(viewModel.state.value.message).isNull()
        // Recorded as the active worker, which is what the home screen is then opened for.
        assertThat(deviceProfile.activeWorkerId()).isEqualTo(workerId)
    }

    @Test
    fun `the PIN chosen at first sign-in is what authenticates on the next one`() = runTest {
        enrol()
        viewModel = buildViewModel()
        viewModel.selectWorker(workerId)
        viewModel.setPin("4291")
        viewModel.submitPin { }

        // Second sign-in: a fresh view model, as if the app had been restarted.
        viewModel = buildViewModel()
        val rows = (viewModel.state.value.step as SignInStep.PickWorker).workers
        assertThat(rows.single().hasPin).isTrue()

        viewModel.selectWorker(workerId)
        assertThat((viewModel.state.value.step as SignInStep.EnterPin).settingNewPin).isFalse()

        viewModel.setPin("4291")
        var signedInAs: String? = null
        viewModel.submitPin { signedInAs = it }
        assertThat(signedInAs).isEqualTo(workerId)
    }

    @Test
    fun `a wrong PIN is refused and reports the attempts left`() = runTest {
        enrol()
        viewModel = buildViewModel()
        viewModel.selectWorker(workerId)
        viewModel.setPin("4291")
        viewModel.submitPin { }

        viewModel = buildViewModel()
        viewModel.selectWorker(workerId)
        viewModel.setPin("1234")
        var signedInAs: String? = null
        viewModel.submitPin { signedInAs = it }

        assertThat(signedInAs).isNull()
        assertThat(viewModel.state.value.message).isNotNull()
        // Cleared, so the next attempt starts from an empty field rather than a half-corrected one.
        assertThat(viewModel.state.value.pin).isEmpty()
    }

    @Test
    fun `a guessable first PIN is refused with a reason`() = runTest {
        enrol()
        viewModel = buildViewModel()
        viewModel.selectWorker(workerId)

        viewModel.setPin("1111")
        var signedInAs: String? = null
        viewModel.submitPin { signedInAs = it }

        assertThat(signedInAs).isNull()
        assertThat(viewModel.state.value.message).isNotNull()
        assertThat(workers.hasPin(workerId)).isFalse()
    }

    @Test
    fun `the PIN field accepts digits only`() = runTest {
        viewModel = buildViewModel()

        viewModel.setPin("12ab34")

        assertThat(viewModel.state.value.pin).isEqualTo("1234")
    }

    @Test
    fun `search finds a worker by name and by id`() = runTest {
        enrol(id = "JH-DHN-001-W00001", name = "Budhan Manjhi")
        enrol(id = "JH-DHN-001-W00002", name = "Sita Kumari")
        viewModel = buildViewModel()

        viewModel.setQuery("sita")
        assertThat((viewModel.state.value.step as SignInStep.PickWorker).workers.map { it.fullName })
            .containsExactly("Sita Kumari")

        viewModel.setQuery("W00001")
        assertThat((viewModel.state.value.step as SignInStep.PickWorker).workers.map { it.workerId })
            .containsExactly("JH-DHN-001-W00001")

        viewModel.setQuery("")
        assertThat((viewModel.state.value.step as SignInStep.PickWorker).workers).hasSize(2)
    }

    @Test
    fun `an unknown search term yields an empty list rather than everybody`() = runTest {
        enrol()
        viewModel = buildViewModel()

        viewModel.setQuery("nobody-by-that-name")

        assertThat((viewModel.state.value.step as SignInStep.PickWorker).workers).isEmpty()
    }

    @Test
    fun `backing out of the PIN step returns to the picker with the PIN cleared`() = runTest {
        enrol()
        viewModel = buildViewModel()
        viewModel.selectWorker(workerId)
        viewModel.setPin("4291")

        viewModel.backToPicker()

        assertThat(viewModel.state.value.step).isInstanceOf(SignInStep.PickWorker::class.java)
        assertThat(viewModel.state.value.pin).isEmpty()
    }

    @Test
    fun `demo setup makes the app usable in one tap on a handset with no network`() = runTest {
        viewModel = buildViewModel()
        assertThat((viewModel.state.value.step as SignInStep.PickWorker).workers).isEmpty()

        viewModel.setUpDemoSite()

        // A site to sign certificates against, and a roster to sign in as. Both were previously
        // reachable only through a Supervisor tools screen that itself sat behind a network login.
        assertThat(viewModel.state.value.siteId).isEqualTo(SignInViewModel.DEMO_SITE_ID)
        val rows = (viewModel.state.value.step as SignInStep.PickWorker).workers
        assertThat(rows).hasSize(SignInViewModel.DEMO_WORKERS.size)
        assertThat(viewModel.state.value.message).isNotNull()
    }

    @Test
    fun `a demo worker can sign in immediately with the PIN shown on screen`() = runTest {
        viewModel = buildViewModel()
        viewModel.setUpDemoSite()

        val first = SignInViewModel.DEMO_WORKERS.first().first
        viewModel.selectWorker(first)
        // Already has a PIN, so this is the returning-worker path rather than choosing one.
        assertThat((viewModel.state.value.step as SignInStep.EnterPin).settingNewPin).isFalse()

        viewModel.setPin(SignInViewModel.DEMO_PIN)
        var signedInAs: String? = null
        viewModel.submitPin { signedInAs = it }

        assertThat(signedInAs).isEqualTo(first)
    }

    @Test
    fun `demo ids satisfy the same validation the server enforces`() = runTest {
        // A demo roster the server would reject would be demonstrating a path that cannot sync.
        for ((workerId, _, _) in SignInViewModel.DEMO_WORKERS) {
            assertThat(WorkerRepository.WORKER_ID_PATTERN.matches(workerId)).isTrue()
        }
    }

    @Test
    fun `running demo setup twice does not duplicate the roster`() = runTest {
        viewModel = buildViewModel()
        viewModel.setUpDemoSite()
        viewModel.setUpDemoSite()

        assertThat(workers.all()).hasSize(SignInViewModel.DEMO_WORKERS.size)
    }

    @Test
    fun `an inactive worker is not offered for sign-in`() = runTest {
        enrol()
        val row = database.workerDao().find(workerId)!!
        database.workerDao().upsert(row.copy(active = false))

        viewModel = buildViewModel()

        assertThat((viewModel.state.value.step as SignInStep.PickWorker).workers).isEmpty()
    }

    @Test
    fun `the site the handset is enrolled to is surfaced on the sign-in screen`() = runTest {
        deviceProfile.setActiveSiteId(siteId)

        viewModel = buildViewModel()

        assertThat(viewModel.state.value.siteId).isEqualTo(siteId)
    }
}
