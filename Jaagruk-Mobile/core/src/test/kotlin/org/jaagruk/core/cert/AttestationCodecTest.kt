package org.jaagruk.core.cert

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.TestFixtures
import org.jaagruk.core.crypto.Sha256
import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.CanonicalWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OutcomeFlagsTest {

    @Test
    fun `exposes each named flag`() {
        val flags = OutcomeFlags.of(
            OutcomeFlags.PASSED,
            OutcomeFlags.BUDDY_DRILL,
            OutcomeFlags.SITE_SCANNED_AR,
        )
        assertThat(flags.passed).isTrue()
        assertThat(flags.buddyDrill).isTrue()
        assertThat(flags.siteScannedAr).isTrue()
        assertThat(flags.hesitationFlagged).isFalse()
        assertThat(flags.refresher).isFalse()
        assertThat(flags.assistedMode).isFalse()
    }

    @Test
    fun `with and without are non destructive`() {
        val base = OutcomeFlags.of(OutcomeFlags.PASSED)
        val added = base.with(OutcomeFlags.HESITATION)
        assertThat(base.hesitationFlagged).isFalse()
        assertThat(added.hesitationFlagged).isTrue()
        assertThat(added.without(OutcomeFlags.HESITATION)).isEqualTo(base)
    }

    @Test
    fun `rejects reserved bits`() {
        // Forward-compatibility guard: a payload built against a future format must be refused,
        // not silently reinterpreted under today's rules.
        val error = assertThrows<IllegalArgumentException> { OutcomeFlags.fromBits(0x40) }
        assertThat(error).hasMessageThat().contains("reserved")
        assertThrows<IllegalArgumentException> { OutcomeFlags.fromBits(0x80) }
        assertThrows<IllegalArgumentException> { OutcomeFlags.fromBits(0xC0) }
    }

    @Test
    fun `rejects values outside u8`() {
        assertThrows<IllegalArgumentException> { OutcomeFlags.fromBits(-1) }
        assertThrows<IllegalArgumentException> { OutcomeFlags.fromBits(256) }
    }

    @Test
    fun `accepts every combination of defined bits`() {
        for (bits in 0..OutcomeFlags.ALL_MASK) {
            assertThat(OutcomeFlags.fromBits(bits).bits).isEqualTo(bits)
        }
    }

    @Test
    fun `toString names the set flags`() {
        val text = OutcomeFlags.of(OutcomeFlags.PASSED, OutcomeFlags.REFRESHER).toString()
        assertThat(text).contains("passed")
        assertThat(text).contains("refresher")
    }
}

class AttestationValidationTest {

    @Test
    fun `accepts a well formed genesis record`() {
        val attestation = TestFixtures.attestation(seq = 1L, prevRecordHash = Sha256.ZERO)
        assertThat(attestation.isGenesis).isTrue()
        assertThat(attestation.scorePercent).isWithin(1e-9).of(84.2)
    }

    @Test
    fun `requires a zero predecessor for genesis`() {
        val error = assertThrows<IllegalArgumentException> {
            TestFixtures.attestation(seq = 1L, prevRecordHash = ByteArray(32) { 9 })
        }
        assertThat(error).hasMessageThat().contains("genesis")
    }

    @Test
    fun `requires a non zero predecessor after genesis`() {
        val error = assertThrows<IllegalArgumentException> {
            TestFixtures.attestation(seq = 2L, prevRecordHash = Sha256.ZERO)
        }
        assertThat(error).hasMessageThat().contains("non-genesis")
    }

    @Test
    fun `rejects a site id over the qr budget`() {
        val error = assertThrows<IllegalArgumentException> {
            TestFixtures.attestation(siteId = "JH-DHANBAD-BLOCK-2-SEAM-7")
        }
        assertThat(error).hasMessageThat().contains("QR budget")
    }

    @Test
    fun `rejects a blank site id`() {
        assertThrows<IllegalArgumentException> { TestFixtures.attestation(siteId = "  ") }
    }

    @Test
    fun `rejects an out of range sequence`() {
        assertThrows<IllegalArgumentException> { TestFixtures.attestation(seq = 0L) }
        assertThrows<IllegalArgumentException> { TestFixtures.attestation(seq = -1L) }
        assertThrows<IllegalArgumentException> {
            TestFixtures.attestation(seq = 0x1_0000_0000L, prevRecordHash = ByteArray(32) { 1 })
        }
    }

