package org.jaagruk.safety.sync

import android.util.Log
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.db.AppKeyValueEntity
import org.jaagruk.safety.data.db.JaagrukDatabase
import kotlin.math.abs

/**
 * Tracks how far this handset's clock has drifted from the server's.
 *
 * Shared site phones have genuinely wrong clocks. They get handed between shifts, factory reset,
 * flown between timezones in a supervisor's bag, and left off long enough to lose the battery-backed
 * RTC. That is survivable for most of this app because every *measurement* comes from a monotonic
 * clock — decision latency, drill timing, lockout floors — but three things are unavoidably dated
 * against wall time:
 *
 *  * certificate issuance time, which is signed and cannot be corrected later;
 *  * statutory expiry, which is date arithmetic by law;
 *  * readiness decay, which is elapsed days since the last pass.
 *
 * So the skew is measured whenever the server tells us its time, stored, and surfaced. A certificate
 * issued on a handset that is four days out is still cryptographically valid — and the dashboard says
 * the device was skewed when it was issued, rather than quietly presenting a wrong date as fact.
 */
class TimeSyncTracker(
    private val database: JaagrukDatabase,
    private val clock: WallClock,
) {

    private val kv = database.appKeyValueDao()

    /** Skew beyond which the UI warns and certificate issuance is flagged. */
    val warnThresholdSeconds: Long = 15 * 60L

    /**
     * Skew beyond which this handset should not mint certificates at all.
     *
     * An hour is the point at which a wrong issuance date could push a certificate across a
     * statutory day boundary. Refusing is better than signing a date that an inspector will later
     * find does not match the shift record.
     */
    val blockThresholdSeconds: Long = 60 * 60L

    /** Records the observed skew. Positive means this device's clock is ahead of the server's. */
    suspend fun record(serverTimeSec: Long) {
        if (serverTimeSec <= 0L) return
        val skew = clock.epochSeconds() - serverTimeSec
        val nowMs = System.currentTimeMillis()
        kv.put(AppKeyValueEntity(KEY_SKEW, skew.toString(), nowMs))
        kv.put(AppKeyValueEntity(KEY_LAST_SYNC_MS, nowMs.toString(), nowMs))
        if (abs(skew) > warnThresholdSeconds) {
            Log.w(TAG, "device clock is ${skew}s from the server; certificate dates will be flagged")
        }
    }

    suspend fun skewSeconds(): Long = kv.get(KEY_SKEW)?.toLongOrNull() ?: 0L

    suspend fun lastServerContactMs(): Long? = kv.get(KEY_LAST_SYNC_MS)?.toLongOrNull()

    suspend fun isSkewed(): Boolean = abs(skewSeconds()) > warnThresholdSeconds

    /** True when the skew is bad enough that issuing a dated, signed certificate is unsafe. */
    suspend fun blocksCertificateIssuance(): Boolean =
        abs(skewSeconds()) > blockThresholdSeconds

    /**
     * The server's best-known current time, from the last observed skew.
     *
     * Used for display only. Storage always uses the device clock, so a later correction changes the
     * displayed skew rather than rewriting stored timestamps — rewriting them would alter the dates
     * inside records that are already signed.
     */
    suspend fun estimatedServerEpochSeconds(): Long = clock.epochSeconds() - skewSeconds()

    /** True when this device has never spoken to the server. Distinct from a zero skew. */
    suspend fun hasNeverSynced(): Boolean = kv.get(KEY_LAST_SYNC_MS) == null

    private companion object {
        const val TAG = "TimeSyncTracker"
        const val KEY_SKEW = "server_clock_skew_sec"
        const val KEY_LAST_SYNC_MS = "last_server_contact_ms"
    }
}
