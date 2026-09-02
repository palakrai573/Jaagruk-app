package org.jaagruk.core.drill

import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.CanonicalReader
import org.jaagruk.core.util.CanonicalWriter

/** Message types on the buddy-drill wire. Codes are frozen once shipped. */
enum class DrillMessageType(val code: Int) {
    HELLO(1),
    ROLE_ASSIGN(2),
    SCENARIO_SEED(3),
    READY(4),
    STEP_ADVANCE(5),
    ACTION(6),
    DISTRESS_TRIGGER(7),
    CHECK_BUDDY(8),
    RESCUE_ACTION(9),
    HEARTBEAT(10),
    ABORT(11),
    RESULT(12),
    ;

    companion object {
        private val byCode: Map<Int, DrillMessageType> = entries.associateBy { it.code }

        fun fromCode(code: Int): DrillMessageType? = byCode[code]
    }
}

/**
 * One framed message between two phones during a buddy drill.
 *
 * [logicalMs] is milliseconds since the host declared the drill started, never a wall clock.
 * Shared site phones routinely disagree about the time by minutes; comparing wall clocks would
 * make the reaction-time measurement — the entire point of the drill — meaningless.
 */
class DrillFrame(
    val protocolVersion: Int,
    val type: DrillMessageType,
    val senderSeq: Long,
    val logicalMs: Long,
    val senderDeviceId: String,
    val body: Map<String, String> = emptyMap(),
) {
    init {
        require(protocolVersion in 0..255) { "protocolVersion out of u8 range: $protocolVersion" }
        require(senderSeq in 0..MAX_U32) { "senderSeq must fit in u32, got $senderSeq" }
        require(logicalMs in 0..MAX_U32) { "logicalMs must fit in u32, got $logicalMs" }
        require(senderDeviceId.isNotBlank()) { "senderDeviceId must not be blank" }
        require(senderDeviceId.toByteArray(Charsets.UTF_8).size <= MAX_DEVICE_ID_BYTES) {
            "senderDeviceId exceeds $MAX_DEVICE_ID_BYTES bytes"
        }
    }

    fun bodyValue(key: String): String? = body[key]

    fun bodyInt(key: String): Int? = body[key]?.toIntOrNull()

    fun bodyLong(key: String): Long? = body[key]?.toLongOrNull()

    fun bodyList(key: String): List<String> =
        body[key]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    override fun toString(): String =
        "DrillFrame(v$protocolVersion, $type, seq=$senderSeq, t=${logicalMs}ms, from=$senderDeviceId, body=$body)"

    companion object {
        const val MAX_U32: Long = 0xFFFF_FFFFL
        const val MAX_DEVICE_ID_BYTES: Int = 64

        // Body keys, centralised so a typo is a compile error rather than a silently missing field.
        const val KEY_WORKER_ID: String = "w"
        const val KEY_ROLE: String = "r"
        const val KEY_SCENARIO_ID: String = "s"
        const val KEY_SEED: String = "sd"
        const val KEY_STEP_ID: String = "st"
        const val KEY_OPTIONS: String = "o"
        const val KEY_CORRECT: String = "c"
        const val KEY_SCORE: String = "sc"
        const val KEY_PASSED: String = "p"
        const val KEY_REASON: String = "rs"
        const val KEY_CATALOG_VERSION: String = "cv"
        const val KEY_APP_VERSION: String = "av"
    }
}

/**
 * Length-prefixed binary framing for [DrillFrame].
 *
 * Binary rather than JSON because these frames cross a Bluetooth or Wi-Fi Direct link that can
 * truncate at any byte. Length-prefixed fields make a partial frame a detectable
 * [CanonicalFormatException] instead of a plausible-looking half-message, which is what lets the
 * state machine safely drop it and carry on rather than desynchronising.
 */
object DrillFrameCodec {

    const val PROTOCOL_VERSION: Int = 1

    /** Generous for a control frame, tight enough to reject a hostile payload outright. */
    const val MAX_FRAME_BYTES: Int = 4_096

    fun encode(frame: DrillFrame): ByteArray {
        val bodyText = DrillBody.encode(frame.body)
        val bytes = CanonicalWriter(96 + bodyText.length)
            .u8(frame.protocolVersion)
            .u8(frame.type.code)
            .u32(frame.senderSeq)
            .u32(frame.logicalMs)
            .lp(frame.senderDeviceId, DrillFrame.MAX_DEVICE_ID_BYTES)
            .lp(bodyText, MAX_FRAME_BYTES - 128)
            .toByteArray()

        check(bytes.size <= MAX_FRAME_BYTES) {
            "drill frame grew to ${bytes.size} bytes, over the $MAX_FRAME_BYTES cap"
        }
        return bytes
    }