    @Test
    fun `rejects an out of range score`() {
        assertThrows<IllegalArgumentException> { TestFixtures.attestation(scorePermille = 1_001) }
        assertThrows<IllegalArgumentException> { TestFixtures.attestation(scorePermille = -1) }
    }

    @Test
    fun `rejects module code zero`() {
        assertThrows<IllegalArgumentException> { TestFixtures.attestation(moduleCode = 0) }
        assertThrows<IllegalArgumentException> { TestFixtures.attestation(moduleCode = 256) }
    }

    @Test
    fun `rejects a wrong sized worker hash`() {
        assertThrows<IllegalArgumentException> {
            Attestation(
                siteId = TestFixtures.SITE_ID,
                seq = 1L,
                workerIdHash = ByteArray(16),
                moduleCode = 1,
                scorePermille = 800,
                medianLatencyMs = 1_000L,
                outcomeFlags = OutcomeFlags.NONE,
                issuedAtEpochMin = 1L,
                prevRecordHash = Sha256.ZERO,
            )
        }
    }

    @Test
    fun `equality is by value including byte arrays`() {
        val a = TestFixtures.attestation()
        val b = TestFixtures.attestation()
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(TestFixtures.attestation(scorePermille = 843))
    }

    @Test
    fun `copy preserves unspecified fields`() {
        val original = TestFixtures.attestation()
        val copied = original.copy(scorePermille = 900)
        assertThat(copied.scorePermille).isEqualTo(900)
        assertThat(copied.siteId).isEqualTo(original.siteId)
        assertThat(copied.workerIdHash).isEqualTo(original.workerIdHash)
    }

    @Test
    fun `toString never leaks a full worker hash`() {
        val text = TestFixtures.attestation().toString()
        assertThat(text).doesNotContain(org.jaagruk.core.util.Hex.encode(TestFixtures.workerHash()))
    }
}

class AttestationCodecTest {

    @Test
    fun `canonical encoding is deterministic`() {
        val attestation = TestFixtures.attestation()
        assertThat(AttestationCodec.canonicalBytes(attestation))
            .isEqualTo(AttestationCodec.canonicalBytes(attestation))
    }

    @Test
    fun `canonical encoding has the documented length`() {
        // magic 4 + version 1 + lp(siteId) 2+10 + seq 4 + workerHash 32 + module 1 + score 2
        // + latency 4 + flags 1 + issuedAt 4 + prevHash 32 = 97
        val bytes = AttestationCodec.canonicalBytes(TestFixtures.attestation())
        assertThat(bytes).hasLength(97)
    }

    @Test
    fun `canonical bytes start with the magic marker and version`() {
        val bytes = AttestationCodec.canonicalBytes(TestFixtures.attestation())
        assertThat(String(bytes.copyOfRange(0, 4), Charsets.US_ASCII)).isEqualTo("JGKA")
        assertThat(bytes[4].toInt()).isEqualTo(Attestation.FORMAT_VERSION)
    }

    @Test
    fun `round trips through canonical bytes`() {
        val attestation = TestFixtures.attestation(
            seq = 12_345L,
            flags = OutcomeFlags.of(
                OutcomeFlags.PASSED,
                OutcomeFlags.HESITATION,
                OutcomeFlags.BUDDY_DRILL,
                OutcomeFlags.ASSISTED_MODE,
            ),
            prevRecordHash = ByteArray(32) { (it * 3).toByte() },
        )
        val decoded = AttestationCodec.decodeCanonical(AttestationCodec.canonicalBytes(attestation))
        assertThat(decoded).isEqualTo(attestation)
    }

    @Test
    fun `body plus header reconstructs the canonical bytes`() {
        val attestation = TestFixtures.attestation()
        val canonical = AttestationCodec.canonicalBytes(attestation)
        val body = AttestationCodec.bodyBytes(attestation)

        assertThat(body).hasLength(canonical.size - AttestationCodec.HEADER_SIZE)
        assertThat(AttestationCodec.canonicalFromBody(attestation.formatVersion, body))
            .isEqualTo(canonical)
    }

    @Test
    fun `record hash commits to the signature`() {
        val attestation = TestFixtures.attestation()
        val real = TestFixtures.sign(attestation)
        val impostor = TestFixtures.sign(attestation, TestFixtures.IMPOSTOR_PRIVATE_KEY)

        // Same payload, different signer: the chain link must differ, otherwise a record could be
        // re-signed and spliced into an existing chain without breaking it.
        assertThat(real.recordHash).isNotEqualTo(impostor.recordHash)
    }

