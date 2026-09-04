package org.jaagruk.safety.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access.
 *
 * Reads that drive the UI return `Flow`, so a screen updates when a background sync writes rather
 * than needing a manual refresh. Writes are `suspend` and every multi-statement write is a
 * `@Transaction`, because the interesting failure here is a half-applied certificate issuance.
 */

@Dao
interface WorkerDao {

    @Query("SELECT * FROM workers WHERE workerId = :workerId")
    suspend fun find(workerId: String): WorkerEntity?

    @Query("SELECT * FROM workers WHERE workerId = :workerId")
    fun observe(workerId: String): Flow<WorkerEntity?>

    @Query("SELECT * FROM workers WHERE siteId = :siteId AND active = 1 ORDER BY fullName")
    fun observeForSite(siteId: String): Flow<List<WorkerEntity>>

    @Query("SELECT * FROM workers WHERE active = 1 ORDER BY fullName")
    suspend fun all(): List<WorkerEntity>

    @Query("SELECT * FROM workers WHERE workerIdHash = :hashHex LIMIT 1")
    suspend fun findByHash(hashHex: String): WorkerEntity?

    @Upsert
    suspend fun upsert(worker: WorkerEntity)

    @Upsert
    suspend fun upsertAll(workers: List<WorkerEntity>)

    @Query(
        """
        UPDATE workers
        SET failedPinAttempts = :attempts,
            lockedUntilEpochMs = :lockedUntilEpochMs,
            lockedUntilElapsedMs = :lockedUntilElapsedMs
        WHERE workerId = :workerId
        """,
    )
    suspend fun updateLockout(
        workerId: String,
        attempts: Int,
        lockedUntilEpochMs: Long,
        lockedUntilElapsedMs: Long,
    )

    @Query(
        """
        UPDATE workers
        SET pinHash = :pinHash, pinSalt = :pinSalt, failedPinAttempts = 0,
            lockedUntilEpochMs = 0, lockedUntilElapsedMs = 0
        WHERE workerId = :workerId
        """,
    )
    suspend fun setPin(workerId: String, pinHash: String, pinSalt: String)

    @Query("SELECT COUNT(*) FROM workers WHERE pinHash IS NOT NULL")
    suspend fun countWithPin(): Int

    /**
     * Workers enrolled on this handset that the server has never seen.
     *
     * Oldest first, so a site that enrolled a shift's worth of contractors offline reconciles in the
     * order they actually arrived. The PIN is deliberately not part of this: it is a local secret and
     * has no business travelling to the server.
     */
    @Query("SELECT * FROM workers WHERE serverSynced = 0 ORDER BY registeredAtSec LIMIT :limit")
    suspend fun notYetOnServer(limit: Int): List<WorkerEntity>

    @Query("SELECT COUNT(*) FROM workers WHERE serverSynced = 0")
    suspend fun countNotYetOnServer(): Int

    /**
     * Marks a worker as present on the server.
     *
     * Only flips the flag. Rewriting the whole row would clobber a PIN the worker set between the
     * upload starting and this returning.
     */
    @Query("UPDATE workers SET serverSynced = 1 WHERE workerId = :workerId")
    suspend fun markServerSynced(workerId: String)
}

@Dao
interface SiteDao {

    @Query("SELECT * FROM sites WHERE siteId = :siteId")
    suspend fun find(siteId: String): SiteEntity?

    @Query("SELECT * FROM sites WHERE siteId = :siteId")
    fun observe(siteId: String): Flow<SiteEntity?>

    @Query("SELECT * FROM sites ORDER BY siteId")
    fun observeAll(): Flow<List<SiteEntity>>

    @Upsert
    suspend fun upsert(site: SiteEntity)

    @Query("UPDATE sites SET arScanned = :scanned, anchorCount = :anchorCount WHERE siteId = :siteId")
    suspend fun updateScanState(siteId: String, scanned: Boolean, anchorCount: Int)

    @Query("UPDATE sites SET publicKeyHex = :publicKeyHex, keyEpoch = :epoch WHERE siteId = :siteId")
    suspend fun updateKey(siteId: String, publicKeyHex: String, epoch: Int)
}

@Dao
interface SiteAnchorDao {

    @Query("SELECT * FROM site_anchors WHERE siteId = :siteId")
    suspend fun forSite(siteId: String): List<SiteAnchorEntity>

    @Query("SELECT * FROM site_anchors WHERE siteId = :siteId")
    fun observeForSite(siteId: String): Flow<List<SiteAnchorEntity>>

