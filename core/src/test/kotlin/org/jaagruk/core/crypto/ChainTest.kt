package org.jaagruk.core.crypto

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.TestFixtures
import org.jaagruk.core.cert.Attestation
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.cert.QrCodec
import org.jaagruk.core.cert.SignedAttestation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ChainHeadTest {

    @Test
    fun `empty head is seq zero with a zero hash`() {
        val head = ChainHead.empty(TestFixtures.SITE_ID)
        assertThat(head.isEmpty).isTrue()
        assertThat(head.lastSeq).isEqualTo(0L)
        assertThat(Sha256.isZero(head.lastRecordHash)).isTrue()
    }

    @Test
    fun `rejects inconsistent head states`() {
        assertThrows<IllegalArgumentException> {
            ChainHead(TestFixtures.SITE_ID, 0L, ByteArray(32) { 1 })
        }
        assertThrows<IllegalArgumentException> {
            ChainHead(TestFixtures.SITE_ID, 5L, Sha256.ZERO)
        }
        assertThrows<IllegalArgumentException> {
            ChainHead(TestFixtures.SITE_ID, -1L, Sha256.ZERO)
        }
        assertThrows<IllegalArgumentException> { ChainHead("  ", 0L, Sha256.ZERO) }
    }
}

class CertificateChainTest {

    @Test
    fun `first issuance is genesis`() {
        val head = ChainHead.empty(TestFixtures.SITE_ID)
        val append = issue(head, 1)

        assertThat(append.signed.attestation.seq).isEqualTo(1L)
        assertThat(Sha256.isZero(append.signed.attestation.prevRecordHash)).isTrue()
        assertThat(append.newHead.lastSeq).isEqualTo(1L)
        assertThat(append.newHead.lastRecordHash).isEqualTo(append.signed.recordHash)
    }

    @Test
    fun `each record links to its predecessor`() {
        var head = ChainHead.empty(TestFixtures.SITE_ID)
        val records = mutableListOf<SignedAttestation>()

        repeat(10) { index ->
            val append = issue(head, index + 1)
            records += append.signed
            head = append.newHead
        }

        for (index in 1 until records.size) {
            assertThat(records[index].attestation.prevRecordHash)
                .isEqualTo(records[index - 1].recordHash)
            assertThat(records[index].attestation.seq)
                .isEqualTo(records[index - 1].attestation.seq + 1)
        }
        assertThat(head.lastSeq).isEqualTo(10L)
    }

    @Test
    fun `nextSeq refuses to overflow the sequence space`() {
        val exhausted = ChainHead(TestFixtures.SITE_ID, Attestation.MAX_SEQ, ByteArray(32) { 1 })
        val error = assertThrows<ChainAppendException> { CertificateChain.nextSeq(exhausted) }
        assertThat(error).hasMessageThat().contains("exhausted")
    }

    @Test
    fun `advance accepts a correctly linked record`() {
        val head = ChainHead.empty(TestFixtures.SITE_ID)
        val first = issue(head, 1)
        val second = issue(first.newHead, 2)

        val advanced = CertificateChain.advance(first.newHead, second.signed)
        assertThat(advanced.lastSeq).isEqualTo(2L)
        assertThat(advanced.lastRecordHash).isEqualTo(second.signed.recordHash)
    }

    @Test
    fun `advance rejects an out of order sequence`() {
        val head = ChainHead.empty(TestFixtures.SITE_ID)
        val first = issue(head, 1)
        val third = issue(issue(first.newHead, 2).newHead, 3)

        val error = assertThrows<ChainAppendException> {
            CertificateChain.advance(first.newHead, third.signed)
        }
        assertThat(error).hasMessageThat().contains("out-of-order")
    }

    @Test
    fun `advance rejects a record from another site`() {
        val head = ChainHead.empty(TestFixtures.SITE_ID)
        val foreign = TestFixtures.sign(TestFixtures.attestation(siteId = TestFixtures.OTHER_SITE_ID))

        val error = assertThrows<ChainAppendException> { CertificateChain.advance(head, foreign) }
        assertThat(error).hasMessageThat().contains("belongs to site")
    }

