package org.jaagruk.safety.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities.
 *
 * Two constraints in here carry most of the offline integrity guarantee, and both are enforced by
 * the database rather than by the code path that happens to write them:
 *
 *  * `certificates` has a unique index on `(siteId, seq)`. Two coroutines racing to mint a
 *    certificate cannot both take the same chain slot — SQLite refuses the second insert.
 *  * `sync_queue` has a unique index on `idempotencyKey`. A double-enqueue is rejected at the
 *    storage layer, so a record cannot be uploaded twice even if the enqueue is retried.
 *
 * Timestamps are epoch **seconds** wherever the value is also arithmetic (decay, expiry, schedule)
 * and epoch **millis** only for ordering. Latency is always measured from a monotonic clock in
 * `:core` and stored as a plain duration, never derived from wall time.
 */

@Entity(
    tableName = "workers",
    indices = [Index("siteId"), Index("workerIdHash")],
)
data class WorkerEntity(
    @PrimaryKey val workerId: String,
    val siteId: String,
    val fullName: String,
    /** SHA-256 of the worker id, hex. Matches what a certificate QR carries. */
    val workerIdHash: String,
    val preferredLanguage: String,
    val pictogramMode: Boolean,
    /** Argon2-style derived PIN hash produced by `PinAuthenticator`. Never the PIN itself. */
    val pinHash: String?,
    val pinSalt: String?,
    val failedPinAttempts: Int = 0,
    /** Wall-clock floor for a lockout, in epoch millis. */
    val lockedUntilEpochMs: Long = 0L,
    /**
     * Monotonic deadline for the same lockout.
     *
     * Both are stored because either alone is defeatable: winding the clock back beats the wall
     * value, and a reboot resets the monotonic one. A lockout expires only when *both* have passed.
     */
    val lockedUntilElapsedMs: Long = 0L,
    val registeredAtSec: Long,
    val serverSynced: Boolean = false,
    val active: Boolean = true,
)

@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val siteId: String,
    val name: String,
    val district: String,
    val sector: String,
    /** Ed25519 public key, hex. Enough to verify; the private half is in the encrypted key store. */
    val publicKeyHex: String?,
    val keyEpoch: Int = 1,
    val arScanned: Boolean = false,
    val anchorCount: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAtSec: Long,
)

/**
 * A resolved position for one named AR target at one site.
 *
 * `cloudAnchorId` is null for a local-only anchor, which happens when Cloud Anchor hosting fails
 * or the site has no connectivity during setup. A local anchor still works, but only on the device
 * that placed it — recorded in `deviceScoped` so the UI can say so rather than quietly training a
 * worker against a marker their colleague will not see.
 */
@Entity(
    tableName = "site_anchors",
    indices = [Index("siteId"), Index(value = ["siteId", "targetKey"], unique = true)],
)
data class SiteAnchorEntity(
    @PrimaryKey val anchorId: String,
    val siteId: String,
    /** One of `org.jaagruk.core.catalog.ArTargets`. */
    val targetKey: String,
    val cloudAnchorId: String?,
    val deviceScoped: Boolean,
    val label: String?,
    val createdAtSec: Long,
    val lastResolvedAtSec: Long = 0L,
    val resolveFailureCount: Int = 0,
)

@Entity(tableName = "modules")
data class ModuleEntity(
    @PrimaryKey val moduleId: String,
    val moduleCode: Int,
    val catalogVersion: Int,
    val titleEn: String,
    val statutoryReference: String,
    val estimatedMinutes: Int,
    val supportsBuddyDrill: Boolean,
    val fullyImplemented: Boolean,
    val enabled: Boolean = true,
)

/**
 * Retention state per (worker, module). Mirrors `org.jaagruk.core.retention.RetentionState`.
 *
 * Readiness is never stored: it is recomputed on read from `baseScore`, `lastPassAtSec` and
 * `refresherStage`, so a handset that spent six weeks underground reports the correct figure the
 * instant it powers on, with no background job having run.
 */
@Entity(
    tableName = "training_progress",
    primaryKeys = ["workerId", "moduleId"],
    indices = [Index("nextDueAtSec"), Index("workerId")],
)
data class TrainingProgressEntity(
    val workerId: String,
    val moduleId: String,
    val siteId: String,
    val moduleCode: Int,
    val baseScore: Int = 0,
    val lastPassAtSec: Long = 0L,
    val certifiedAtSec: Long = 0L,
    val refresherStage: Int = 0,
    val nextDueAtSec: Long = 0L,
    val consecutiveFailures: Int = 0,
    val attempts: Int = 0,
    val bestScorePermille: Int = 0,
    val lastHesitationFlag: Boolean = false,
    val updatedAtSec: Long = 0L,
)

