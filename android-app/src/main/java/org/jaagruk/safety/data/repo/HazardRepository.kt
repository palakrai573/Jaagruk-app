package org.jaagruk.safety.data.repo

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.LocalMediaStore
import org.jaagruk.safety.data.db.HazardTagEntity
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.hazard.HazardCategory
import org.jaagruk.safety.data.hazard.HazardSeverity
import org.jaagruk.safety.sync.SyncKind
import java.io.File
import java.util.UUID

/**
 * Near-miss and hazard reporting.
 *
 * The design constraint that shaped this: a worker reports a hazard *while standing in front of it*,
 * which is the moment there is least likely to be a network. So a report is complete and durable the
 * instant it is stored locally, and the upload is a separate concern that may happen hours later,
 * possibly relayed through a supervisor's handset rather than over the internet at all.
 *
 * Text and media sync independently. A 900 kB photo on a 2G uplink must not hold up the one line of
 * text saying an exit is blocked — the text is what a site officer acts on, and the photo is
 * corroboration. `mediaPending` tracks the second half so it retries on its own schedule.
 */
class HazardRepository(
    private val database: JaagrukDatabase,
    private val media: LocalMediaStore,
    private val clock: WallClock,
) {

    private val hazards = database.hazardTagDao()
    private val queue = database.syncQueueDao()

    fun observeForSite(siteId: String): Flow<List<HazardTagEntity>> =
        hazards.observeForSite(siteId)

    suspend fun find(hazardId: String): HazardTagEntity? = hazards.find(hazardId)

    suspend fun pendingUpload(limit: Int): List<HazardTagEntity> = hazards.pendingUpload(limit)

    suspend fun pendingMedia(limit: Int): List<HazardTagEntity> = hazards.pendingMedia(limit)

    suspend fun markUploaded(ids: List<String>, mediaPending: Boolean) =
        hazards.markUploaded(ids, mediaPending)

    suspend fun attachMedia(hazardId: String, photoMediaId: String?, voiceMediaId: String?) {
        hazards.attachMedia(hazardId, photoMediaId, voiceMediaId)
        // Once the server holds the media, the local copy is redundant. Deleting it is what keeps a
        // shared site handset from accumulating a year of photographs of colleagues.
        media.deleteFor(hazardId)
    }

    /** Outcome of a report attempt. Every rejection is explainable to the worker. */
    sealed interface ReportResult {
        data class Filed(val hazard: HazardTagEntity) : ReportResult

        /**
         * Too many reports from one worker in the rate-limit window.
         *
         * Not a punishment: it catches a phone in a pocket triggering the report button, and a
         * frustrated worker double-filing the same thing five times. The limit is generous enough
         * that genuine reporting is never blocked.
         */
        data class RateLimited(val secondsUntilNext: Long, val recentCount: Int) : ReportResult

        data class Invalid(val reason: String) : ReportResult
    }

    /**
     * Files a hazard report.
     *
     * Position is optional and frequently absent — there is no GPS fix underground, which is exactly
     * why [zoneLabel] and [arAnchorId] exist. A report with neither is still accepted: "blocked exit,
     * somewhere on this site" is worth having, and refusing it because a satellite was not visible
     * would train workers to stop reporting.
     */
    suspend fun report(
        siteId: String,
        reporterWorkerId: String?,
        category: HazardCategory,
        severity: HazardSeverity,
        note: String?,
        latitude: Double?,
        longitude: Double?,
        zoneLabel: String?,
        arAnchorId: String?,
        photo: File?,
        voiceNote: File?,
    ): ReportResult {
        if (siteId.isBlank()) return ReportResult.Invalid("this device is not enrolled to a site")

        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedNote != null && trimmedNote.length > MAX_NOTE_LENGTH) {
            return ReportResult.Invalid("the note is longer than $MAX_NOTE_LENGTH characters")
        }
        if (latitude != null && latitude !in -90.0..90.0) {
            return ReportResult.Invalid("latitude is out of range")
        }
        if (longitude != null && longitude !in -180.0..180.0) {
            return ReportResult.Invalid("longitude is out of range")
        }

        val nowSec = clock.epochSeconds()

        if (reporterWorkerId != null) {
            val recent = hazards.countRecentByWorker(
                workerId = reporterWorkerId,
                sinceSec = nowSec - RATE_LIMIT_WINDOW_SEC,
            )
            if (recent >= RATE_LIMIT_MAX_REPORTS) {
                return ReportResult.RateLimited(
                    secondsUntilNext = RATE_LIMIT_WINDOW_SEC,
                    recentCount = recent,
                )
            }
        }

        val hazardId = UUID.randomUUID().toString()

        // Media is moved into managed storage before the row is written, so a row can never point at
        // a file that is not there. The reverse — a file with no row — is harmless and gets pruned.
        val photoPath = photo?.let { source ->
            moveInto(source, media.newPhotoFile(hazardId))
        }
        val voicePath = voiceNote?.let { source ->
            moveInto(source, media.newVoiceFile(hazardId))
        }

        val entity = HazardTagEntity(
            hazardId = hazardId,
            siteId = siteId,
            reporterWorkerId = reporterWorkerId,
            category = category.wireName,
            severity = severity.wireName,
            note = trimmedNote,
            latitude = latitude,
            longitude = longitude,
            zoneLabel = zoneLabel?.trim()?.takeIf { it.isNotEmpty() },
            arAnchorId = arAnchorId,
            photoPath = photoPath,
            voiceNotePath = voicePath,
            photoMediaId = null,
            voiceMediaId = null,
            createdAtSec = nowSec,
            uploaded = false,
            mediaPending = photoPath != null || voicePath != null,
        )

        database.withTransaction {
            hazards.insert(entity)
            queue.enqueue(
                SyncKind.HAZARD.queueEntry(
                    refId = hazardId,
                    // The hazard id is generated here and never reused, so it is already a stable
                    // idempotency key across retries and across a Nearby relay.
                    idempotencyKey = "hazard:$hazardId",
                    payloadJson = SyncKind.hazardPayload(hazardId),
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }

        return ReportResult.Filed(entity)
    }

    /**
     * True when a report is severe enough to try relaying immediately.
     *
     * Waiting for the next sync window is fine for a damaged glove. It is not fine for a blocked
     * escape route found at the start of a night shift.
     */
    fun warrantsImmediateRelay(severity: HazardSeverity): Boolean =
        severity.rank >= HazardSeverity.URGENT_THRESHOLD.rank

    /**
     * Frees local media for hazards the server holds in full.
     *
     * The safe set is computed here, from upload state, and handed to the media store. The media
     * store deliberately cannot make this decision itself — it has no view of what has synced, and a
     * class that can delete evidence should not also be the one deciding when.
     */
    suspend fun pruneUploadedMedia() {
        val safeToDelete = hazards.fullyUploadedIds().toSet()
        if (safeToDelete.isEmpty()) return
        media.pruneTo(safeToDelete)
        media.clearScratch()
    }

    private fun moveInto(source: File, destination: File): String? = try {
        if (!source.exists()) {
            null
        } else if (source.canonicalPath == destination.canonicalPath) {
            destination.absolutePath
        } else {
            destination.parentFile?.mkdirs()
            if (source.renameTo(destination)) {
                destination.absolutePath
            } else {
                // renameTo fails across storage volumes. Copy and delete rather than losing the file.
                source.copyTo(destination, overwrite = true)
                source.delete()
                destination.absolutePath
            }
        }
    } catch (e: Exception) {
        // A hazard report without its photo is still a hazard report. Losing the text because the
        // camera wrote to a directory that has since gone away would be the worse failure.
        Log.w(TAG, "could not store hazard media ${source.name}", e)
        null
    }

    companion object {
        private const val TAG = "HazardRepository"

        const val MAX_NOTE_LENGTH: Int = 2_000

        /** Rate-limit window and cap. Generous: this catches malfunction, not enthusiasm. */
        const val RATE_LIMIT_WINDOW_SEC: Long = 10 * 60L
        const val RATE_LIMIT_MAX_REPORTS: Int = 12
    }
}
