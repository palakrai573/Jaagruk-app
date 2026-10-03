package org.jaagruk.safety.data.repo

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.util.FixedWallClock
import org.jaagruk.safety.data.db.WorkerEntity
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CertificateReplayTest {
    private val database = TestDatabase.create()
    private val keys = mockk<SiteKeyStore>()
    private val repository = CertificateRepository(database, keys, FixedWallClock(1_760_000_000_000L))

    @Before fun setup() = runTest {
        every { keys.hasSiteKey() } returns true
        every { keys.keyEpoch } returns 1
        // Signing is mocked; these tests cover persistence, not cryptographic verification.
        every { keys.signWithSiteKey(any()) } returns ByteArray(64) { 1 }
        database.workerDao().upsert(WorkerEntity("worker", "site", "Test worker", "01".repeat(32),
            "en", false, null, null, registeredAtSec = 1))
    }

    @After fun cleanup() = database.close()

    private suspend fun issue(score: Int = 900) = repository.issue("site", "worker", 1, score,
        1000, OutcomeFlags.NONE.with(OutcomeFlags.PASSED), "run-1")

    @Test fun repeatedIssuanceReusesCertificateWithoutAdvancingChain() = runTest {
        val first = issue() as CertificateRepository.IssueResult.Issued
        val second = issue() as CertificateRepository.IssueResult.Issued
        assertThat(second.certificate.certId).isEqualTo(first.certificate.certId)
        assertThat(second.certificate.qrText).isEqualTo(first.certificate.qrText)
        assertThat(repository.certificateCountForSite("site")).isEqualTo(1)
        assertThat(repository.observeChainHead("site")?.lastSeq).isEqualTo(1)
        verify(exactly = 1) { keys.signWithSiteKey(any()) }
    }

    @Test fun changedResultCannotReuseTheRunIdentity() = runTest {
        issue()
        assertThat(issue(950)).isInstanceOf(CertificateRepository.IssueResult.Rejected::class.java)
        assertThat(repository.certificateCountForSite("site")).isEqualTo(1)
        verify(exactly = 1) { keys.signWithSiteKey(any()) }
    }

    @Test fun existingCertificateCanBeReplayedWithoutSigningKey() = runTest {
        val original = issue() as CertificateRepository.IssueResult.Issued
        every { keys.hasSiteKey() } returns false
        val replay = issue() as CertificateRepository.IssueResult.Issued
        assertThat(replay.certificate).isEqualTo(original.certificate)
        verify(exactly = 1) { keys.signWithSiteKey(any()) }
    }

    @Test fun concurrentRetriesOnlyAppendOneRecord() = runTest {
        val results = List(4) { async { issue() as CertificateRepository.IssueResult.Issued } }.awaitAll()
        assertThat(results.map { it.certificate.certId }.distinct()).hasSize(1)
        assertThat(repository.observeChainHead("site")?.lastSeq).isEqualTo(1)
        verify(exactly = 1) { keys.signWithSiteKey(any()) }
    }

    @Test fun replayCannotChangeSignedFlags() = runTest {
        issue()
        val changed = repository.issue("site", "worker", 1, 900, 1000,
            OutcomeFlags.NONE.with(OutcomeFlags.PASSED).with(OutcomeFlags.SITE_SCANNED_AR), "run-1")
        assertThat(changed).isInstanceOf(CertificateRepository.IssueResult.Rejected::class.java)
        verify(exactly = 1) { keys.signWithSiteKey(any()) }
    }
}
