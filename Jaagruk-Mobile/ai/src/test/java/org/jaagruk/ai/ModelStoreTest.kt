package org.jaagruk.ai

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.core.ai.AiLanguage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Where the model file lives, and what counts as a usable one.
 *
 * The checks here exist because the model arrives out of band — sideloaded by a supervisor or relayed
 * off another handset — so a half-finished transfer is a normal event rather than an exceptional one.
 * A partial file that the app treats as valid produces a native-side failure that reads to the worker
 * as a broken app rather than an incomplete download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelStoreTest {

    private lateinit var store: ModelStore

    private val ggufHeader = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    @Before
    fun setUp() {
        store = ModelStore(ApplicationProvider.getApplicationContext())
        store.delete()
    }

    private fun bytes(size: Long, header: ByteArray = ggufHeader): ByteArray {
        val content = ByteArray(size.toInt())
        header.copyInto(content)
        return content
    }

    // -----------------------------------------------------------------------
    // Presence
    // -----------------------------------------------------------------------

    @Test
    fun `no file means no model`() {
        assertThat(store.isModelPresent()).isFalse()
    }

    @Test
    fun `the model path is app private so no storage permission is involved`() {
        assertThat(store.modelPath).contains("files")
        assertThat(store.modelPath).endsWith(ModelStore.MODEL_FILE_NAME)
    }

    @Test
    fun `a plausible gguf file is accepted`() = runTest {
        val size = ModelStore.MIN_PLAUSIBLE_BYTES + 1_024
        val result = store.install(ByteArrayInputStream(bytes(size)))
        assertThat(result.isSuccess).isTrue()
        assertThat(store.isModelPresent()).isTrue()
    }

    @Test
    fun `an interrupted transfer is refused rather than half installed`() = runTest {
        val result = store.install(ByteArrayInputStream(bytes(5L * 1024 * 1024)))
        assertThat(result.isFailure).isTrue()
        assertThat(store.isModelPresent()).isFalse()
        // And no partial file is left behind for the next launch to pick up.
        assertThat(store.modelFile.exists()).isFalse()
    }

    @Test
    fun `a file that is not gguf is refused`() = runTest {
        val size = ModelStore.MIN_PLAUSIBLE_BYTES + 1_024
        val notAModel = bytes(size, header = byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // a zip
        val result = store.install(ByteArrayInputStream(notAModel))
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("GGUF")
        assertThat(store.isModelPresent()).isFalse()
    }

    @Test
    fun `install reports progress`() = runTest {
        val seen = mutableListOf<Long>()
        store.install(ByteArrayInputStream(bytes(ModelStore.MIN_PLAUSIBLE_BYTES + 1_024))) {
            seen += it
        }
        assertThat(seen).isNotEmpty()
        assertThat(seen).isInOrder()
        assertThat(seen.last()).isGreaterThan(ModelStore.MIN_PLAUSIBLE_BYTES)
    }

    @Test
    fun `installing over an existing model replaces it`() = runTest {
        store.install(ByteArrayInputStream(bytes(ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)))
        val first = store.modelFile.length()
        store.install(ByteArrayInputStream(bytes(ModelStore.MIN_PLAUSIBLE_BYTES + 8_192)))
        assertThat(store.modelFile.length()).isNotEqualTo(first)
        assertThat(store.isModelPresent()).isTrue()
    }

    @Test
    fun `delete removes the model`() = runTest {
        store.install(ByteArrayInputStream(bytes(ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)))
        assertThat(store.delete()).isTrue()
        assertThat(store.isModelPresent()).isFalse()
    }

    // -----------------------------------------------------------------------
    // Importing a sideloaded file
    //
    // filesDir cannot be written to from outside the app, so asking somebody to put a file there is
    // asking for root or a three-command adb sequence. A file is dropped into app-specific external
    // storage instead and moved into place on the next launch.
    // -----------------------------------------------------------------------

    private fun drop(name: String, size: Long, header: ByteArray = ggufHeader): File {
        val directory = store.dropDirectory()!!
        return File(directory, name).apply { writeBytes(bytes(size, header)) }
    }

    private fun clearDropDirectory() {
        store.dropDirectory()?.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `nothing dropped means nothing to import`() = runTest {
        clearDropDirectory()
        assertThat(store.pendingImport()).isNull()
        assertThat(store.importIfPending()).isNull()
    }

    @Test
    fun `a dropped model is moved into place`() = runTest {
        clearDropDirectory()
        val dropped = drop(ModelStore.MODEL_FILE_NAME, ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)

        val result = store.importIfPending()

        assertThat(result).isNotNull()
        assertThat(result!!.isSuccess).isTrue()
        assertThat(store.isModelPresent()).isTrue()
        // Moved, not copied: leaving 769 MiB behind would double the footprint of the one thing
        // this whole arrangement exists to avoid duplicating.
        assertThat(dropped.exists()).isFalse()
    }

    @Test
    fun `a dropped file whose case differs is still imported`() = runTest {
        clearDropDirectory()
        // The published Hugging Face artefact is gemma-3-1b-it-Q4_K_M.gguf, which does not match
        // MODEL_FILE_NAME byte for byte, and Android filesystems are case sensitive. Requiring an
        // exact match would reject the one file almost everybody downloads.
        drop("gemma-3-1b-it-Q4_K_M.gguf", ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)

        val result = store.importIfPending()

        assertThat(result!!.isSuccess).isTrue()
        assertThat(store.isModelPresent()).isTrue()
    }

    @Test
    fun `a truncated download is refused with a size in the message`() = runTest {
        clearDropDirectory()
        drop(ModelStore.MODEL_FILE_NAME, 5L * 1024 * 1024)

        val result = store.importIfPending()

        assertThat(result!!.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("too")
        assertThat(store.isModelPresent()).isFalse()
    }

    @Test
    fun `a dropped file that is not gguf is refused`() = runTest {
        clearDropDirectory()
        drop(
            ModelStore.MODEL_FILE_NAME,
            ModelStore.MIN_PLAUSIBLE_BYTES + 1_024,
            header = byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        )

        val result = store.importIfPending()

        assertThat(result!!.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("GGUF")
        assertThat(store.isModelPresent()).isFalse()
    }

    @Test
    fun `two candidates with no exact name are refused rather than guessed at`() = runTest {
        clearDropDirectory()
        drop("some-other-model.gguf", ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)
        drop("a-third-model.gguf", ModelStore.MIN_PLAUSIBLE_BYTES + 2_048)

        // Choosing the largest or the newest would silently load a model nobody picked, and which
        // model produced an answer is not something to be vague about.
        assertThat(store.pendingImport()).isNull()
        assertThat(store.importIfPending()).isNull()
        assertThat(store.isModelPresent()).isFalse()
    }

    @Test
    fun `the expected name wins when several files are present`() = runTest {
        clearDropDirectory()
        drop("some-other-model.gguf", ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)
        drop(ModelStore.MODEL_FILE_NAME, ModelStore.MIN_PLAUSIBLE_BYTES + 4_096)

        assertThat(store.pendingImport()!!.name).isEqualTo(ModelStore.MODEL_FILE_NAME)
        assertThat(store.importIfPending()!!.isSuccess).isTrue()
        assertThat(store.isModelPresent()).isTrue()
    }

    // -----------------------------------------------------------------------
    // The source ladder
    // -----------------------------------------------------------------------

    @Test
    fun `an already resident model is reported without any copying`() = runTest {
        clearDropDirectory()
        store.install(ByteArrayInputStream(bytes(ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)))

        var progressCalls = 0
        val result = store.ensureAvailable { _, _ -> progressCalls++ }

        assertThat(result.getOrNull()).isEqualTo(ModelStore.ModelSource.RESIDENT)
        // The common case must not cost a copy, or every launch would pay for one.
        assertThat(progressCalls).isEqualTo(0)
    }

    @Test
    fun `a dropped file is preferred over extracting a bundled asset`() = runTest {
        clearDropDirectory()
        drop(ModelStore.MODEL_FILE_NAME, ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)

        val result = store.ensureAvailable()

        assertThat(result.getOrNull()).isEqualTo(ModelStore.ModelSource.DROP_DIRECTORY)
        assertThat(store.isModelPresent()).isTrue()
    }

    @Test
    fun `progress is reported against a known total during an import`() = runTest {
        clearDropDirectory()
        val size = ModelStore.MIN_PLAUSIBLE_BYTES + 1_024
        drop("some-model.gguf", size)

        val totals = mutableSetOf<Long>()
        store.ensureAvailable { _, total -> totals += total }

        // A rename needs no byte-by-byte progress, but whatever is reported must carry a real
        // total: a fraction computed against zero is how a progress bar ends up frozen at 0%.
        assertThat(totals.all { it > 0L }).isTrue()
    }

    @Test
    fun `no source at all fails with a path the user can actually write to`() = runTest {
        clearDropDirectory()

        val result = store.ensureAvailable()

        assertThat(result.isFailure).isTrue()
        val message = result.exceptionOrNull()!!.message!!
        assertThat(message).contains(store.dropDirectory()!!.absolutePath)
        // Naming filesDir would be telling somebody to write somewhere they cannot.
        assertThat(message).doesNotContain("/data/data")
    }

    @Test
    fun `a build with no bundled model reports none rather than failing`() {
        // This is the `lean` flavour. The unit-test variant carries no assets/models directory,
        // so it stands in for that build, and the ladder must fall through rather than throw.
        assertThat(store.bundledAssetName()).isNull()
    }

    @Test
    fun `the drop directory is external so adb push and a file manager can both reach it`() {
        val directory = store.dropDirectory()
        assertThat(directory).isNotNull()
        // Not under filesDir: that is the whole point of having a separate drop location.
        assertThat(directory!!.absolutePath).doesNotContain(store.modelFile.parentFile!!.absolutePath)
    }

    // -----------------------------------------------------------------------
    // Device fitness
    // -----------------------------------------------------------------------

    @Test
    fun `thread count is bounded so the handset does not become uncomfortable to hold`() {
        assertThat(store.recommendedThreads()).isAtLeast(2)
        assertThat(store.recommendedThreads()).isAtMost(4)
    }

    @Test
    fun `unknown device memory is not treated as a block`() {
        // Refusing on a failed memory query would disable the feature on the strength of the query
        // failing, not on the strength of the device being unfit.
        assertThat(store.hasEnoughMemory()).isTrue()
    }

    @Test
    fun `describe reports enough to diagnose a missing model from a screenshot`() {
        val description = store.describe()
        assertThat(description).contains("present=")
        assertThat(description).contains("ram=")
        assertThat(description).contains("abi=")
    }

    // -----------------------------------------------------------------------
    // The engine, with no native library present
    // -----------------------------------------------------------------------

    @Test
    fun `the engine reports unsupported rather than crashing without a native library`() = runTest {
        // A unit test JVM has no libjaagruk_llm.so, which is the same situation as an armeabi-v7a
        // handset. The guarded load must turn that into a capability, not an
        // ExceptionInInitializerError.
        val engine = LlamaLlmEngine(store, LlmSessionGuard())
        assertThat(engine.capability(AiLanguage.ENGLISH))
            .isEqualTo(AiCapability.UNSUPPORTED_DEVICE)
        assertThat(engine.ensureLoaded()).isFalse()
        assertThat(engine.state.value).isInstanceOf(LlmState.Failed::class.java)
    }

    @Test
    fun `the engine subscribes to the drill interlock on construction`() = runTest {
        val guard = LlmSessionGuard()
        LlamaLlmEngine(store, guard)
        // No native library, so there is nothing to release; entering a drill must still be safe.
        guard.enterDrill()
        assertThat(guard.isInDrill).isTrue()
        guard.exitDrill()
    }

    @Test
    fun `generation without an engine fails rather than hanging`() = runTest {
        val engine = LlamaLlmEngine(store, LlmSessionGuard())
        val result = engine.generate("prompt", maxTokens = 16)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `the noop engine is always unavailable and never throws`() = runTest {
        val engine = NoopLlmEngine()
        assertThat(engine.capability(AiLanguage.ENGLISH).canGenerate).isFalse()
        assertThat(engine.ensureLoaded()).isFalse()
        assertThat(engine.generate("p", 8).isFailure).isTrue()
        engine.stop()
        engine.unload()
    }

    @Test
    fun `sampling parameters are validated`() {
        assertThat(SamplingParams.GREEDY.temperature).isEqualTo(0.0f)
        runCatching { SamplingParams(temperature = -1.0f) }
            .also { assertThat(it.isFailure).isTrue() }
        runCatching { SamplingParams(topP = 2.0f) }
            .also { assertThat(it.isFailure).isTrue() }
    }
}