    @Test
    fun `advance rejects a broken link`() {
        val head = ChainHead.empty(TestFixtures.SITE_ID)
        val first = issue(head, 1)
        val spliced = TestFixtures.sign(
            TestFixtures.attestation(seq = 2L, prevRecordHash = ByteArray(32) { 0x77 }),
        )

        val error = assertThrows<ChainAppendException> {
            CertificateChain.advance(first.newHead, spliced)
        }
        assertThat(error).hasMessageThat().contains("broken link")
    }

    private fun issue(head: ChainHead, workerIndex: Int): ChainAppend = CertificateChain.issue(
        head = head,
        sitePrivateKey = TestFixtures.SITE_PRIVATE_KEY,
        workerIdHash = TestFixtures.workerHash("${TestFixtures.SITE_ID}-W%05d".format(workerIndex)),
        moduleCode = 1,
        scorePermille = 780,
        medianLatencyMs = 2_100L,
        outcomeFlags = OutcomeFlags.of(OutcomeFlags.PASSED),
        issuedAtEpochMin = 29_400_000L + workerIndex,
    )
}

class ChainVerifierTest {

    private val fullChain = TestFixtures.chain(5)

    private fun viewOf(
        records: List<SignedAttestation>,
        publicKey: ByteArray? = TestFixtures.SITE_PUBLIC_KEY,
        siteId: String = TestFixtures.SITE_ID,
    ): ChainView = InMemoryChainView(
        publicKeys = if (publicKey == null) emptyMap() else mapOf(siteId to publicKey),
        records = mapOf(siteId to records.associate { it.attestation.seq to it.recordHash }),
    )

    @Test
    fun `verifies a record that links into a held chain`() {
        val result = ChainVerifier.verify(fullChain[2], viewOf(fullChain))

        assertThat(result.status).isEqualTo(ChainStatus.VERIFIED)
        assertThat(result.status.isTrustworthy).isTrue()
        assertThat(result.reasons.map { it.code })
            .containsAtLeast(VerificationReasonCode.SIGNATURE_OK, VerificationReasonCode.LINK_OK)
    }

    @Test
    fun `verifies a genesis record`() {
        val result = ChainVerifier.verify(fullChain[0], viewOf(fullChain))
        assertThat(result.status).isEqualTo(ChainStatus.VERIFIED)
    }

    @Test
    fun `reports an unknown site key rather than failing`() {
        val result = ChainVerifier.verify(fullChain[0], EmptyChainView)

        assertThat(result.status).isEqualTo(ChainStatus.UNKNOWN_SITE_KEY)
        assertThat(result.status.isTrustworthy).isFalse()
        assertThat(result.status.indicatesTampering).isFalse()
        assertThat(result.reasons.single().code)
            .isEqualTo(VerificationReasonCode.NO_PUBLIC_KEY_FOR_SITE)
    }

    @Test
    fun `reports partial trust when the key is known but the chain is not`() {
        // A DGMS inspector's first visit to a site: legitimate, and must not read as "invalid".
        val view = InMemoryChainView(
            publicKeys = mapOf(TestFixtures.SITE_ID to TestFixtures.SITE_PUBLIC_KEY),
        )
        val result = ChainVerifier.verify(fullChain[3], view)

        assertThat(result.status).isEqualTo(ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN)
        assertThat(result.status.isTrustworthy).isTrue()
        assertThat(result.reasons.map { it.code })
            .contains(VerificationReasonCode.NO_LOCAL_CHAIN_COPY)
    }

    @Test
    fun `reports a record newer than the local copy as partial trust`() {
        val view = viewOf(fullChain.take(2))
        val result = ChainVerifier.verify(fullChain[4], view)

        assertThat(result.status).isEqualTo(ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN)
        assertThat(result.reasons.map { it.code })
            .contains(VerificationReasonCode.AHEAD_OF_LOCAL_CHAIN)
    }

