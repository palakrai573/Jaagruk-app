package org.jaagruk.safety.ui.home

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
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.data.repo.RetentionRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.sync.SyncStatusProvider
import org.jaagruk.safety.sync.TimeSyncTracker
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-time notification permission prompt.
 *
 * `POST_NOTIFICATIONS` was declared in the manifest — with a comment saying it was requested at
 * runtime on API 33+ — and nothing ever asked. `RefresherReminderWorker` only checked it, found it
 * missing, and returned quietly, so on Android 13 and above the spaced-refresher reminders never
 * arrived and nothing said why.
 *
 * Asking exactly once matters as much as asking at all: Android stops showing the dialog after two
 * refusals and silently denies from then on, so a screen that asks on every visit burns the user's
 * only two chances and then appears broken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPromptTest {

    private lateinit var database: JaagrukDatabase
    private lateinit var deviceProfile: DeviceProfile
    private lateinit var workers: WorkerRepository

    private val workerId = "JH-DHN-001-W00042"
    private val clock = FixedWallClock(1_760_000_000_000L)

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = TestDatabase.create()
        deviceProfile = DeviceProfile(database)
        workers = WorkerRepository(database, PinAuthenticator(database.workerDao()), clock)
        workers.register(
            workerId = workerId,
            siteId = "JH-DHN-001",
            fullName = "Budhan Manjhi",
            preferredLanguage = "hi",
            pictogramMode = false,
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): HomeViewModel {
        val keyStore = mockk<SiteKeyStore>(relaxed = true)
        return HomeViewModel(
            retention = RetentionRepository(database, clock),
            workers = workers,
            assessments = AssessmentRepository(
                database,
                CertificateRepository(database, keyStore, clock),
                RetentionRepository(database, clock),
                clock,
                org.jaagruk.core.util.SystemMonotonicTimeSource,
            ),
            deviceProfile = deviceProfile,
            syncScheduler = mockk<SyncScheduler>(relaxed = true),
            timeSync = TimeSyncTracker(database, clock),
            syncStatus = SyncStatusProvider(database),
        )
    }

    @Test
    fun `a handset that has never been asked is asked`() = runTest {
        val viewModel = buildViewModel()
        viewModel.load(workerId)

        assertThat(viewModel.state.value.askForNotifications).isTrue()
    }

    @Test
    fun `once the prompt has been shown it is not asked again`() = runTest {
        val first = buildViewModel()
        first.load(workerId)
        assertThat(first.state.value.askForNotifications).isTrue()

        first.onNotificationPromptShown()
        assertThat(first.state.value.askForNotifications).isFalse()

        // A fresh view model, as if the app had been restarted.
        val second = buildViewModel()
        second.load(workerId)
        assertThat(second.state.value.askForNotifications).isFalse()
    }

    @Test
    fun `the decision survives as a stored fact rather than view model state`() = runTest {
        assertThat(deviceProfile.hasAskedForNotifications()).isFalse()

        val viewModel = buildViewModel()
        viewModel.load(workerId)
        viewModel.onNotificationPromptShown()

        assertThat(deviceProfile.hasAskedForNotifications()).isTrue()
    }

    @Test
    fun `a refusal is remembered too, so the worker is not nagged`() = runTest {
        // The screen calls onNotificationPromptShown from the permission result callback whatever the
        // answer was, because a denial is still an answer and re-asking would waste the second of two
        // chances Android allows.
        val viewModel = buildViewModel()
        viewModel.load(workerId)
        viewModel.onNotificationPromptShown()

        val later = buildViewModel()
        later.load(workerId)

        assertThat(later.state.value.askForNotifications).isFalse()
        assertThat(deviceProfile.hasAskedForNotifications()).isTrue()
    }

    @Test
    fun `asking does not interfere with the rest of the home screen loading`() = runTest {
        val viewModel = buildViewModel()
        viewModel.load(workerId)

        val state = viewModel.state.value
        assertThat(state.askForNotifications).isTrue()
        assertThat(state.workerName).isEqualTo("Budhan Manjhi")
        // Standings come from the compile-time catalogue, so every module is listed on a fresh handset.
        assertThat(state.modules).isNotEmpty()
    }
}
