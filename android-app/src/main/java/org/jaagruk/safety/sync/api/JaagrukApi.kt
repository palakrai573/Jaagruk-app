package org.jaagruk.safety.sync.api

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * The complete set of routes this app calls.
 *
 * Nothing here is on the critical path for training. The device runs drills, scores them, issues
 * signed certificates and verifies scanned ones with no network at all; this interface exists only
 * to hand over what has already happened, and to pull down the roster and key material when
 * connectivity happens to exist.
 *
 * Every method returns `Response<T>` rather than the bare body, so a caller can distinguish
 * "rejected, stop retrying" from "failed, keep the queue" — a distinction the sync worker depends
 * on and which an exception-based API would flatten.
 */
interface JaagrukApi {

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<TokenResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<TokenResponse>

    @POST("api/v1/devices/register")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): Response<DeviceResponse>

    @POST("api/v1/workers")
    suspend fun registerWorker(@Body request: WorkerRegisterRequest): Response<WorkerDto>

    /**
     * Idempotent bulk upload.
     *
     * Replaying the same `(deviceId, clientBatchId)` returns the stored response with
     * `replayed = true` and ingests nothing, which is what makes a retry after a lost reply safe.
     * On a mine-site uplink that is the normal case, not the exception.
     */
    @POST("api/v1/sync/batch")
    suspend fun uploadBatch(@Body request: SyncBatchRequest): Response<SyncBatchResponse>

    /** Down-sync: site keys for every epoch, module catalog, roster and the current chain head. */
    @GET("api/v1/sync/bootstrap")
    suspend fun bootstrap(
        @Query("site_id") siteId: String,
        @Query("include_roster") includeRoster: Boolean = true,
    ): Response<BootstrapResponse>

    /**
     * Optional online cross-check of a scanned certificate.
     *
     * The offline verifier is authoritative for the inspector's decision. This adds what only the
     * server knows — the worker's current readiness across every device that has synced — and its
     * absence never blocks a verification.
     */
    @POST("api/v1/certificates/verify")
    suspend fun verifyCertificate(@Body request: VerifyRequest): Response<VerifyResponse>

    @Multipart
    @POST("api/v1/media")
    suspend fun uploadMedia(
        @Query("kind") kind: String,
        @Query("site_id") siteId: String?,
        @Part file: MultipartBody.Part,
    ): Response<MediaResponse>
}
