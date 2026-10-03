package org.jaagruk.safety.data.repo

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jaagruk.core.cert.OutcomeFlags
import org.jaagruk.core.util.FixedMonotonicTimeSource
import org.jaagruk.core.util.FixedWallClock
import org.jaagruk.safety.data.db.AssessmentRunEntity
import org.jaagruk.safety.sync.api.StepResultUpload
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingCertificateTest {
    private val database = TestDatabase.create()
    private val certificates = mockk<CertificateRepository>()
    private val seenFlags = mutableListOf<OutcomeFlags>()
    private val repository = AssessmentRepository(database, certificates, mockk(),
        FixedWallClock(1_760_000_000_000L), FixedMonotonicTimeSource(1000))

    init {
        coEvery { certificates.issue(any(), any(), any(), any(), any(), capture(seenFlags), any()) } returns
            CertificateRepository.IssueResult.NoSigningKey
    }

    @After fun cleanup() = database.close()

    private fun steps(method: String) = Json.encodeToString(listOf(
        StepResultUpload("step", "CORRECT", 1000, 2000, 10000, true, true, 1.0, method),
    ))

    private fun run(id: String, detail: String) = AssessmentRunEntity(
        runId = id, workerId = "worker", siteId = "site", moduleId = "fire", moduleCode = 1,
        scenarioId = "scenario", catalogVersion = 1, mode = "INITIAL",
        presentation = "ARCORE_GENERIC", completion = "COMPLETED", scorePermille = 900,
        passed = true, hesitationFlag = false, hesitationRatio = 0.0, medianLatencyMs = 1000,
        startedAtSec = 1, finishedAtSec = 2, totalDurationMs = 1000,
        stepsJson = detail, failedCriticalStepsJson = "[]",
    )

    @Test fun deferredVoiceAndGesturePreserveAssistedFlagButTouchDoesNot() = runTest {
        for (method in listOf("VOICE", "GESTURE", "TOUCH")) {
            database.assessmentRunDao().insert(run(method, steps(method)))
        }
        repository.issuePendingCertificates("site", false)
        assertThat(seenFlags.map { it.bits }).containsExactly(
            OutcomeFlags.NONE.with(OutcomeFlags.PASSED).with(OutcomeFlags.ASSISTED_MODE).bits,
            OutcomeFlags.NONE.with(OutcomeFlags.PASSED).with(OutcomeFlags.ASSISTED_MODE).bits,
            OutcomeFlags.NONE.with(OutcomeFlags.PASSED).bits,
        )
    }

    @Test fun unreadableOrMissingEvidenceStaysPendingWithoutBlockingValidRuns() = runTest {
        for ((id, detail) in listOf("bad" to "{broken", "empty" to "[]",
            "unknown" to steps("FUTURE_INPUT"), "valid" to steps("VOICE"))) {
            database.assessmentRunDao().insert(run(id, detail))
        }
        repository.issuePendingCertificates("site", false)
        coVerify(exactly = 1) { certificates.issue(any(), any(), any(), any(), any(), any(), "valid") }
        assertThat(seenFlags).hasSize(1)
        assertThat(database.assessmentRunDao().passedWithoutCertificate("site", 20)).hasSize(4)
    }
}
