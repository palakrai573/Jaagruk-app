package org.jaagruk.safety.data

import android.os.Build
import org.jaagruk.safety.BuildConfig
import org.jaagruk.safety.data.db.AppKeyValueEntity
import org.jaagruk.safety.data.db.JaagrukDatabase
import java.util.UUID

/**
 * Stable identity and capability facts about this handset.
 *
 * The device id is generated once and stored in the Room key/value table rather than derived from
 * `ANDROID_ID` or a hardware serial. Those are either unavailable, rotated per signing key, or
 * outright privacy-restricted on modern Android; and a device id that silently changes would make
 * the server treat one supervisor phone as an endless stream of new devices, breaking the
 * idempotent-batch replay guarantee that keeps a retried upload from double-ingesting.
 *
 * It is also deliberately *not* a hardware identifier. It identifies an install, which is exactly
 * the granularity the sync protocol needs, and it carries no personal data.
 */
class DeviceProfile(private val database: JaagrukDatabase) {

    private val kv = database.appKeyValueDao()

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * Reads, or creates on first call.
     *
     * Suspending because it touches the database. A blocking variant would be convenient and is the
     * reason device ids end up being read on the main thread during a frame; there is no such
     * variant here on purpose.
     */
    suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val existing = kv.get(KEY_DEVICE_ID)
        if (!existing.isNullOrBlank()) {
            cachedDeviceId = existing
            return existing
        }
        // Truncated to keep it inside the buddy-drill frame's 64-byte device id budget while
        // staying comfortably unique: 128 bits of UUID hex is 32 chars.
        val generated = UUID.randomUUID().toString().replace("-", "")
        kv.put(AppKeyValueEntity(KEY_DEVICE_ID, generated, System.currentTimeMillis()))
        cachedDeviceId = generated
        return generated
    }

    /** The site this handset is enrolled to, or null before enrolment. */
    suspend fun activeSiteId(): String? = kv.get(KEY_ACTIVE_SITE)?.takeIf { it.isNotBlank() }

    suspend fun setActiveSiteId(siteId: String) {
        kv.put(AppKeyValueEntity(KEY_ACTIVE_SITE, siteId, System.currentTimeMillis()))
    }

    /** Worker whose session is currently open on this handset, or null when nobody is signed in. */
    suspend fun activeWorkerId(): String? = kv.get(KEY_ACTIVE_WORKER)?.takeIf { it.isNotBlank() }

    suspend fun setActiveWorkerId(workerId: String?) {
        if (workerId == null) {
            kv.remove(KEY_ACTIVE_WORKER)
        } else {
            kv.put(AppKeyValueEntity(KEY_ACTIVE_WORKER, workerId, System.currentTimeMillis()))
        }
    }

    suspend fun isDeviceRegistered(): Boolean = kv.get(KEY_DEVICE_REGISTERED) == "1"

    suspend fun markDeviceRegistered() {
        kv.put(AppKeyValueEntity(KEY_DEVICE_REGISTERED, "1", System.currentTimeMillis()))
    }

    val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    val androidRelease: String get() = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    val appVersion: String get() = BuildConfig.VERSION_NAME

    val apiBaseUrl: String get() = BuildConfig.API_BASE_URL

    /**
     * A short label for the buddy-drill advertisement.
     *
     * Nearby shows this to the other phone before connecting, so it has to be recognisable to a
     * worker in a headlamp beam. Model plus four id characters is enough to tell two identical
     * handsets apart without printing anything personal on a stranger's screen.
     */
    suspend fun nearbyDisplayName(): String {
        val suffix = deviceId().takeLast(4).uppercase()
        return "${Build.MODEL.take(12)}-$suffix"
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACTIVE_SITE = "active_site_id"
        const val KEY_ACTIVE_WORKER = "active_worker_id"
        const val KEY_DEVICE_REGISTERED = "device_registered"
    }
}
