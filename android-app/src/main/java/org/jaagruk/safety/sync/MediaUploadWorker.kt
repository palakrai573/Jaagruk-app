package org.jaagruk.safety.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.jaagruk.safety.data.LocalMediaStore
import org.jaagruk.safety.data.repo.HazardRepository
import org.jaagruk.safety.sync.api.JaagrukApi
import org.jaagruk.safety.sync.api.SessionStore
import java.io.File
import java.io.IOException

/**
 * Uploads hazard photos and voice notes, separately from the text records.
 *
 * Split from [SyncWorker] on purpose. A hazard report is two things with very different urgency: one
 * line of text saying an escape route is blocked, and a 900 kB photograph corroborating it. On a 2G
 * uplink at a mine portal, bundling them means the sentence an officer needs to act on waits behind
 * the picture. So the text goes out on any connection and the media waits for an unmetered one.
 *
 * The media is only ever deleted once the server has confirmed it. A photo pruned before upload
 * cannot be retaken — the hazard has usually been fixed, or the shift has ended.
 */
@HiltWorker
class MediaUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: JaagrukApi,
    private val session: SessionStore,
    private val hazards: HazardRepository,
    private val media: LocalMediaStore,
    private val connectivity: ConnectivityObserver,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!session.isAuthenticated) return Result.success()

        val quality = connectivity.current()
        if (!quality.isOnline) return Result.success()
        if (!quality.allowsLargeUploads) {
            // Deliberately not a failure. The text record has already synced; the media waits for
            // Wi-Fi rather than spending a worker's mobile data on a photograph.
            Log.i(TAG, "holding media uploads for an unmetered connection")
            return Result.success()
        }

        val pending = hazards.pendingMedia(BATCH_SIZE)
        if (pending.isEmpty()) {
            hazards.pruneUploadedMedia()
            return Result.success()
        }

        var deferred = false

        for (hazard in pending) {
            val photo = media.resolve(hazard.photoPath)
            val voice = media.resolve(hazard.voiceNotePath)

            if (photo == null && voice == null) {
                // The row claims media that is not on disk — a manual cache clear, or a failed move.
                // Clearing the flag is right: there is nothing left to send, and leaving it set would
                // retry forever.
                hazards.attachMedia(hazard.hazardId, hazard.photoMediaId, hazard.voiceMediaId)
                continue
            }

            val photoId = photo?.let { upload(it, KIND_PHOTO, hazard.siteId) }
            val voiceId = voice?.let { upload(it, KIND_VOICE, hazard.siteId) }

            val photoDone = photo == null || photoId != null
            val voiceDone = voice == null || voiceId != null

            if (photoDone && voiceDone) {
                hazards.attachMedia(
                    hazardId = hazard.hazardId,
                    photoMediaId = photoId ?: hazard.photoMediaId,
                    voiceMediaId = voiceId ?: hazard.voiceMediaId,
                )
            } else {
                // Partial success is kept. Retrying only the missing half on the next pass is the
                // difference between one more 40 kB voice note and re-sending the whole photo.
                deferred = true
                if (photoId != null || voiceId != null) {
                    Log.i(TAG, "partially uploaded media for ${hazard.hazardId}; will finish later")
                }
            }
        }

        hazards.pruneUploadedMedia()
        return if (deferred) Result.retry() else Result.success()
    }

    /** @return the server media id, or null when the upload should be retried. */
    private suspend fun upload(file: File, kind: String, siteId: String): String? = try {
        if (file.length() > MAX_UPLOAD_BYTES) {
            // Refused locally rather than sent and rejected. Capturing at a smaller resolution is a
            // camera-configuration fix, and the log line is what points at it.
            Log.w(TAG, "${file.name} is ${file.length()} bytes, over the upload cap; skipping")
            null
        } else {
            val mediaType = if (kind == KIND_PHOTO) MIME_JPEG else MIME_M4A
            val part = MultipartBody.Part.createFormData(
                "file",
                file.name,
                file.asRequestBody(mediaType.toMediaType()),
            )
            val response = api.uploadMedia(kind = kind, siteId = siteId, file = part)
            if (response.isSuccessful) {
                response.body()?.id
            } else {
                Log.i(TAG, "media upload for ${file.name} returned ${response.code()}")
                null
            }
        }
    } catch (e: IOException) {
        Log.i(TAG, "media upload deferred: no usable connection")
        null
    } catch (e: Exception) {
        Log.w(TAG, "media upload failed for ${file.name}", e)
        null
    }

    companion object {
        private const val TAG = "MediaUploadWorker"

        const val UNIQUE_WORK_NAME: String = "jaagruk-media-upload"

        private const val BATCH_SIZE = 8

        /** Matches the server's media size limit. */
        private const val MAX_UPLOAD_BYTES = 8L * 1024L * 1024L

        private const val KIND_PHOTO = "photo"
        private const val KIND_VOICE = "voice"
        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_M4A = "audio/mp4"
    }
}