    @Query("SELECT * FROM site_anchors WHERE siteId = :siteId AND targetKey = :targetKey LIMIT 1")
    suspend fun find(siteId: String, targetKey: String): SiteAnchorEntity?

    @Upsert
    suspend fun upsert(anchor: SiteAnchorEntity)

    @Delete
    suspend fun delete(anchor: SiteAnchorEntity)

    @Query("DELETE FROM site_anchors WHERE siteId = :siteId")
    suspend fun clearSite(siteId: String)

    @Query("SELECT COUNT(*) FROM site_anchors WHERE siteId = :siteId")
    suspend fun countForSite(siteId: String): Int

    @Query(
        """
        UPDATE site_anchors
        SET resolveFailureCount = resolveFailureCount + 1
        WHERE anchorId = :anchorId
        """,
    )
    suspend fun recordResolveFailure(anchorId: String)

    @Query("UPDATE site_anchors SET lastResolvedAtSec = :atSec, resolveFailureCount = 0 WHERE anchorId = :anchorId")
    suspend fun recordResolveSuccess(anchorId: String, atSec: Long)
}

@Dao
interface ModuleDao {

    @Query("SELECT * FROM modules WHERE enabled = 1 ORDER BY moduleCode")
    fun observeEnabled(): Flow<List<ModuleEntity>>

    @Query("SELECT * FROM modules ORDER BY moduleCode")
    suspend fun all(): List<ModuleEntity>

    @Query("SELECT * FROM modules WHERE moduleId = :moduleId")
    suspend fun find(moduleId: String): ModuleEntity?

    @Upsert
    suspend fun upsertAll(modules: List<ModuleEntity>)
}

@Dao
interface TrainingProgressDao {

    @Query("SELECT * FROM training_progress WHERE workerId = :workerId ORDER BY moduleCode")
    fun observeForWorker(workerId: String): Flow<List<TrainingProgressEntity>>

    @Query("SELECT * FROM training_progress WHERE workerId = :workerId ORDER BY moduleCode")
    suspend fun forWorker(workerId: String): List<TrainingProgressEntity>

    @Query("SELECT * FROM training_progress WHERE workerId = :workerId AND moduleId = :moduleId")
    suspend fun find(workerId: String, moduleId: String): TrainingProgressEntity?

    @Upsert
    suspend fun upsert(progress: TrainingProgressEntity)

    @Upsert
    suspend fun upsertAll(progress: List<TrainingProgressEntity>)

    @Query("SELECT * FROM training_progress WHERE nextDueAtSec <= :nowSec AND baseScore > 0")
    suspend fun due(nowSec: Long): List<TrainingProgressEntity>
}

@Dao
interface AssessmentRunDao {

    @Query("SELECT * FROM assessment_runs WHERE runId = :runId")
    suspend fun find(runId: String): AssessmentRunEntity?

    @Query("SELECT * FROM assessment_runs WHERE workerId = :workerId ORDER BY finishedAtSec DESC LIMIT :limit")
    fun observeRecent(workerId: String, limit: Int = 20): Flow<List<AssessmentRunEntity>>

    @Query("SELECT * FROM assessment_runs WHERE uploaded = 0 ORDER BY finishedAtSec LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<AssessmentRunEntity>

    /**
     * Passes that never got a certificate.
     *
     * The normal cause is a handset with no site signing key enrolled yet. The pass is kept and the
     * certificate is minted the moment a key arrives, so a worker never has to re-run a module
     * because of an enrolment gap they had no part in.
     */
    @Query(
        """
        SELECT * FROM assessment_runs
        WHERE siteId = :siteId
          AND passed = 1
          AND completion = 'COMPLETED'
          AND runId NOT IN (SELECT runId FROM certificates WHERE runId IS NOT NULL)
        ORDER BY finishedAtSec
        LIMIT :limit
        """,
    )
    suspend fun passedWithoutCertificate(siteId: String, limit: Int): List<AssessmentRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: AssessmentRunEntity)

    @Query("UPDATE assessment_runs SET uploaded = 1 WHERE runId IN (:runIds)")
    suspend fun markUploaded(runIds: List<String>)

    /**
     * A run interrupted by process death.
     *
     * Persisted as INCOMPLETE the moment it starts, so relaunching can offer to resume or discard
     * rather than silently losing what the worker already did.
     */
    @Query(
        """
        SELECT * FROM assessment_runs
        WHERE workerId = :workerId AND completion = 'INCOMPLETE'
        ORDER BY startedAtSec DESC LIMIT 1
        """,
    )
    suspend fun findResumable(workerId: String): AssessmentRunEntity?

    @Query("DELETE FROM assessment_runs WHERE runId = :runId")
    suspend fun delete(runId: String)
}

