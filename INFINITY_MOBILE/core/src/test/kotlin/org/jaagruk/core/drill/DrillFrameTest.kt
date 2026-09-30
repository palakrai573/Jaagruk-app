package org.jaagruk.core.drill

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.util.CanonicalFormatException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DrillBodyTest {

    @Test
    fun `round trips an empty body`() {
        assertThat(DrillBody.encode(emptyMap())).isEmpty()
        assertThat(DrillBody.decode("")).isEmpty()
    }

    @Test
    fun `round trips simple pairs`() {
        val body = mapOf("w" to "JH-DHN-001-W00042", "st" to "gas_rescue_decision", "c" to "1")
        assertThat(DrillBody.decode(DrillBody.encode(body))).isEqualTo(body)
    }

    @Test
    fun `escapes separators so a value cannot break out of its field`() {
        val body = mapOf("a" to "has;semicolon", "b" to "has=equals", "c" to "has\\backslash")
        val encoded = DrillBody.encode(body)
        assertThat(DrillBody.decode(encoded)).isEqualTo(body)
    }

    @Test
    fun `escaping survives every awkward combination`() {
        val nasty = listOf(";", "=", "\\", ";;", "==", "\\\\", "a;b=c\\d", "\\;", "\\=", ";=\\")
        for (value in nasty) {
            val body = mapOf("k" to value)
            assertThat(DrillBody.decode(DrillBody.encode(body))).isEqualTo(body)
        }
        for (key in nasty) {
            val body = mapOf(key to "v")
            assertThat(DrillBody.decode(DrillBody.encode(body))).isEqualTo(body)
        }
    }

    @Test
    fun `encoding is deterministic regardless of insertion order`() {
        val first = DrillBody.encode(linkedMapOf("z" to "1", "a" to "2"))
        val second = DrillBody.encode(linkedMapOf("a" to "2", "z" to "1"))
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `rejects a malformed body`() {
        assertThrows<CanonicalFormatException> { DrillBody.decode("novalue") }
        assertThrows<CanonicalFormatException> { DrillBody.decode("a=1=2") }
        assertThrows<CanonicalFormatException> { DrillBody.decode("=novalue") }
        assertThrows<CanonicalFormatException> { DrillBody.decode("a=1;bad") }
        assertThrows<CanonicalFormatException> { DrillBody.decode("a=trailing\\") }
        assertThrows<CanonicalFormatException> { DrillBody.decode("a=bad\\x") }
    }

    @Test
    fun `tolerates a trailing separator`() {
        assertThat(DrillBody.decode("a=1;")).isEqualTo(mapOf("a" to "1"))
    }
}

class DrillFrameCodecTest {

    private fun frame(
        type: DrillMessageType = DrillMessageType.ACTION,
        seq: Long = 7L,
        logicalMs: Long = 12_345L,
        body: Map<String, String> = mapOf("st" to "s1", "o" to "a,b"),
    ) = DrillFrame(
        protocolVersion = DrillFrameCodec.PROTOCOL_VERSION,
        type = type,
        senderSeq = seq,
        logicalMs = logicalMs,
        senderDeviceId = "device-1234-abcd",
        body = body,
    )

    @Test
    fun `round trips every message type`() {
        for (type in DrillMessageType.entries) {
            val original = frame(type = type)
            val decoded = DrillFrameCodec.decode(DrillFrameCodec.encode(original))

            assertThat(decoded.type).isEqualTo(type)
            assertThat(decoded.senderSeq).isEqualTo(original.senderSeq)
            assertThat(decoded.logicalMs).isEqualTo(original.logicalMs)
            assertThat(decoded.senderDeviceId).isEqualTo(original.senderDeviceId)
            assertThat(decoded.body).isEqualTo(original.body)
        }
    }

    @Test
    fun `body accessors parse typed values`() {
        val decoded = DrillFrameCodec.decode(
            DrillFrameCodec.encode(
                frame(body = mapOf("sc" to "842", "sd" to "9876543210", "o" to "a,b,c", "p" to "1")),
            ),
        )
        assertThat(decoded.bodyInt("sc")).isEqualTo(842)
        assertThat(decoded.bodyLong("sd")).isEqualTo(9_876_543_210L)
        assertThat(decoded.bodyList("o")).containsExactly("a", "b", "c").inOrder()
        assertThat(decoded.bodyValue("p")).isEqualTo("1")
        assertThat(decoded.bodyInt("missing")).isNull()
        assertThat(decoded.bodyList("missing")).isEmpty()
    }

    @Test
    fun `body list ignores empty entries`() {
        val decoded = DrillFrameCodec.decode(DrillFrameCodec.encode(frame(body = mapOf("o" to "a,,b,"))))
        assertThat(decoded.bodyList("o")).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `rejects an empty or truncated frame`() {
        assertThrows<CanonicalFormatException> { DrillFrameCodec.decode(ByteArray(0)) }

        val bytes = DrillFrameCodec.encode(frame())
        for (cut in listOf(1, 4, 8, bytes.size - 1)) {
            assertThrows<CanonicalFormatException> {
                DrillFrameCodec.decode(bytes.copyOfRange(0, cut))
            }
        }
    }

    @Test
    fun `rejects an oversized frame`() {
        val oversized = ByteArray(DrillFrameCodec.MAX_FRAME_BYTES + 1)
        assertThrows<CanonicalFormatException> { DrillFrameCodec.decode(oversized) }
    }

    @Test
    fun `rejects an unknown message type`() {
        val bytes = DrillFrameCodec.encode(frame())
        bytes[1] = 99
        val error = assertThrows<CanonicalFormatException> { DrillFrameCodec.decode(bytes) }
        assertThat(error).hasMessageThat().contains("unknown drill message type")
    }

    @Test
    fun `rejects trailing bytes`() {
        val bytes = DrillFrameCodec.encode(frame())
        assertThrows<CanonicalFormatException> {
            DrillFrameCodec.decode(bytes + byteArrayOf(0, 0))
        }
    }

    @Test
    fun `decodeOrNull swallows malformed frames`() {
        assertThat(DrillFrameCodec.decodeOrNull(ByteArray(3))).isNull()
        assertThat(DrillFrameCodec.decodeOrNull(DrillFrameCodec.encode(frame()))).isNotNull()
    }

    @Test
    fun `frame rejects invalid field values`() {
        assertThrows<IllegalArgumentException> { frame(seq = -1L) }
        assertThrows<IllegalArgumentException> { frame(seq = 0x1_0000_0000L) }
        assertThrows<IllegalArgumentException> { frame(logicalMs = -1L) }
        assertThrows<IllegalArgumentException> {
            DrillFrame(1, DrillMessageType.HELLO, 1L, 1L, " ")
        }
        assertThrows<IllegalArgumentException> {
            DrillFrame(1, DrillMessageType.HELLO, 1L, 1L, "x".repeat(65))
        }
        assertThrows<IllegalArgumentException> {
            DrillFrame(999, DrillMessageType.HELLO, 1L, 1L, "d")
        }
    }

    @Test
    fun `message type codes are unique and stable`() {
        val codes = DrillMessageType.entries.map { it.code }
        assertThat(codes).containsNoDuplicates()
        assertThat(codes).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        DrillMessageType.entries.forEach {
            assertThat(DrillMessageType.fromCode(it.code)).isEqualTo(it)
        }
        assertThat(DrillMessageType.fromCode(0)).isNull()
        assertThat(DrillMessageType.fromCode(200)).isNull()
    }

    @Test
    fun `encoding is deterministic`() {
        val original = frame()
        assertThat(DrillFrameCodec.encode(original)).isEqualTo(DrillFrameCodec.encode(original))
    }
}

class DrillConfigTest {

    @Test
    fun `default timings are internally consistent`() {
        val config = DrillConfig.DEFAULT
        assertThat(config.peerStaleAfterMs).isGreaterThan(config.heartbeatIntervalMs)
        assertThat(config.peerLostAfterMs).isGreaterThan(config.peerStaleAfterMs)
    }

    @Test
    fun `rejects inconsistent timings`() {
        assertThrows<IllegalArgumentException> { DrillConfig(heartbeatIntervalMs = 0) }
        assertThrows<IllegalArgumentException> {
            DrillConfig(heartbeatIntervalMs = 5_000, peerStaleAfterMs = 1_000)
        }
        assertThrows<IllegalArgumentException> {
            DrillConfig(peerStaleAfterMs = 9_000, peerLostAfterMs = 5_000)
        }
        assertThrows<IllegalArgumentException> { DrillConfig(maxBufferedFrames = 0) }
        assertThrows<IllegalArgumentException> { DrillConfig(distressWindowMs = 0) }
        assertThrows<IllegalArgumentException> { DrillConfig(seqMemory = 0) }
    }
}
