package org.jaagruk.safety.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Local storage for hazard photos and voice notes before they upload.
 *
 * Everything lives in app-private storage — no `MediaStore`, no gallery, no shared collection. A
 * near-miss photo can show a colleague's face, a vehicle plate, or an unsafe act that is going to
 * be discussed with a supervisor. Putting it in the device gallery would make it visible to every
 * app with read permission and, in practice, to whoever picks the shared site phone up next.
 *
 * Files are kept after upload only until the record is confirmed ingested, then deleted by
 * [deleteFor]. That is what stops a shared handset accumulating a year of photographs.
 */
class LocalMediaStore(context: Context) {

    private val photoDir = File(context.filesDir, "hazard_photos").apply { mkdirs() }
    private val voiceDir = File(context.filesDir, "hazard_voice").apply { mkdirs() }

    /** Hard cap on total media held locally. Beyond it, the oldest uploaded files are pruned. */
    private val budgetBytes = 64L * 1024L * 1024L

    fun newPhotoFile(hazardId: String): File = File(photoDir, "$hazardId.jpg")

    fun newVoiceFile(hazardId: String): File = File(voiceDir, "$hazardId.m4a")

    /** A scratch file for a recording that may be discarded before a hazard id exists. */
    fun scratchVoiceFile(): File = File(voiceDir, "scratch-${UUID.randomUUID()}.m4a")

    /**
     * A scratch file for a photo the camera is about to write, before a hazard id exists.
     *
     * Inside [photoDir] rather than the cache directory on purpose: that is one of the two paths
     * `file_paths.xml` lets the `FileProvider` expose, so the camera app can be handed a content URI
     * for it. A photo in the cache directory could not be shared with the camera without widening
     * the provider, and a near-miss photo can show a colleague's face.
     */
    fun scratchPhotoFile(): File = File(photoDir, "scratch-${UUID.randomUUID()}.jpg")

    fun resolve(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        // Refuse anything outside the two managed directories. A path arriving from a stored row
        // should always be ours, and treating it as arbitrary would turn a corrupted row into an
        // upload of an unrelated file.
        val parent = file.parentFile?.canonicalFile ?: return null
        val allowed = parent == photoDir.canonicalFile || parent == voiceDir.canonicalFile
        return if (allowed && file.exists()) file else null
    }

    fun deleteFor(hazardId: String) {
        listOf(newPhotoFile(hazardId), newVoiceFile(hazardId)).forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "could not delete ${file.name}")
            }
        }
    }

    fun totalBytes(): Long = sequenceOf(photoDir, voiceDir)
        .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
        .sumOf { it.length() }

    /**
     * Frees space by deleting the oldest files whose hazard is already uploaded.
     *
     * [uploadedHazardIds] is supplied by the caller rather than queried here, because deciding what
     * is safe to delete is a database question and this class must never be able to destroy
     * evidence for a record that has not reached the server.
     */
    fun pruneTo(uploadedHazardIds: Set<String>) {
        if (totalBytes() <= budgetBytes) return

        val deletable = sequenceOf(photoDir, voiceDir)
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.nameWithoutExtension in uploadedHazardIds }
            .sortedBy { it.lastModified() }
            .toList()

        var freed = 0L
        val target = totalBytes() - budgetBytes
        for (file in deletable) {
            if (freed >= target) break
            val size = file.length()
            if (file.delete()) freed += size
        }
        Log.i(TAG, "pruned ${freed / 1024} kB of uploaded hazard media")
    }

    /**
     * Deletes scratch media left behind by a cancelled hazard report.
     *
     * Both directories. A scratch file is never named after a hazard id, so [pruneTo] will never
     * consider it deletable however full the handset gets — which means anything missed here stays
     * on the device indefinitely.
     */
    fun clearScratch() {
        sequenceOf(photoDir, voiceDir)
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.name.startsWith("scratch-") }
            .forEach { runCatching { it.delete() } }
    }

    @Throws(IOException::class)
    fun ensureWritable() {
        if (!photoDir.exists() && !photoDir.mkdirs()) {
            throw IOException("cannot create the hazard photo directory")
        }
        if (!voiceDir.exists() && !voiceDir.mkdirs()) {
            throw IOException("cannot create the hazard voice-note directory")
        }
    }

    private companion object {
        const val TAG = "LocalMediaStore"
    }
}
