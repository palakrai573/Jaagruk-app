package org.jaagruk.safety.sync.nearby

import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.CanonicalReader
import org.jaagruk.core.util.CanonicalWriter

/**
 * Framing for the record-relay channel between two handsets.
 *
 * Distinct from the buddy-drill protocol in `:core`, and deliberately so: that one is a real-time
 * control protocol with sequencing, heartbeats and role election, and this is a bulk transfer of
 * records that are already signed. Reusing one for the other would give the transfer channel timing
 * requirements it does not have, and the drill channel a payload size it cannot afford.
 *
 * **Why relay at all.** Underground there is no network. A worker's handset can hold a week of
 * certificates with no way to deliver them. A supervisor who walks the section every shift *does*
 * surface, so the records ride out on their phone. The relay carries nothing that has to be trusted:
 * every certificate is Ed25519-signed and chain-linked, so a relaying handset cannot alter, forge or
 * selectively drop a record without it being evident at the server.
 *
 * Idempotency keys travel with the payload unchanged, which is what collapses the two delivery paths
 * — direct upload and relay — onto a single server-side row.
 */
class GossipFrame(
    val type: Type,
    val senderDeviceId: String,
    val siteId: String,
    /** Opaque to the transport. JSON for a record batch, empty for control frames. */
    val payload: String,
) {

    enum class Type(val code: Int) {
        /** Announces who this device is and how many records it is holding. */
        OFFER(1),

        /** The receiving device confirms it can accept and relay. */
        ACCEPT(2),

        /** One batch of queued records, as JSON. */
        RECORDS(3),

        /** The receiver confirms which idempotency keys it has taken responsibility for. */
        ACK(4),

        /** No more batches. */
        DONE(5),

        /** The receiver cannot help — wrong site, no capacity, not a supervisor handset. */
        DECLINE(6),
        ;

        companion object {
            private val byCode = entries.associateBy { it.code }

            fun fromCode(code: Int): Type? = byCode[code]
        }
    }

    init {
        require(senderDeviceId.isNotBlank()) { "senderDeviceId must not be blank" }
        require(senderDeviceId.length <= MAX_DEVICE_ID) {
            "senderDeviceId exceeds $MAX_DEVICE_ID characters"
        }
        require(siteId.length <= MAX_SITE_ID) { "siteId exceeds $MAX_SITE_ID characters" }
    }

    override fun toString(): String =
        "GossipFrame($type, from=$senderDeviceId, site=$siteId, ${payload.length} chars)"

    companion object {
        const val PROTOCOL_VERSION: Int = 1

        /**
         * Cap per frame.
         *
         * Nearby's own reliable-payload limit is far higher, but a 32 kB ceiling keeps a single
         * transfer inside a few seconds of Bluetooth throughput, which matters when the two people
         * holding the phones are standing in a haulage road and want to move on.
         */
        const val MAX_FRAME_BYTES: Int = 32 * 1024

        const val MAX_DEVICE_ID: Int = 64
        const val MAX_SITE_ID: Int = 16

        private const val MAGIC = "JGKG"

        fun encode(frame: GossipFrame): ByteArray {
            val bytes = CanonicalWriter(frame.payload.length + 128)
                .magic(MAGIC)
                .u8(PROTOCOL_VERSION)
                .u8(frame.type.code)
                .lp(frame.senderDeviceId, MAX_DEVICE_ID)
                .lp(frame.siteId, MAX_SITE_ID)
                .lp(frame.payload, MAX_FRAME_BYTES - 256)
                .toByteArray()

            check(bytes.size <= MAX_FRAME_BYTES) {
                "gossip frame grew to ${bytes.size} bytes, over the $MAX_FRAME_BYTES cap"
            }
            return bytes
        }

        /**
         * @throws CanonicalFormatException for a truncated, oversized or unknown frame.
         *
         * Length-prefixed throughout, so a transfer cut off mid-frame is a detectable error rather
         * than a plausible-looking half-message. The receiver drops it and the sender retries; nothing
         * is lost because the sender does not clear its queue until it sees an ACK.
         */
        fun decode(bytes: ByteArray): GossipFrame {
            if (bytes.isEmpty()) throw CanonicalFormatException("empty gossip frame")
            if (bytes.size > MAX_FRAME_BYTES) {
                throw CanonicalFormatException(
                    "gossip frame is ${bytes.size} bytes, over the $MAX_FRAME_BYTES cap",
                )
            }

            val reader = CanonicalReader(bytes)
            reader.magic(MAGIC)
            val version = reader.u8()
            if (version != PROTOCOL_VERSION) {
                throw CanonicalFormatException(
                    "gossip protocol version $version is not $PROTOCOL_VERSION; update one of the " +
                        "two handsets",
                )
            }
            val typeCode = reader.u8()
            val senderDeviceId = reader.lp(MAX_DEVICE_ID)
            val siteId = reader.lp(MAX_SITE_ID)
            val payload = reader.lp(MAX_FRAME_BYTES - 256)
            reader.requireExhausted()

            val type = Type.fromCode(typeCode)
                ?: throw CanonicalFormatException("unknown gossip frame type code $typeCode")

            return try {
                GossipFrame(type, senderDeviceId, siteId, payload)
            } catch (e: IllegalArgumentException) {
                throw CanonicalFormatException("gossip frame failed validation: ${e.message}", e)
            }
        }

        fun decodeOrNull(bytes: ByteArray): GossipFrame? = try {
            decode(bytes)
        } catch (e: CanonicalFormatException) {
            null
        }
    }
}
