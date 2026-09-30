package org.jaagruk.safety.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jaagruk.safety.data.db.SyncQueueEntity

/**
 * What a queue entry uploads, and how its retry behaves.
 *
 * The queue is a single table rather than one per record type, so draining it preserves the order
 * things actually happened in. A certificate arriving before the assessment run that produced it
 * would be harmless, but a hazard arriving before the site it belongs to is not — the server would
 * reject it as retryable and the device would spin.
 *
 * Backoff is exponential from 30 s, doubling, capped at 6 h, with up to ±20 % jitter. The jitter
 * matters at shift change: fifty handsets coming into Wi-Fi range within a minute of each other must
 * not all retry on the same second.
 */
enum class SyncKind(val wireName: String) {
    CERTIFICATE("certificate"),
    ASSESSMENT("assessment"),
    HAZARD("hazard"),
    PROGRESS("progress"),

    /**
     * A record received from another handset over Nearby, awaiting upload.
     *
     * Kept as its own kind because the payload is a finished DTO rather than a reference to a local
     * row. The relaying device does not own the record and must not fabricate a row for it: it is a
     * courier carrying signed bytes it cannot alter, under an idempotency key it cannot change. That
     * is what makes the relay path safe — and why a relayed certificate and a directly uploaded one
     * collapse onto the same server-side row instead of double-ingesting.
     */
    RELAY("relay"),
    ;

    fun queueEntry(
        refId: String,
        idempotencyKey: String,
        payloadJson: String,
        nowMs: Long,
    ): SyncQueueEntity = SyncQueueEntity(
        kind = wireName,
        refId = refId,
        idempotencyKey = idempotencyKey,
        payloadJson = payloadJson,
        attempts = 0,
        // Zero means "eligible immediately"; the worker picks it up on its next pass.
        nextAttemptAtMs = 0L,
        createdAtMs = nowMs,
    )

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        fun fromWireName(name: String): SyncKind? = byWireName[name]

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        // --- backoff ------------------------------------------------------
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 6 * 60 * 60 * 1_000L
        const val JITTER_FRACTION = 0.20

        /**
         * Attempts after which a *non-retryable* item is abandoned.
         *
         * A retryable failure — a 5xx, a timeout, no key registered yet — never counts against
         * this. Only a definite server verdict does. Discarding a worker's certificate because the
         * uplink was bad for a fortnight would defeat the point of the whole offline design.
         */
        const val MAX_ATTEMPTS = 12

        fun nextAttemptAt(nowMs: Long, attempts: Int, random: () -> Double = Math::random): Long {
            val exponent = attempts.coerceIn(0, 20)
            val base = (BASE_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
            val jitter = 1.0 + (random() * 2.0 - 1.0) * JITTER_FRACTION
            return nowMs + (base * jitter).toLong().coerceAtLeast(1_000L)
        }

        // --- payload builders --------------------------------------------
        // Built by hand rather than by serialising the entity, so the wire shape is visible in one
        // place next to the backend schema it has to match.

        fun certificatePayload(
            qrText: String,
            workerId: String,
            moduleCode: Int,
            keyEpoch: Int,
            runId: String?,
        ): String = buildJsonObject {
            put("qr_text", qrText)
            put("worker_id", workerId)
            put("module_code", moduleCode)
            put("key_epoch", keyEpoch)
            if (runId != null) put("run_id", runId)
        }.toString()

        fun assessmentPayload(runId: String): String = buildJsonObject {
            // The full run is read from the database at drain time. Only the reference is stored, so
            // a queued upload always reflects the current row rather than a stale copy of it.
            put("run_id", runId)
        }.toString()

        fun hazardPayload(hazardId: String): String = buildJsonObject {
            put("hazard_id", hazardId)
        }.toString()

        fun progressPayload(workerId: String, moduleId: String): String = buildJsonObject {
            put("worker_id", workerId)
            put("module_id", moduleId)
        }.toString()

        fun parse(payloadJson: String): JsonObject =
            json.parseToJsonElement(payloadJson) as JsonObject
    }
}