    @Test
    fun `verifies a genuine signature`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        assertThat(AttestationCodec.verifySignature(signed, TestFixtures.SITE_PUBLIC_KEY)).isTrue()
    }

    @Test
    fun `rejects a signature from another site`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        assertThat(AttestationCodec.verifySignature(signed, TestFixtures.IMPOSTOR_PUBLIC_KEY))
            .isFalse()
    }

    @Test
    fun `rejects a doctored record hash even when the signature is valid`() {
        val signed = TestFixtures.sign(TestFixtures.attestation())
        val doctored = SignedAttestation(
            attestation = signed.attestation,
            signature = signed.signature,
            recordHash = ByteArray(32) { 0x5A },
        )
        assertThat(AttestationCodec.verifySignature(doctored, TestFixtures.SITE_PUBLIC_KEY))
            .isFalse()
    }

    @Test
    fun `rejects a tampered score`() {
        val signed = TestFixtures.sign(TestFixtures.attestation(scorePermille = 705))
        val forged = SignedAttestation(
            attestation = signed.attestation.copy(scorePermille = 995),
            signature = signed.signature,
            recordHash = signed.recordHash,
        )
        assertThat(AttestationCodec.verifySignature(forged, TestFixtures.SITE_PUBLIC_KEY)).isFalse()
    }

    @Test
    fun `decode rejects a bad magic marker`() {
        val canonical = AttestationCodec.canonicalBytes(TestFixtures.attestation())
        canonical[0] = 'X'.code.toByte()
        assertThrows<CanonicalFormatException> { AttestationCodec.decodeCanonical(canonical) }
    }

    @Test
    fun `decode rejects an unsupported format version`() {
        val canonical = AttestationCodec.canonicalBytes(TestFixtures.attestation())
        canonical[4] = 9
        val error = assertThrows<CanonicalFormatException> {
            AttestationCodec.decodeCanonical(canonical)
        }
        assertThat(error).hasMessageThat().contains("not supported")
    }

    @Test
    fun `decode rejects reserved flag bits`() {
        // Rebuild by hand so the reserved bits bypass the OutcomeFlags factory.
        val attestation = TestFixtures.attestation()
        val forged = CanonicalWriter()
            .magic(AttestationCodec.MAGIC)
            .u8(attestation.formatVersion)
            .lp(attestation.siteId, Attestation.MAX_SITE_ID_BYTES)
            .u32(attestation.seq)
            .fixed(attestation.workerIdHash, 32)
            .u8(attestation.moduleCode)
            .u16(attestation.scorePermille)
            .u32(attestation.medianLatencyMs)
            .u8(0xC1)
            .u32(attestation.issuedAtEpochMin)
            .fixed(attestation.prevRecordHash, 32)
            .toByteArray()

        val error = assertThrows<CanonicalFormatException> {
            AttestationCodec.decodeCanonical(forged)
        }
        assertThat(error).hasMessageThat().contains("reserved")
    }

    @Test
    fun `decode rejects truncation and trailing bytes`() {
        val canonical = AttestationCodec.canonicalBytes(TestFixtures.attestation())
        assertThrows<CanonicalFormatException> {
            AttestationCodec.decodeCanonical(canonical.copyOfRange(0, canonical.size - 1))
        }
        assertThrows<CanonicalFormatException> {
            AttestationCodec.decodeCanonical(canonical + byteArrayOf(0))
        }
    }

    @Test
    fun `worker id hash is sha256 of the utf8 id`() {
        assertThat(AttestationCodec.workerIdHash(TestFixtures.WORKER_ID))
            .isEqualTo(Sha256.hashUtf8(TestFixtures.WORKER_ID))
    }

    @Test
    fun `worker id hash rejects a blank id`() {
        assertThrows<IllegalArgumentException> { AttestationCodec.workerIdHash("   ") }
    }

    @Test
    fun `matches the worker id printed on a card`() {
        val attestation = TestFixtures.attestation(workerId = "JH-DHN-001-W00042")
        assertThat(AttestationCodec.matchesWorkerId(attestation, "JH-DHN-001-W00042")).isTrue()
        assertThat(AttestationCodec.matchesWorkerId(attestation, "JH-DHN-001-W00043")).isFalse()
        assertThat(AttestationCodec.matchesWorkerId(attestation, "")).isFalse()
    }
}