@Dao
interface CertificateDao {

    @Query("SELECT * FROM certificates WHERE workerId = :workerId ORDER BY issuedAtSec DESC")
    fun observeForWorker(workerId: String): Flow<List<CertificateEntity>>

    @Query("SELECT * FROM certificates WHERE certId = :certId")
    suspend fun find(certId: String): CertificateEntity?

    @Query("SELECT * FROM certificates WHERE siteId = :siteId AND seq = :seq")
    suspend fun findBySeq(siteId: String, seq: Long): CertificateEntity?

    @Query("SELECT * FROM certificates WHERE recordHashHex = :recordHashHex")
    suspend fun findByRecordHash(recordHashHex: String): CertificateEntity?

    /**
     * The certificate minted from a given run, if one was.
     *
     * Lets the result screen distinguish "passed and certified" from "passed, certificate pending because no
     * site key is enrolled yet" — two very different things to tell a worker.
     */
    @Query("SELECT * FROM certificates WHERE runId = :runId LIMIT 1")
    suspend fun findByRunId(runId: String): CertificateEntity?

    @Query("SELECT * FROM certificates WHERE siteId = :siteId ORDER BY seq")
    suspend fun forSite(siteId: String): List<CertificateEntity>

    @Query("SELECT recordHashHex FROM certificates WHERE siteId = :siteId AND seq = :seq")
    suspend fun recordHashAt(siteId: String, seq: Long): String?

    @Query("SELECT MAX(seq) FROM certificates WHERE siteId = :siteId")
    suspend fun highestSeq(siteId: String): Long?

    @Query("SELECT COUNT(*) FROM certificates WHERE siteId = :siteId")
    suspend fun countForSite(siteId: String): Long

    @Query("SELECT * FROM certificates WHERE uploaded = 0 ORDER BY seq LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<CertificateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrThrow(certificate: CertificateEntity)

    @Query("UPDATE certificates SET uploaded = 1 WHERE certId IN (:certIds)")
    suspend fun markUploaded(certIds: List<String>)
}

@Dao
interface ChainHeadDao {

    @Query("SELECT * FROM chain_heads WHERE siteId = :siteId")
    suspend fun find(siteId: String): ChainHeadEntity?

    @Query("SELECT * FROM chain_heads WHERE siteId = :siteId")
    fun observe(siteId: String): Flow<ChainHeadEntity?>

    @Upsert
    suspend fun upsert(head: ChainHeadEntity)
}

@Dao
interface HazardTagDao {

    @Query("SELECT * FROM hazard_tags WHERE siteId = :siteId ORDER BY createdAtSec DESC")
    fun observeForSite(siteId: String): Flow<List<HazardTagEntity>>

    @Query("SELECT * FROM hazard_tags WHERE hazardId = :hazardId")
    suspend fun find(hazardId: String): HazardTagEntity?

