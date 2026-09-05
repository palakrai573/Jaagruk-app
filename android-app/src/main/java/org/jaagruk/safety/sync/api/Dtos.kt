package org.jaagruk.safety.sync.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs.
 *
 * Field names mirror `backend/app/schemas.py` exactly, via `@SerialName`. The Kotlin names stay
 * idiomatic camelCase; the wire stays snake_case. Getting one of these wrong produces a 422 with
 * the offending field named, which is why the backend flattens validation errors into readable
 * "field: message" lines instead of FastAPI's nested default.
 */

@Serializable
data class LoginRequest(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int,
    @SerialName("role") val role: String,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("site_id") val siteId: String? = null,
    @SerialName("full_name") val fullName: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class DeviceRegisterRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("site_public_key_hex") val sitePublicKeyHex: String,
    @SerialName("attest_public_key_hex") val attestPublicKeyHex: String? = null,
    @SerialName("key_epoch") val keyEpoch: Int = 1,
    @SerialName("model") val model: String? = null,
    @SerialName("android_release") val androidRelease: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class DeviceResponse(
    @SerialName("id") val id: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("active") val active: Boolean,
)

@Serializable
data class WorkerRegisterRequest(
    @SerialName("id") val id: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("preferred_language") val preferredLanguage: String,
    @SerialName("pictogram_mode") val pictogramMode: Boolean = false,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("employment_type") val employmentType: String? = null,
)

// ---------------------------------------------------------------------------
// Sync
// ---------------------------------------------------------------------------

@Serializable
data class CertificateUpload(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("qr_text") val qrText: String,
    @SerialName("worker_id") val workerId: String,
    @SerialName("module_id") val moduleId: String,
    @SerialName("key_epoch") val keyEpoch: Int = 1,
    @SerialName("run_id") val runId: String? = null,
)

@Serializable
data class StepResultUpload(
    @SerialName("step_id") val stepId: String,
    @SerialName("outcome") val outcome: String,
    @SerialName("latency_ms") val latencyMs: Long,
    @SerialName("expert_ms") val expertMs: Long,
    @SerialName("timeout_ms") val timeoutMs: Long,
    @SerialName("correct") val correct: Boolean,
    @SerialName("critical") val critical: Boolean,
    @SerialName("weight") val weight: Double,
    @SerialName("input_method") val inputMethod: String,
    @SerialName("suspicious_fast") val suspiciousFast: Boolean = false,
)

@Serializable
data class AssessmentUpload(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("run_id") val runId: String,
    @SerialName("worker_id") val workerId: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("module_id") val moduleId: String,
    @SerialName("module_code") val moduleCode: Int,
    @SerialName("scenario_id") val scenarioId: String,
    @SerialName("catalog_version") val catalogVersion: Int = 1,
    @SerialName("mode") val mode: String,
    @SerialName("presentation") val presentation: String,
    @SerialName("completion") val completion: String,
    @SerialName("score_permille") val scorePermille: Int,
    @SerialName("passed") val passed: Boolean,
    @SerialName("hesitation_flag") val hesitationFlag: Boolean,
    @SerialName("hesitation_ratio") val hesitationRatio: Double,
    @SerialName("median_latency_ms") val medianLatencyMs: Long,
    @SerialName("started_at_sec") val startedAtSec: Long,
    @SerialName("finished_at_sec") val finishedAtSec: Long,
    @SerialName("total_duration_ms") val totalDurationMs: Long,
    @SerialName("steps") val steps: List<StepResultUpload> = emptyList(),
    @SerialName("failed_critical_step_ids") val failedCriticalStepIds: List<String> = emptyList(),
    @SerialName("void_reason") val voidReason: String? = null,
    @SerialName("abort_reason") val abortReason: String? = null,
    @SerialName("buddy_peer_device_id") val buddyPeerDeviceId: String? = null,
)

@Serializable
data class HazardUpload(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("hazard_id") val hazardId: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("reporter_worker_id") val reporterWorkerId: String? = null,
    @SerialName("category") val category: String,
    @SerialName("severity") val severity: String,
    @SerialName("note") val note: String? = null,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("zone_label") val zoneLabel: String? = null,
    @SerialName("ar_anchor_id") val arAnchorId: String? = null,
    @SerialName("photo_media_id") val photoMediaId: String? = null,
    @SerialName("voice_media_id") val voiceMediaId: String? = null,
    @SerialName("reported_at_sec") val reportedAtSec: Long,
)

@Serializable
data class ProgressUpload(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("worker_id") val workerId: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("module_id") val moduleId: String,
    @SerialName("module_code") val moduleCode: Int,
    @SerialName("base_score") val baseScore: Int,
    @SerialName("last_pass_at_sec") val lastPassAtSec: Long,
    @SerialName("certified_at_sec") val certifiedAtSec: Long,
    @SerialName("refresher_stage") val refresherStage: Int,
    @SerialName("next_due_at_sec") val nextDueAtSec: Long,
    @SerialName("consecutive_failures") val consecutiveFailures: Int,
    @SerialName("attempts") val attempts: Int,
    @SerialName("best_score_permille") val bestScorePermille: Int,
    @SerialName("last_hesitation_flag") val lastHesitationFlag: Boolean = false,
)

@Serializable
data class SyncBatchRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("client_batch_id") val clientBatchId: String,
    @SerialName("device_signature_hex") val deviceSignatureHex: String? = null,
    @SerialName("certificates") val certificates: List<CertificateUpload> = emptyList(),
    @SerialName("assessments") val assessments: List<AssessmentUpload> = emptyList(),
    @SerialName("hazards") val hazards: List<HazardUpload> = emptyList(),
    @SerialName("progress") val progress: List<ProgressUpload> = emptyList(),
)

