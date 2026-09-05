package org.jaagruk.safety.ui.hazard

import androidx.test.core.app.ApplicationProvider
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
import org.jaagruk.safety.data.LocalMediaStore
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.hazard.HazardCategory
import org.jaagruk.safety.data.hazard.HazardSeverity
import org.jaagruk.safety.data.repo.HazardRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.input.VoiceNoteRecorder
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Hazard photo capture.
 *
 * The whole pipeline existed — managed storage, a `photoPath` column, the `FileProvider` paths, the
 * media upload worker, deletion once the server has it — and `HazardViewModel` passed `photo = null`
 * hardcoded with no camera launcher anywhere. Near-miss photo reporting could not take a photo.
 *
 * These tests stand in for the camera by writing bytes into the file the view model hands out, which
 * is exactly what `ActivityResultContracts.TakePicture` does on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HazardPhotoTest {

    private lateinit var database: JaagrukDatabase
    private lateinit var media: LocalMediaStore
    private lateinit var hazards: HazardRepository
    private lateinit var viewModel: HazardViewModel

    private val siteId = "JH-DHN-001"
    private val workerId = "JH-DHN-001-W00042"
    private val clock = FixedWallClock(1_760_000_000_000L)

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = TestDatabase.create()
        media = LocalMediaStore(ApplicationProvider.getApplicationContext())
        hazards = HazardRepository(database, media, clock)

        val workers = WorkerRepository(database, PinAuthenticator(database.workerDao()), clock)
        workers.register(
            workerId = workerId,
            siteId = siteId,
            fullName = "Budhan Manjhi",
            preferredLanguage = "hi",
            pictogramMode = false,
        )

        val recorder = mockk<VoiceNoteRecorder>(relaxed = true)
        every { recorder.state } returns MutableStateFlow(VoiceNoteRecorder.State())

        viewModel = HazardViewModel(
            hazards = hazards,
            workers = workers,
            deviceProfile = DeviceProfile(database),
            recorder = recorder,
            media = media,
            syncScheduler = mockk<SyncScheduler>(relaxed = true),
        )
        viewModel.load(workerId)
    }

    @After
    fun tearDown() {
        media.clearScratch()
        database.close()
        Dispatchers.resetMain()
    }

    /** Stands in for the camera app writing into the URI it was handed. */
    private fun cameraWrites(bytes: Int = 2_048) {
        val target = viewModel.preparePhotoTarget()
        target.writeBytes(ByteArray(bytes) { 0x42 })
        viewModel.onPhotoCaptured(succeeded = true)
    }

    private fun fileReport() {
        viewModel.selectCategory(HazardCategory.entries.first())
        viewModel.selectSeverity(HazardSeverity.HIGH)
        viewModel.submit()
    }

    @Test
    fun `the target file lives where the FileProvider can expose it`() {
        val target = viewModel.preparePhotoTarget()

        // file_paths.xml exposes files-path hazard_photos/ only. A cache-directory target could not
        // be handed to the camera without widening the provider.
        assertThat(target.parentFile!!.name).isEqualTo("hazard_photos")
        assertThat(target.name).startsWith("scratch-")
        assertThat(target.name).endsWith(".jpg")
    }

    @Test
    fun `a captured photo is attached to the state`() {
        cameraWrites()

        assertThat(viewModel.state.value.hasPhoto).isTrue()
        assertThat(viewModel.state.value.message).isNull()
    }

    @Test
    fun `a captured photo reaches the stored hazard row`() = runTest {
        cameraWrites()
        fileReport()

        val row = hazards.pendingUpload(10).single()
        assertThat(row.photoPath).isNotNull()
        assertThat(File(row.photoPath!!).exists()).isTrue()
        // Renamed out of scratch and onto the hazard id, so pruning can reason about it.
        assertThat(File(row.photoPath!!).name).isEqualTo("${row.hazardId}.jpg")
        assertThat(row.mediaPending).isTrue()
    }

    @Test
    fun `a report with no photo still files, because a photo is corroboration not a requirement`() =
        runTest {
            fileReport()

            val row = hazards.pendingUpload(10).single()
            assertThat(row.photoPath).isNull()
            assertThat(row.mediaPending).isFalse()
            assertThat(viewModel.state.value.filed).isTrue()
        }

    @Test
    fun `a cancelled capture attaches nothing and says nothing`() {
        val target = viewModel.preparePhotoTarget()
        // The camera returns false and often leaves an empty file behind.
        target.writeBytes(ByteArray(0))
        viewModel.onPhotoCaptured(succeeded = false)

        assertThat(viewModel.state.value.hasPhoto).isFalse()
        assertThat(target.exists()).isFalse()
        // An explicit cancel is not an error worth a banner.
        assertThat(viewModel.state.value.message).isNull()
    }

    @Test
    fun `a capture that reports success but wrote nothing is refused with a message`() {
        val target = viewModel.preparePhotoTarget()
        target.writeBytes(ByteArray(0))
        viewModel.onPhotoCaptured(succeeded = true)

        // Attaching a zero-byte photo would mean spending a mine-site uplink uploading nothing.
        assertThat(viewModel.state.value.hasPhoto).isFalse()
        assertThat(target.exists()).isFalse()
        assertThat(viewModel.state.value.message).isNotNull()
    }

    @Test
    fun `retaking replaces the first photo rather than leaving it behind`() {
        val first = viewModel.preparePhotoTarget()
        first.writeBytes(ByteArray(1_024))
        viewModel.onPhotoCaptured(succeeded = true)

        val second = viewModel.preparePhotoTarget()
        second.writeBytes(ByteArray(1_024))
        viewModel.onPhotoCaptured(succeeded = true)

        assertThat(first.exists()).isFalse()
        assertThat(second.exists()).isTrue()
        assertThat(viewModel.state.value.hasPhoto).isTrue()
    }

    @Test
    fun `removing a photo deletes it from the handset`() {
        val target = viewModel.preparePhotoTarget()
        target.writeBytes(ByteArray(1_024))
        viewModel.onPhotoCaptured(succeeded = true)

        viewModel.discardPhoto()

        assertThat(viewModel.state.value.hasPhoto).isFalse()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `cancelling the report deletes the photo, because it is a picture of a colleague`() {
        val target = viewModel.preparePhotoTarget()
        target.writeBytes(ByteArray(1_024))
        viewModel.onPhotoCaptured(succeeded = true)

        viewModel.cancel()

        // A scratch file is not named after a hazard id, so pruneTo would never reclaim it. Anything
        // missed here would sit on a shared site phone indefinitely.
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `a denied camera permission is explained and does not block the report`() = runTest {
        viewModel.onCameraPermissionDenied()
        assertThat(viewModel.state.value.message).isNotNull()

        fileReport()

        assertThat(viewModel.state.value.filed).isTrue()
        assertThat(hazards.pendingUpload(10)).hasSize(1)
    }

    @Test
    fun `clearScratch reaches photos as well as voice notes`() {
        val target = viewModel.preparePhotoTarget()
        target.writeBytes(ByteArray(1_024))

        media.clearScratch()

        assertThat(target.exists()).isFalse()
    }
}