    @Query("SELECT * FROM hazard_tags WHERE uploaded = 0 ORDER BY createdAtSec LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<HazardTagEntity>

    @Query("SELECT * FROM hazard_tags WHERE mediaPending = 1 ORDER BY createdAtSec LIMIT :limit")
    suspend fun pendingMedia(limit: Int): List<HazardTagEntity>

    /**
     * Hazards the server holds in full, text and media.
     *
     * The only set whose local files are safe to delete. Anything still queued keeps its evidence,
     * because a report whose photo was pruned before upload cannot be reconstructed.
     */
    @Query("SELECT hazardId FROM hazard_tags WHERE uploaded = 1 AND mediaPending = 0")
    suspend fun fullyUploadedIds(): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM hazard_tags
        WHERE reporterWorkerId = :workerId AND createdAtSec >= :sinceSec
        """,
    )
    suspend fun countRecentByWorker(workerId: String, sinceSec: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(hazard: HazardTagEntity)

    @Query("UPDATE hazard_tags SET uploaded = 1, mediaPending = :mediaPending WHERE hazardId IN (:ids)")
    suspend fun markUploaded(ids: List<String>, mediaPending: Boolean)

    @Query(
        """
        UPDATE hazard_tags
        SET photoMediaId = :photoMediaId, voiceMediaId = :voiceMediaId, mediaPending = 0
        WHERE hazardId = :hazardId
        """,
    )
    suspend fun attachMedia(hazardId: String, photoMediaId: String?, voiceMediaId: String?)
}

@Dao
interface SyncQueueDao {

    @Query(
        """
        SELECT * FROM sync_queue
        WHERE abandoned = 0 AND nextAttemptAtMs <= :nowMs
        ORDER BY createdAtMs
        LIMIT :limit
        """,
    )
    suspend fun ready(nowMs: Long, limit: Int): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE abandoned = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE abandoned = 1")
    fun observeAbandonedCount(): Flow<Int>

    /**
     * Ignores a duplicate `idempotencyKey` rather than replacing it.
     *
     * The unique index is what makes a double-enqueue harmless, and IGNORE is what keeps the
     * original attempt count and backoff instead of silently resetting them.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entry: SyncQueueEntity): Long

    @Query("DELETE FROM sync_queue WHERE queueId IN (:ids)")
    suspend fun remove(ids: List<Long>)

    @Query("DELETE FROM sync_queue WHERE idempotencyKey IN (:keys)")
    suspend fun removeByKeys(keys: List<String>)

    @Query(
        """
        UPDATE sync_queue
        SET attempts = attempts + 1, nextAttemptAtMs = :nextAttemptAtMs, lastError = :error
        WHERE queueId = :queueId
        """,
    )
    suspend fun recordFailure(queueId: Long, nextAttemptAtMs: Long, error: String?)

    @Query("UPDATE sync_queue SET abandoned = 1, lastError = :error WHERE queueId = :queueId")
    suspend fun abandon(queueId: Long, error: String?)

    @Query("SELECT * FROM sync_queue WHERE idempotencyKey = :key")
    suspend fun findByKey(key: String): SyncQueueEntity?
}

@Dao
interface RefresherScheduleDao {

    @Query("SELECT * FROM refresher_schedule WHERE dueAtSec <= :nowSec ORDER BY dueAtSec")
    suspend fun due(nowSec: Long): List<RefresherScheduleEntity>

    @Query("SELECT * FROM refresher_schedule WHERE workerId = :workerId ORDER BY dueAtSec")
    fun observeForWorker(workerId: String): Flow<List<RefresherScheduleEntity>>

    @Query("SELECT * FROM refresher_schedule ORDER BY dueAtSec LIMIT 1")
    suspend fun earliest(): RefresherScheduleEntity?

    @Upsert
    suspend fun upsert(schedule: RefresherScheduleEntity)

    @Query("UPDATE refresher_schedule SET notifiedAtSec = :atSec WHERE workerId = :workerId AND moduleId = :moduleId")
    suspend fun markNotified(workerId: String, moduleId: String, atSec: Long)

    @Query("DELETE FROM refresher_schedule WHERE workerId = :workerId AND moduleId = :moduleId")
    suspend fun clear(workerId: String, moduleId: String)
}

@Dao
interface VoiceTemplateDao {

    @Query("SELECT * FROM voice_templates WHERE languageTag = :languageTag")
    suspend fun forLanguage(languageTag: String): List<VoiceTemplateEntity>

    @Query("SELECT * FROM voice_templates WHERE languageTag = :languageTag")
    fun observeForLanguage(languageTag: String): Flow<List<VoiceTemplateEntity>>

    @Query("SELECT COUNT(DISTINCT commandKey) FROM voice_templates WHERE languageTag = :languageTag")
    suspend fun distinctCommandCount(languageTag: String): Int

    @Upsert
    suspend fun upsert(template: VoiceTemplateEntity)

    @Query("DELETE FROM voice_templates WHERE commandKey = :commandKey AND languageTag = :languageTag")
    suspend fun clearCommand(commandKey: String, languageTag: String)

    @Query("DELETE FROM voice_templates WHERE languageTag = :languageTag")
    suspend fun clearLanguage(languageTag: String)
}

@Dao
interface AppKeyValueDao {

    @Query("SELECT value FROM app_kv WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_kv WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(entry: AppKeyValueEntity)

    @Query("DELETE FROM app_kv WHERE key = :key")
    suspend fun remove(key: String)
}

/**
 * Cross-table reads used by chain verification.
 *
 * Multi-table *writes* are wrapped with `RoomDatabase.withTransaction` in the repository rather
 * than modelled as a DAO method, because a `@Transaction` DAO function cannot take other DAOs as
 * parameters. Certificate issuance is the case that matters: appending to the chain, storing the
 * certificate, advancing the head and enqueuing the upload must all commit together, or the local
 * ledger ends up describing a chain that does not exist.
 */
@Dao
interface ChainQueryDao {

    @Query(
        """
        SELECT seq FROM certificates
        WHERE siteId = :siteId
        ORDER BY seq
        """,
    )
    suspend fun sequencesForSite(siteId: String): List<Long>

    @Transaction
    @Query("SELECT * FROM certificates WHERE siteId = :siteId ORDER BY seq")
    suspend fun ledger(siteId: String): List<CertificateEntity>
}