    @Test
    fun `detects a bad signature`() {
        val forged = SignedAttestation(
            attestation = fullChain[1].attestation.copy(scorePermille = 999),
            signature = fullChain[1].signature,
            recordHash = fullChain[1].recordHash,
        )
        val result = ChainVerifier.verify(forged, viewOf(fullChain))

        assertThat(result.status).isEqualTo(ChainStatus.BAD_SIGNATURE)
        assertThat(result.status.indicatesTampering).isTrue()
    }

    @Test
    fun `detects a record signed by the wrong site key`() {
        val impostorChain = TestFixtures.chain(2, privateKey = TestFixtures.IMPOSTOR_PRIVATE_KEY)
        val result = ChainVerifier.verify(impostorChain[0], viewOf(fullChain))
        assertThat(result.status).isEqualTo(ChainStatus.BAD_SIGNATURE)
    }

    @Test
    fun `detects an inserted record`() {
        // Correctly signed but pointing at the wrong predecessor: exactly what inserting a
        // backdated certificate into the middle of a ledger looks like.
        val spliced = TestFixtures.sign(
            TestFixtures.attestation(seq = 3L, prevRecordHash = ByteArray(32) { 0x33 }),
        )
        val result = ChainVerifier.verify(spliced, viewOf(fullChain.take(2)))

        assertThat(result.status).isEqualTo(ChainStatus.BROKEN_LINK)
        assertThat(result.status.indicatesTampering).isTrue()
        assertThat(result.reasons.map { it.code })
            .contains(VerificationReasonCode.PREDECESSOR_HASH_MISMATCH)
    }

    @Test
    fun `detects two different records in one sequence slot`() {
        val conflicting = TestFixtures.sign(
            TestFixtures.attestation(
                seq = 2L,
                scorePermille = 999,
                prevRecordHash = fullChain[0].recordHash,
            ),
        )
        val result = ChainVerifier.verify(conflicting, viewOf(fullChain))

        assertThat(result.status).isEqualTo(ChainStatus.BROKEN_LINK)
        assertThat(result.reasons.map { it.code })
            .contains(VerificationReasonCode.DUPLICATE_SEQUENCE_DIFFERENT_RECORD)
    }

    @Test
    fun `detects a record deleted from the middle`() {
        // Hold seq 1, 2, 4, 5 but not 3, then verify seq 4.
        val gapped = listOf(fullChain[0], fullChain[1], fullChain[3], fullChain[4])
        val result = ChainVerifier.verify(fullChain[3], viewOf(gapped))

        assertThat(result.status).isEqualTo(ChainStatus.SEQUENCE_GAP)
        assertThat(result.reasons.map { it.code })
            .containsAtLeast(
                VerificationReasonCode.PREDECESSOR_MISSING,
                VerificationReasonCode.SEQUENCE_GAP_DETECTED,
            )
    }

    @Test
    fun `reports malformed qr text`() {
        val result = ChainVerifier.verifyQr("not-a-certificate", viewOf(fullChain))

        assertThat(result.status).isEqualTo(ChainStatus.MALFORMED)
        assertThat(result.signed).isNull()
        assertThat(result.reasons.single().code).isEqualTo(VerificationReasonCode.DECODE_FAILED)
    }

    @Test
    fun `verifies straight from qr text`() {
        val qr = QrCodec.encode(fullChain[2])
        assertThat(ChainVerifier.verifyQr(qr, viewOf(fullChain)).status)
            .isEqualTo(ChainStatus.VERIFIED)
    }

    @Test
    fun `every status carries at least one reason`() {
        val cases = listOf(
            ChainVerifier.verify(fullChain[2], viewOf(fullChain)),
            ChainVerifier.verify(fullChain[2], EmptyChainView),
            ChainVerifier.verifyQr("garbage", viewOf(fullChain)),
        )
        cases.forEach { assertThat(it.reasons).isNotEmpty() }
    }
}

class ChainAuditTest {

    @Test
    fun `an empty chain audits clean`() {
        val result = ChainVerifier.auditChain(
            TestFixtures.SITE_ID,
            TestFixtures.SITE_PUBLIC_KEY,
            emptyList(),
        )
        assertThat(result.isClean).isTrue()
        assertThat(result.recordsChecked).isEqualTo(0)
    }