@Serializable
data class SyncItemResult(
    @SerialName("kind") val kind: String,
    @SerialName("ref_id") val refId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    /** accepted | duplicate | quarantined | rejected */
    @SerialName("status") val status: String,
    @SerialName("reason") val reason: String? = null,
    /** True when the device should keep the item queued rather than abandon it. */
    @SerialName("retryable") val retryable: Boolean = false,
)

@Serializable
data class SyncBatchResponse(
    @SerialName("batch_id") val batchId: String,
    @SerialName("accepted") val accepted: Int,
    @SerialName("rejected") val rejected: Int,
    @SerialName("quarantined") val quarantined: Int,
    @SerialName("duplicates") val duplicates: Int,
    @SerialName("results") val results: List<SyncItemResult> = emptyList(),
    @SerialName("server_time_sec") val serverTimeSec: Long,
    @SerialName("replayed") val replayed: Boolean = false,
)

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------

@Serializable
data class SiteDto(
    @SerialName("id") val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("name") val name: String,
    @SerialName("district") val district: String,
    @SerialName("sector") val sector: String,
    @SerialName("ar_scanned") val arScanned: Boolean,
    @SerialName("ar_anchor_count") val arAnchorCount: Int,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("active") val active: Boolean,
)

@Serializable
data class SiteKeyDto(
    @SerialName("epoch") val epoch: Int,
    @SerialName("public_key_hex") val publicKeyHex: String,
    @SerialName("active") val active: Boolean,
)

@Serializable
data class ModuleDto(
    @SerialName("id") val id: String,
    @SerialName("module_code") val moduleCode: Int,
    @SerialName("catalog_version") val catalogVersion: Int,
    @SerialName("title_en") val titleEn: String,
    @SerialName("statutory_reference") val statutoryReference: String,
    @SerialName("estimated_minutes") val estimatedMinutes: Int,
    @SerialName("supports_buddy_drill") val supportsBuddyDrill: Boolean,
    @SerialName("fully_implemented") val fullyImplemented: Boolean,
    @SerialName("enabled") val enabled: Boolean,
)

@Serializable
data class WorkerDto(
    @SerialName("id") val id: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("preferred_language") val preferredLanguage: String,
    @SerialName("pictogram_mode") val pictogramMode: Boolean,
    @SerialName("active") val active: Boolean,
    @SerialName("provisional") val provisional: Boolean,
)

@Serializable
data class BootstrapResponse(
    @SerialName("site") val site: SiteDto,
    @SerialName("site_keys") val siteKeys: List<SiteKeyDto> = emptyList(),
    @SerialName("modules") val modules: List<ModuleDto> = emptyList(),
    @SerialName("workers") val workers: List<WorkerDto> = emptyList(),
    @SerialName("chain_head_seq") val chainHeadSeq: Long,
    @SerialName("chain_head_hash_hex") val chainHeadHashHex: String,
    @SerialName("catalog_version") val catalogVersion: Int,
    @SerialName("server_time_sec") val serverTimeSec: Long,
)

@Serializable
data class MediaResponse(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: String,
    @SerialName("byte_size") val byteSize: Long,
    @SerialName("sha256_hex") val sha256Hex: String,
    @SerialName("url") val url: String,
)

@Serializable
data class VerifyRequest(
    @SerialName("qr_text") val qrText: String,
    @SerialName("candidate_worker_id") val candidateWorkerId: String? = null,
)

@Serializable
data class VerifyResponse(
    @SerialName("status") val status: String,
    @SerialName("trustworthy") val trustworthy: Boolean,
    @SerialName("indicates_tampering") val indicatesTampering: Boolean,
    @SerialName("reasons") val reasons: List<String> = emptyList(),
    @SerialName("site_id") val siteId: String? = null,
    @SerialName("seq") val seq: Long? = null,
    @SerialName("worker_full_name") val workerFullName: String? = null,
    @SerialName("statutory_valid") val statutoryValid: Boolean? = null,
    @SerialName("readiness_permille") val readinessPermille: Int? = null,
    @SerialName("readiness_band") val readinessBand: String? = null,
)

/** FastAPI's error body, flattened by the backend's exception handler. */
@Serializable
data class ApiErrorBody(
    @SerialName("detail") val detail: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("hint") val hint: String? = null,
)
