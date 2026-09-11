package org.jaagruk.ai

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Finds the model file, and never copies it.
 *
 * ## Why the model is not an APK asset
 *
 * Gemma 3 1B at Q4_K_M is around 769 MiB. Bundling it would:
 *
 *  * end the 27 MB download this app is built around, and put it past what a worker on a metered
 *    connection at a mine gate will ever fetch;
 *  * cost a second 769 MiB, because an asset has to be extracted to a real path before llama.cpp can
 *    mmap it — a 1 GB footprint on a handset that has 8 GB of user storage;
 *  * make first launch a multi-minute copy on a device that was working fine a moment earlier.
 *
 * So the file arrives out of band: a supervisor sideloads it once per handset, or it travels the same
 * Nearby relay path the app already uses to get records out of a shaft. llama.cpp then mmaps it in
 * place, so the pages are read as they are touched and nothing is duplicated.
 *
 * Every feature that uses it works without it. That is the same contract the app already has with
 * `gesture_recognizer.task` and the ARCore Cloud Anchor key: absent, the affordance is hidden and the
 * reason is stated.
 */
class ModelStore(private val context: Context) {

    companion object {
        private const val TAG = "JaagrukLlm"

        /**
         * The expected file name.
         *
         * Fixed rather than discovered so a half-copied file from another project cannot be picked up
         * and loaded as a model.
         */
        const val MODEL_FILE_NAME: String = "gemma-3-1b-it-q4_k_m.gguf"

        private const val MODELS_DIR = "models"

        /** GGUF magic. Checked so a truncated or wrong file is refused before llama.cpp sees it. */
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

        /**
         * Floor for a plausible Q4 1B model.
         *
         * A file that passes the magic check but is 3 MB is an interrupted transfer. Loading it
         * produces a native-side failure that reads as a broken app rather than a broken download.
         */
        const val MIN_PLAUSIBLE_BYTES: Long = 200L * 1024 * 1024

        /**
         * Device memory floor.
         *
         * Weights plus KV cache for a 1B model at Q4 sit around 900 MiB: 769 MiB of weights plus the KV cache. On a 3 GB
         * handset that competes with the camera pipeline and the OS, and the allocator resolves the
         * competition by killing something. 4 GB is where holding it becomes reasonable, and the
         * check is on total RAM rather than free RAM because free RAM at the moment of asking says
         * nothing about free RAM once an AR session has started.
         */
        const val MIN_DEVICE_MEMORY_BYTES: Long = 3_500L * 1024 * 1024
    }

    /** Where the model is expected. App-private, so no storage permission is involved. */
    val modelFile: File
        get() = File(File(context.filesDir, MODELS_DIR).apply { mkdirs() }, MODEL_FILE_NAME)

    val modelPath: String get() = modelFile.absolutePath

    /** True when a file is present that is plausibly a GGUF model. */
    fun isModelPresent(): Boolean {
        val file = modelFile
        if (!file.isFile) return false
        if (file.length() < MIN_PLAUSIBLE_BYTES) {
            Log.w(TAG, "model file is ${file.length()} bytes, below the plausibility floor")
            return false
        }
        return hasGgufMagic(file)
    }

    private fun hasGgufMagic(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            stream.read(header) == 4 && header.contentEquals(GGUF_MAGIC)
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the model header", e)
        false
    }

    /** Total device RAM, or 0 when it cannot be determined. */
    fun deviceMemoryBytes(): Long = try {
        val info = android.app.ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.getMemoryInfo(info)
        info.totalMem
    } catch (e: Exception) {
        Log.w(TAG, "could not read device memory", e)
        0L
    }

    fun hasEnoughMemory(): Boolean {
        val total = deviceMemoryBytes()
        // Unknown memory is treated as enough rather than as a block: refusing on a device whose
        // total RAM could not be read would disable the feature on the strength of a failed query.
        return total == 0L || total >= MIN_DEVICE_MEMORY_BYTES
    }

    /**
     * How many threads to give inference.
     *
     * Half the cores, at least two, at most four. Using every core makes the handset hot and
     * unresponsive, and a worker holding a phone that has become uncomfortably warm puts it down.
     */
    fun recommendedThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /**
     * Installs a model from a stream — a supervisor picking a file, or a Nearby transfer.
     *
     * Writes to a temporary path and renames on success, so an interrupted install cannot leave a
     * partial file that passes the presence check. This is the one place a copy happens, and it
     * happens once per handset rather than on every launch.
     */
    suspend fun install(source: InputStream, onProgress: (Long) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            val destination = modelFile
            val temporary = File("${destination.absolutePath}.part")
            try {
                if (temporary.exists()) temporary.delete()
                var written = 0L
                source.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(4 * 1024 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written)
                        }
                        output.flush()
                    }
                }
                if (written < MIN_PLAUSIBLE_BYTES) {
                    temporary.delete()
                    return@withContext Result.failure(
                        IllegalStateException(
                            "transfer ended at $written bytes, below the $MIN_PLAUSIBLE_BYTES floor",
                        ),
                    )
                }
                if (!hasGgufMagic(temporary)) {
                    temporary.delete()
                    return@withContext Result.failure(
                        IllegalStateException("the file is not in GGUF format"),
                    )
                }
                if (destination.exists()) destination.delete()
                if (!temporary.renameTo(destination)) {
                    temporary.delete()
                    return@withContext Result.failure(
                        IllegalStateException("could not move the model into place"),
                    )
                }
                Log.i(TAG, "model installed, ${destination.length()} bytes")
                Result.success(destination)
            } catch (e: Exception) {
                Log.e(TAG, "model install failed", e)
                temporary.delete()
                Result.failure(e)
            }
        }

    fun delete(): Boolean = modelFile.delete()

    /** Human-readable diagnostics for the supervisor screen. */
    fun describe(): String = buildString {
        append("path=").append(modelPath)
        append(" present=").append(isModelPresent())
        append(" bytes=").append(if (modelFile.isFile) modelFile.length() else 0L)
        append(" ram=").append(deviceMemoryBytes() / (1024 * 1024)).append("MB")
        append(" abi=").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        append(" threads=").append(recommendedThreads())
    }
}