    @Test
    fun `a full valid chain audits clean`() {
        val records = TestFixtures.chain(25)
        val result = ChainVerifier.auditChain(
            TestFixtures.SITE_ID,
            TestFixtures.SITE_PUBLIC_KEY,
            records,
        )
        assertThat(result.isClean).isTrue()
        assertThat(result.recordsChecked).isEqualTo(25)
        assertThat(result.firstProblemSeq).isNull()
    }

    @Test
    fun `audit finds a missing record and names the sequence`() {
        val records = TestFixtures.chain(6).toMutableList()
        records.removeAt(3) // drop seq 4

        val result = ChainVerifier.auditChain(
            TestFixtures.SITE_ID,
            TestFixtures.SITE_PUBLIC_KEY,
            records,
        )
        assertThat(result.status).isEqualTo(ChainStatus.SEQUENCE_GAP)
        assertThat(result.firstProblemSeq).isEqualTo(4L)
    }

    @Test
    fun `audit finds a re-signed record`() {
        val records = TestFixtures.chain(4).toMutableList()
        records[2] = TestFixtures.sign(records[2].attestation, TestFixtures.IMPOSTOR_PRIVATE_KEY)

        val result = ChainVerifier.auditChain(
            TestFixtures.SITE_ID,
            TestFixtures.SITE_PUBLIC_KEY,
            records,
        )
        assertThat(result.status).isEqualTo(ChainStatus.BAD_SIGNATURE)
        assertThat(result.firstProblemSeq).isEqualTo(3L)
    }

    @Test
    fun `audit finds an altered score`() {
        val records = TestFixtures.chain(4).toMutableList()
        val original = records[1]
        records[1] = SignedAttestation(
            attestation = original.attestation.copy(scorePermille = 1_000),
            signature = original.signature,
            recordHash = original.recordHash,
        )

        val result = ChainVerifier.auditChain(
            TestFixtures.SITE_ID,
            TestFixtures.SITE_PUBLIC_KEY,
            records,
        )
        assertThat(result.status).isEqualTo(ChainStatus.BAD_SIGNATURE)
        assertThat(result.firstProblemSeq).isEqualTo(2L)
    }

    @Test
    fun `audit detects a foreign record spliced in`() {
        val records = TestFixtures.chain(3).toMutableList()
        records[1] = TestFixtures.sign(
            TestFixtures.attestation(siteId = TestFixtures.OTHER_SITE_ID, seq = 2L, prevRecordHash = records[0].recordHash),
        )

        val result = ChainVerifier.auditChain(
            TestFixtures.SITE_ID,
            TestFixtures.SITE_PUBLIC_KEY,
            records,
        )
        assertThat(result.status).isEqualTo(ChainStatus.BROKEN_LINK)
        assertThat(result.firstProblemSeq).isEqualTo(2L)
    }

    @Test
    fun `audit stops at the first problem`() {
        val records = TestFixtures.chain(10).toMutableList()
        records[2] = TestFixtures.sign(records[2].attestation, TestFixtures.IMPOSTOR_PRIVATE_KEY)

        val result = ChainVerifier.auditChain(
            TestFixtures.SITE_ID,
            TestFixtures.SITE_PUBLIC_KEY,
            records,
        )
        // Everything after an unexplained break is untrustworthy anyway, so checking further
        // would only produce noise.
        assertThat(result.recordsChecked).isEqualTo(3)
    }

    @Test
    fun `record hash chain is recomputable from stored records`() {
        // A dispute years later must be resolvable from stored data alone.
        val records = TestFixtures.chain(5)
        var previous = Sha256.ZERO
        records.forEach { record ->
            assertThat(record.attestation.prevRecordHash).isEqualTo(previous)
            val canonical = AttestationCodec.canonicalBytes(record.attestation)
            assertThat(AttestationCodec.recordHash(canonical, record.signature))
                .isEqualTo(record.recordHash)
            previous = record.recordHash
        }
    }
}
