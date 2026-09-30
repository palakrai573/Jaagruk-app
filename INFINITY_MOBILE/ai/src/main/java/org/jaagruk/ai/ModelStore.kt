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

    /**
     * Where a supervisor may drop a model file for the app to pick up.
     *
     * `filesDir` is the right place for the model to *live* — it cannot be unmounted mid-session, so
     * an mmap of it stays valid — but nothing outside the app can write there. That makes it a bad
     * place to ask somebody to put a file: on a release build it needs root, and on a debug build it
     * needs a three-command `adb run-as` dance.
     *
     * App-specific external storage needs no permission on any supported API level, is writable by
     * `adb push`, and is visible to an on-device file manager. So the file is dropped here and
     * [importIfPending] moves it into place on the next launch.
     *
     * Null when external storage is genuinely unavailable, which is not an error — it just means the
     * only route left is the in-app picker calling [install].
     */
    fun dropDirectory(): File? =
        context.getExternalFilesDir(MODELS_DIR)?.apply { mkdirs() }

    /**
     * A file waiting in the drop directory, or null.
     *
     * Any `.gguf` is accepted rather than only [MODEL_FILE_NAME], because the published Hugging Face
     * artefact is `gemma-3-1b-it-Q4_K_M.gguf` — different case from the name used here — and Android
     * filesystems are case sensitive. Requiring an exact match would fail for the one file almost
     * everybody will actually download.
     *
     * More than one candidate is refused rather than guessed at: picking the newest or the largest
     * would silently load a model nobody chose, and which model produced an answer is not a detail to
     * be vague about.
     */
    fun pendingImport(): File? {
        val directory = dropDirectory() ?: return null
        val candidates = directory.listFiles { f: File ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true)
        }?.toList().orEmpty()

        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.single()
            else -> candidates.firstOrNull { it.name.equals(MODEL_FILE_NAME, ignoreCase = true) }
                ?: run {
                    Log.w(
                        TAG,
                        "${candidates.size} .gguf files in the drop directory and none named " +
                            "$MODEL_FILE_NAME; refusing to guess between " +
                            candidates.joinToString { it.name },
                    )
                    null
                }
        }
    }

    /**
     * Moves a dropped file into place, if one is waiting.
     *
     * Returns null when there was nothing to do, so a caller can tell "no import attempted" apart
     * from "an import failed" — the second needs to be shown to somebody and the first does not.
     *
     * A rename is tried first. On most handsets the emulated external volume and `filesDir` share a
     * partition, so this is instant for a 769 MiB file. When it is not, the stream copy in [install]
     * runs, which validates as it goes and leaves nothing partial behind.
     */
    suspend fun importIfPending(onProgress: (Long) -> Unit = {}): Result<File>? {
        val source = pendingImport() ?: return null

        if (source.length() < MIN_PLAUSIBLE_BYTES) {
            Log.w(TAG, "dropped file ${source.name} is ${source.length()} bytes, below the floor")
            return Result.failure(
                IllegalStateException(
                    "${source.name} is only ${source.length() / (1024 * 1024)} MB, which is too " +
                        "small to be a model. The download was probably interrupted.",
                ),
            )
        }
        if (!hasGgufMagic(source)) {
            Log.w(TAG, "dropped file ${source.name} is not GGUF")
            return Result.failure(
                IllegalStateException("${source.name} is not in GGUF format."),
            )
        }

        Log.i(TAG, "importing ${source.name}, ${source.length()} bytes")
        val destination = modelFile
        if (destination.exists()) destination.delete()

        if (source.renameTo(destination)) {
            Log.i(TAG, "imported by rename, ${destination.length()} bytes")
            onProgress(destination.length())
            return Result.success(destination)
        }

        Log.i(TAG, "rename failed - different volume - falling back to a copy")
        return withContext(Dispatchers.IO) {
            source.inputStream().use { stream -> install(stream, onProgress) }
        }.onSuccess {
            // Only removed once the model is safely in place, so a failed copy leaves the dropped
            // file where it was and the next launch can retry.
            if (source.delete()) Log.i(TAG, "removed the dropped file after a successful copy")
        }
    }

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

    /**
     * The bundled model asset's file name, or null when this build does not carry one.
     *
     * Only the `bundled` product flavour packages a model. The `lean` flavour returns null here and
     * falls through to the other rungs of the ladder, which is why this is a lookup rather than a
     * build-config constant: the same code path serves both flavours.
     */
    fun bundledAssetName(): String? = runCatching {
        context.assets.list(MODELS_DIR)?.firstOrNull { it.endsWith(".gguf", ignoreCase = true) }
    }.getOrNull()

    /** Uncompressed size of the bundled asset, or -1 when it cannot be determined. */
    private fun bundledAssetBytes(name: String): Long = runCatching {
        context.assets.openFd("$MODELS_DIR/$name").use { it.length }
    }.getOrDefault(-1L)

    /**
     * Extracts a model that shipped inside the APK.
     *
     * Returns null when this build has no bundled model, so a caller can tell "nothing to extract"
     * apart from "extraction failed".
     *
     * ## The cost, stated plainly
     *
     * An APK asset cannot be memory-mapped: llama.cpp needs a real path, and an asset lives inside
     * the APK's zip. So a bundled model is paid for twice — once compressed in the APK and once
     * extracted here — roughly 1.6 GB of device storage for a 769 MiB model. `noCompress` keeps the
     * APK copy stored rather than deflated, which makes this a straight read instead of an inflate,
     * but it cannot avoid the copy.
     *
     * That is the price of "install it and the model is already there" with no Play dependency and no
     * network. The `lean` flavour plus a fast-follow asset pack pays 769 MiB once instead, and is the
     * right choice wherever Play is the delivery channel.
     */
    suspend fun installFromBundledAsset(onProgress: (Long) -> Unit = {}): Result<File>? {
        val name = bundledAssetName() ?: return null
        Log.i(TAG, "extracting the bundled model asset $name")
        return runCatching { context.assets.open("$MODELS_DIR/$name") }
            .fold(
                onSuccess = { stream -> stream.use { install(it, onProgress) } },
                onFailure = { error ->
                    Log.e(TAG, "could not open the bundled model asset", error)
                    Result.failure(error)
                },
            )
    }

    /** Where a model came from. Recorded so a support question has a factual answer. */
    enum class ModelSource {
        /** Already in place from an earlier launch. The usual case. */
        RESIDENT,

        /** Moved in from the drop directory — adb push, a file manager, or a USB stick. */
        DROP_DIRECTORY,

        /** Extracted from an asset that shipped inside the APK. */
        BUNDLED_ASSET,
    }

    /**
     * Makes the model available from whichever source can provide it.
     *
     * Tried cheapest first, so the overwhelmingly common case — already installed — costs a stat call
     * and nothing else:
     *
     *  1. **Resident.** Already at [modelFile].
     *  2. **Drop directory.** A file pushed to app-specific external storage, moved into place.
     *  3. **Bundled asset.** Shipped inside the APK by the `bundled` flavour, extracted once.
     *
     * A failure at rung 2 or 3 is returned rather than swallowed, because a truncated download or a
     * corrupt asset is something somebody has to be told about. Finding no source at all is also a
     * failure, but a different one, with a message naming the drop directory.
     *
     * @param onProgress written and total bytes during a copy. Total is -1 when it is not knowable.
     */
    suspend fun ensureAvailable(
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<ModelSource> {
        if (isModelPresent()) return Result.success(ModelSource.RESIDENT)

        pendingImport()?.let { dropped ->
            val total = dropped.length()
            importIfPending { written -> onProgress(written, total) }?.let { result ->
                return result.map { ModelSource.DROP_DIRECTORY }
            }
        }

        bundledAssetName()?.let { name ->
            val total = bundledAssetBytes(name)
            installFromBundledAsset { written -> onProgress(written, total) }?.let { result ->
                return result.map { ModelSource.BUNDLED_ASSET }
            }
        }

        return Result.failure(IllegalStateException(missingMessage()))
    }

    /** What to tell somebody when no source has a model. Names a path they can actually write to. */
    fun missingMessage(): String {
        val drop = dropDirectory()?.absolutePath
        return if (drop != null) {
            "No model installed. Copy $MODEL_FILE_NAME to $drop and reopen the app."
        } else {
            "No model installed, and external storage is unavailable for a manual copy."
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