@Entity(
    tableName = "assessment_runs",
    indices = [Index("workerId"), Index("moduleId"), Index("finishedAtSec"), Index("uploaded")],
)
data class AssessmentRunEntity(
    @PrimaryKey val runId: String,
    val workerId: String,
    val siteId: String,
    val moduleId: String,
    val moduleCode: Int,
    val scenarioId: String,
    val catalogVersion: Int,
    val mode: String,
    val presentation: String,
    val completion: String,
    val scorePermille: Int,
    val passed: Boolean,
    val hesitationFlag: Boolean,
    val hesitationRatio: Double,
    val medianLatencyMs: Long,
    val startedAtSec: Long,
    val finishedAtSec: Long,
    val totalDurationMs: Long,
    /** Per-step detail as JSON, so a disputed score can be recomputed years later. */
    val stepsJson: String,
    val failedCriticalStepsJson: String,
    val voidReason: String? = null,
    val abortReason: String? = null,
    val buddyPeerDeviceId: String? = null,
    val uploaded: Boolean = false,
)

@Entity(
    tableName = "certificates",
    indices = [
        Index(value = ["siteId", "seq"], unique = true),
        Index(value = ["recordHashHex"], unique = true),
        Index("workerId"),
        Index("uploaded"),
    ],
)
data class CertificateEntity(
    @PrimaryKey val certId: String,
    val siteId: String,
    val seq: Long,
    val keyEpoch: Int,
    val workerId: String,
    val workerIdHashHex: String,
    val moduleCode: Int,
    val scorePermille: Int,
    val medianLatencyMs: Long,
    val outcomeFlags: Int,
    val issuedAtSec: Long,
    val prevRecordHashHex: String,
    val recordHashHex: String,
    val signatureHex: String,
    /** The exact string a scanner reads. Stored so display never re-derives it. */
    val qrText: String,
    val runId: String?,
    val uploaded: Boolean = false,
)

/** Tip of a site's local chain. One row per site. */
@Entity(tableName = "chain_heads")
data class ChainHeadEntity(
    @PrimaryKey val siteId: String,
    val lastSeq: Long,
    val lastRecordHashHex: String,
    val updatedAtSec: Long,
)

@Entity(
    tableName = "hazard_tags",
    indices = [Index("siteId"), Index("uploaded"), Index("createdAtSec")],
)
data class HazardTagEntity(
    @PrimaryKey val hazardId: String,
    val siteId: String,
    val reporterWorkerId: String?,
    val category: String,
    val severity: String,
    val note: String?,
    val latitude: Double?,
    val longitude: Double?,
    val zoneLabel: String?,
    val arAnchorId: String?,
    /** Local file path until the media uploads and a server media id comes back. */
    val photoPath: String?,
    val voiceNotePath: String?,
    val photoMediaId: String?,
    val voiceMediaId: String?,
    val createdAtSec: Long,
    val uploaded: Boolean = false,
    /** True when the text record synced but its media has not. Retried independently. */
    val mediaPending: Boolean = false,
)

/**
 * Outbound work queue.
 *
 * Every user action writes to its own table first and then enqueues here. Nothing is ever gated on
 * the network, and sync is additive only: there is no client-driven update or delete, so a device
 * cannot rewrite history even if its queue is tampered with.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index("nextAttemptAtMs"),
        Index("kind"),
    ],
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val queueId: Long = 0L,
    /** One of `SyncKind`. */
    val kind: String,
    /** Primary key of the row this entry uploads. */
    val refId: String,
    /** Stable across retries and across a gossip relay, which is what collapses duplicates. */
    val idempotencyKey: String,
    val payloadJson: String,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val lastError: String? = null,
    /** Set when the server returns a non-retryable verdict. Kept for diagnosis, never resent. */
    val abandoned: Boolean = false,
    val createdAtMs: Long,
)

@Entity(
    tableName = "refresher_schedule",
    primaryKeys = ["workerId", "moduleId"],
    indices = [Index("dueAtSec")],
)
data class RefresherScheduleEntity(
    val workerId: String,
    val moduleId: String,
    val dueAtSec: Long,
    val stage: Int,
    val notifiedAtSec: Long = 0L,
)

/**
 * One enrolled voice template.
 *
 * This is what makes Santali voice input possible at all. There is no usable Santali acoustic
 * model, so a supervisor records the fixed command vocabulary once per site and matching runs
 * offline against these MFCC features.
 */
@Entity(
    tableName = "voice_templates",
    indices = [Index("commandKey"), Index("languageTag")],
)
data class VoiceTemplateEntity(
    @PrimaryKey val templateId: String,
    val commandKey: String,
    val languageTag: String,
    val siteId: String?,
    /** Base64url MFCC blob from `org.jaagruk.core.speech.MfccCodec`. */
    val mfccBlob: String,
    val frameCount: Int,
    val enrolledAtSec: Long,
)

/**
 * Small durable key/value store.
 *
 * Deliberately separate from `EncryptedSharedPreferences`. Values here must survive even if the
 * Keystore-backed store cannot be opened — which does happen, on a device whose keystore was reset
 * — so the app can still tell the user what went wrong instead of crashing at startup.
 */
@Entity(tableName = "app_kv")
data class AppKeyValueEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value") val value: String,
    val updatedAtMs: Long,
)