    /** @throws CanonicalFormatException for a truncated, oversized or unknown-type frame. */
    fun decode(bytes: ByteArray): DrillFrame {
        if (bytes.isEmpty()) throw CanonicalFormatException("empty drill frame")
        if (bytes.size > MAX_FRAME_BYTES) {
            throw CanonicalFormatException(
                "drill frame is ${bytes.size} bytes, over the $MAX_FRAME_BYTES cap",
            )
        }

        val reader = CanonicalReader(bytes)
        val protocolVersion = reader.u8()
        val typeCode = reader.u8()
        val senderSeq = reader.u32()
        val logicalMs = reader.u32()
        val senderDeviceId = reader.lp(DrillFrame.MAX_DEVICE_ID_BYTES)
        val bodyText = reader.lp(MAX_FRAME_BYTES - 128)
        reader.requireExhausted()

        val type = DrillMessageType.fromCode(typeCode)
            ?: throw CanonicalFormatException("unknown drill message type code $typeCode")

        return try {
            DrillFrame(
                protocolVersion = protocolVersion,
                type = type,
                senderSeq = senderSeq,
                logicalMs = logicalMs,
                senderDeviceId = senderDeviceId,
                body = DrillBody.decode(bodyText),
            )
        } catch (e: IllegalArgumentException) {
            throw CanonicalFormatException("drill frame failed validation: ${e.message}", e)
        }
    }

    fun decodeOrNull(bytes: ByteArray): DrillFrame? =
        try {
            decode(bytes)
        } catch (e: CanonicalFormatException) {
            null
        }
}

/**
 * Flat `key=value;key=value` body encoding with escaping.
 *
 * Chosen over JSON to keep `:core` dependency-free on the wire path and to make round-tripping
 * provably lossless — the escape rules below are total, so no worker id, step id or reason string
 * can break out of its field.
 */
object DrillBody {

    private const val PAIR_SEPARATOR = ';'
    private const val KEY_VALUE_SEPARATOR = '='
    private const val ESCAPE = '\\'

    fun encode(body: Map<String, String>): String {
        if (body.isEmpty()) return ""
        // Sorted so encoding is deterministic and two devices produce identical bytes for
        // identical content, which makes frame logs diffable during debugging.
        return body.entries
            .sortedBy { it.key }
            .joinToString(PAIR_SEPARATOR.toString()) { (k, v) ->
                "${escape(k)}$KEY_VALUE_SEPARATOR${escape(v)}"
            }
    }

    fun decode(text: String): Map<String, String> {
        if (text.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()

        for (pair in splitUnescaped(text, PAIR_SEPARATOR)) {
            if (pair.isEmpty()) continue
            val parts = splitUnescaped(pair, KEY_VALUE_SEPARATOR)
            if (parts.size != 2) {
                throw CanonicalFormatException("malformed drill body pair: '$pair'")
            }
            val key = unescape(parts[0])
            if (key.isEmpty()) throw CanonicalFormatException("drill body has an empty key")
            result[key] = unescape(parts[1])
        }
        return result
    }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 4)
        for (ch in value) {
            when (ch) {
                ESCAPE -> sb.append(ESCAPE).append(ESCAPE)
                PAIR_SEPARATOR -> sb.append(ESCAPE).append('s')
                KEY_VALUE_SEPARATOR -> sb.append(ESCAPE).append('e')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != ESCAPE) {
                sb.append(ch)
                i++
                continue
            }
            if (i + 1 >= value.length) {
                throw CanonicalFormatException("drill body ends with a dangling escape")
            }
            when (val next = value[i + 1]) {
                ESCAPE -> sb.append(ESCAPE)
                's' -> sb.append(PAIR_SEPARATOR)
                'e' -> sb.append(KEY_VALUE_SEPARATOR)
                else -> throw CanonicalFormatException("unknown drill body escape '\\$next'")
            }
            i += 2
        }
        return sb.toString()
    }

    /** Splits on [delimiter], ignoring occurrences preceded by an escape character. */
    private fun splitUnescaped(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == ESCAPE && i + 1 < text.length) {
                current.append(ch).append(text[i + 1])
                i += 2
                continue
            }
            if (ch == delimiter) {
                parts += current.toString()
                current.setLength(0)
                i++
                continue
            }
            current.append(ch)
            i++
        }
        parts += current.toString()
        return parts
    }
}
