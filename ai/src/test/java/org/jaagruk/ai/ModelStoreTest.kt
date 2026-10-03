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
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException

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

    private fun seedInstalledModel(): Long {
        RandomAccessFile(store.modelFile, "rw").use {
            it.write(ggufHeader)
            it.setLength(ModelStore.MIN_PLAUSIBLE_BYTES + 8192)
        }
        return store.modelFile.length()
    }

    @Test fun `invalid replacement preserves the installed model`() = runTest {
        val originalSize = seedInstalledModel()
        val result = store.install(ByteArrayInputStream(ggufHeader))
        assertThat(result.isFailure).isTrue()
        assertThat(store.isModelPresent()).isTrue()
        assertThat(store.modelFile.length()).isEqualTo(originalSize)
        assertThat(store.modelFile.parentFile!!.listFiles()!!.filter { it.extension == "part" }).isEmpty()
    }

    @Test fun `read failure closes the stream and preserves the installed model`() = runTest {
        val originalSize = seedInstalledModel()
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("transfer disconnected")
            override fun close() { closed = true }
        }
        assertThat(store.install(source).isFailure).isTrue()
        assertThat(closed).isTrue()
        assertThat(store.isModelPresent()).isTrue()
        assertThat(store.modelFile.length()).isEqualTo(originalSize)
        assertThat(store.modelFile.parentFile!!.listFiles()!!.filter { it.extension == "part" }).isEmpty()
    }

    @Test fun `cancelled import propagates cancellation and removes staging file`() = runTest {
        val originalSize = seedInstalledModel()
        var cancelled = false
        try {
            store.install(modelStream(8192)) { throw CancellationException("cancel import") }
        } catch (_: CancellationException) { cancelled = true }
        assertThat(cancelled).isTrue()
        assertThat(store.isModelPresent()).isTrue()
        assertThat(store.modelFile.length()).isEqualTo(originalSize)
        assertThat(store.modelFile.parentFile!!.listFiles()!!.filter { it.extension == "part" }).isEmpty()
    }

    private lateinit var store: ModelStore

    private val ggufHeader = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    @Before
    fun setUp() {
        store = ModelStore(ApplicationProvider.getApplicationContext())
        store.delete()
    }

    private fun modelStream(size: Long, header: ByteArray = ggufHeader): InputStream =
        object : InputStream() {
            private var position = 0L
            override fun read(): Int {
                if (position >= size) return -1
                val value = if (position < header.size) header[position.toInt()].toInt() and 255 else 0
                position++
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position >= size) return -1
                val count = minOf(length.toLong(), size - position).toInt()
                buffer.fill(0, offset, offset + count)
                for (index in header.indices) {
                    if (index.toLong() >= position && index.toLong() < position + count) {
                        buffer[offset + (index - position).toInt()] = header[index]
                    }
                }
                position += count
                return count
            }
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
        val result = store.install(modelStream(size))
        assertThat(result.isSuccess).isTrue()
        assertThat(store.isModelPresent()).isTrue()
    }

    @Test
    fun `an interrupted transfer is refused rather than half installed`() = runTest {
        val result = store.install(modelStream(5L * 1024 * 1024))
        assertThat(result.isFailure).isTrue()
        assertThat(store.isModelPresent()).isFalse()
        // And no partial file is left behind for the next launch to pick up.
        assertThat(store.modelFile.exists()).isFalse()
    }

    @Test
    fun `a file that is not gguf is refused`() = runTest {
        val size = ModelStore.MIN_PLAUSIBLE_BYTES + 1_024
        val notAModel = modelStream(size, header = byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // a zip
        val result = store.install(notAModel)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("GGUF")
        assertThat(store.isModelPresent()).isFalse()
    }

    @Test
    fun `install reports progress`() = runTest {
        val seen = mutableListOf<Long>()
        store.install(modelStream(ModelStore.MIN_PLAUSIBLE_BYTES + 1_024)) {
            seen += it
        }
        assertThat(seen).isNotEmpty()
        assertThat(seen).isInOrder()
        assertThat(seen.last()).isGreaterThan(ModelStore.MIN_PLAUSIBLE_BYTES)
    }

    @Test
    fun `installing over an existing model replaces it`() = runTest {
        store.install(modelStream(ModelStore.MIN_PLAUSIBLE_BYTES + 1_024))
        val first = store.modelFile.length()
        store.install(modelStream(ModelStore.MIN_PLAUSIBLE_BYTES + 8_192))
        assertThat(store.modelFile.length()).isNotEqualTo(first)
        assertThat(store.isModelPresent()).isTrue()
    }

    @Test
    fun `delete removes the model`() = runTest {
        store.install(modelStream(ModelStore.MIN_PLAUSIBLE_BYTES + 1_024))
        assertThat(store.delete()).isTrue()
        assertThat(store.isModelPresent()).isFalse()
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
