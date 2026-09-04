package org.jaagruk.safety.data.repo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.util.FixedWallClock
import org.jaagruk.core.util.Hex
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.testing.TestDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Offline worker enrolment — the path that makes a handset with no uplink usable at all.
 *
 * Without it the roster only ever arrives from the server, so a phone that has never had signal
 * shows an empty worker picker and nobody can train, be scored or be certified. These tests run
 * against a real in-memory Room database rather than a mock, because the thing worth checking is
 * that the queries actually execute.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkerRegistrationTest {

    private lateinit var database: JaagrukDatabase
    private lateinit var workers: WorkerRepository

    private val fixedClock = FixedWallClock(1_760_000_000_000L)

    private val siteId = "JH-DHN-001"
    private val validId = "JH-DHN-001-W00042"

    @Before
    fun setUp() {
        database = TestDatabase.create()

        workers = WorkerRepository(
            database = database,
            pinAuthenticator = PinAuthenticator(database.workerDao()),
            clock = fixedClock,
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun register(
        id: String = validId,
        name: String = "Budhan Manjhi",
        language: String = "hi",
    ) = workers.register(
        workerId = id,
        siteId = siteId,
        fullName = name,
        preferredLanguage = language,
        pictogramMode = false,
    )

    // -----------------------------------------------------------------------
    // The blocker: a fresh handset must be able to produce a usable roster
    // -----------------------------------------------------------------------

    @Test
    fun `a fresh database has no roster at all`() = runTest {
        assertThat(workers.all()).isEmpty()
    }

    @Test
    fun `registering offline puts a worker on the roster`() = runTest {
        val result = register()

        assertThat(result).isInstanceOf(WorkerRepository.RegisterResult.Registered::class.java)
        assertThat(workers.all()).hasSize(1)
        assertThat(workers.all().single().fullName).isEqualTo("Budhan Manjhi")
    }

    @Test
    fun `a newly registered worker has no PIN so they choose their own`() = runTest {
        register()

        assertThat(workers.hasPin(validId)).isFalse()
        // Which is what puts sign-in into its "set a first PIN" branch rather than asking for one
        // the supervisor would have had to invent and tell them.
        assertThat(workers.find(validId)!!.pinHash).isNull()
    }

    @Test
    fun `a registered worker can set a PIN and authenticate entirely offline`() = runTest {
        register()

        assertThat(workers.validatePin("4291"))
            .isEqualTo(PinAuthenticator.PinValidity.Acceptable)
        assertThat(workers.setPin(validId, "4291")).isTrue()

        assertThat(workers.authenticate(validId, "4291"))
            .isInstanceOf(PinAuthenticator.Result.Success::class.java)
        assertThat(workers.authenticate(validId, "9999"))
            .isInstanceOf(PinAuthenticator.Result.WrongPin::class.java)
    }

    // -----------------------------------------------------------------------
    // Identity: the id is hashed into every certificate, so its form matters
    // -----------------------------------------------------------------------

    @Test
    fun `a lower-case id is stored in the canonical upper-case form`() = runTest {
        val result = register(id = "jh-dhn-001-w00042")

        val worker = (result as WorkerRepository.RegisterResult.Registered).worker
        assertThat(worker.workerId).isEqualTo(validId)
    }

    @Test
    fun `the stored hash matches the canonical id an inspector reads off the card`() = runTest {
        // The server upper-cases the id, so the canonical id is the upper-case one. If the device
        // stored it as typed, the hash inside the certificate would not match the id an inspector
        // enters at the gate, and a worker who did nothing wrong would fail the identity check.
        register(id = "jh-dhn-001-w00042")

        val stored = workers.find(validId)!!
        val canonicalHash = Hex.encode(AttestationCodec.workerIdHash(validId))
        assertThat(stored.workerIdHash).isEqualTo(canonicalHash)
    }

    @Test
    fun `a worker is findable by the hash a certificate QR carries`() = runTest {
        register()

        val hash = Hex.encode(AttestationCodec.workerIdHash(validId))
        assertThat(workers.findByHash(hash)?.workerId).isEqualTo(validId)
    }

    @Test
    fun `an id the server would reject is refused at the point of entry`() = runTest {
        // Every one of these satisfies the old, looser rule (8+ chars of letters, digits, - and /)
        // and every one would have earned a 422 on the next sync — a definite verdict, so the
        // upload would have been abandoned rather than retried.
        val rejected = listOf(
            "ABCDEFGH",
            "JH-DHN-001",
            "JH-DHN-001-W0042",
            "JH-DHN-001-W000042",
            "JH-DHN-1-W00042",
            "J-DHN-001-W00042",
            "JH-DHN-001-X00042",
            "JH/DHN/001/W00042",
        )

        for (id in rejected) {
            val result = register(id = id)
            assertThat(result).isInstanceOf(WorkerRepository.RegisterResult.Invalid::class.java)
            assertThat((result as WorkerRepository.RegisterResult.Invalid).problem)
                .isEqualTo(WorkerRepository.Problem.BAD_ID_FORMAT)
        }
        assertThat(workers.all()).isEmpty()
    }

    @Test
    fun `the documented example is accepted`() = runTest {
        // The hint under the field shows this exact string. If it were not accepted, the app would
        // be telling supervisors to type something it refuses.
        val result = register(id = WorkerRepository.WORKER_ID_EXAMPLE)
        assertThat(result).isInstanceOf(WorkerRepository.RegisterResult.Registered::class.java)
    }

    @Test
    fun `a district code between two and six characters is accepted`() = runTest {
        assertThat(register(id = "JH-BK-002-W00001"))
            .isInstanceOf(WorkerRepository.RegisterResult.Registered::class.java)
        assertThat(register(id = "JH-SRKLA-003-W00002"))
            .isInstanceOf(WorkerRepository.RegisterResult.Registered::class.java)
    }

    // -----------------------------------------------------------------------
    // Refusals that name the field at fault
    // -----------------------------------------------------------------------

    @Test
    fun `an empty id is reported as empty rather than as a bad format`() = runTest {
        val result = register(id = "   ")
        assertThat((result as WorkerRepository.RegisterResult.Invalid).problem)
            .isEqualTo(WorkerRepository.Problem.EMPTY_ID)
    }

    @Test
    fun `a missing name is refused`() = runTest {
        val result = register(name = "  ")
        assertThat((result as WorkerRepository.RegisterResult.Invalid).problem)
            .isEqualTo(WorkerRepository.Problem.EMPTY_NAME)
    }

    @Test
    fun `a one-character name is refused as too short`() = runTest {
        val result = register(name = "R")
        assertThat((result as WorkerRepository.RegisterResult.Invalid).problem)
            .isEqualTo(WorkerRepository.Problem.NAME_TOO_SHORT)
    }

    @Test
    fun `enrolling without a site is refused because the site id is signed into certificates`() =
        runTest {
            val result = workers.register(
                workerId = validId,
                siteId = "",
                fullName = "Budhan Manjhi",
                preferredLanguage = "hi",
                pictogramMode = false,
            )
            assertThat((result as WorkerRepository.RegisterResult.Invalid).problem)
                .isEqualTo(WorkerRepository.Problem.NO_SITE)
        }

    @Test
    fun `registering the same worker twice does not duplicate or overwrite them`() = runTest {
        register(name = "Budhan Manjhi")
        val second = register(name = "Somebody Else")

        assertThat(second).isInstanceOf(WorkerRepository.RegisterResult.AlreadyExists::class.java)
        assertThat(workers.all()).hasSize(1)
        assertThat(workers.all().single().fullName).isEqualTo("Budhan Manjhi")
    }

    @Test
    fun `an existing worker is matched case-insensitively`() = runTest {
        register(id = validId)
        val second = register(id = "jh-dhn-001-w00042")

        assertThat(second).isInstanceOf(WorkerRepository.RegisterResult.AlreadyExists::class.java)
        assertThat(workers.all()).hasSize(1)
    }

    @Test
    fun `a PIN set before a duplicate registration survives it`() = runTest {
        register()
        workers.setPin(validId, "4291")

        register(name = "Somebody Else")

        assertThat(workers.hasPin(validId)).isTrue()
        assertThat(workers.authenticate(validId, "4291"))
            .isInstanceOf(PinAuthenticator.Result.Success::class.java)
    }

    // -----------------------------------------------------------------------
    // Reconciliation with the server
    // -----------------------------------------------------------------------

    @Test
    fun `an offline enrolment is queued for the server`() = runTest {
        register()

        assertThat(workers.countNotYetOnServer()).isEqualTo(1)
        assertThat(workers.notYetOnServer().single().workerId).isEqualTo(validId)
    }

    @Test
    fun `marking a worker synced removes them from the upload list`() = runTest {
        register()
        workers.markServerSynced(validId)

        assertThat(workers.countNotYetOnServer()).isEqualTo(0)
        assertThat(workers.notYetOnServer()).isEmpty()
    }

    @Test
    fun `marking synced does not disturb a PIN set in the meantime`() = runTest {
        register()
        workers.setPin(validId, "4291")
        workers.markServerSynced(validId)

        // The flag is flipped with a targeted UPDATE rather than a row rewrite precisely so a PIN
        // chosen while the upload was in flight is not clobbered.
        assertThat(workers.authenticate(validId, "4291"))
            .isInstanceOf(PinAuthenticator.Result.Success::class.java)
        assertThat(workers.find(validId)!!.serverSynced).isTrue()
    }

    @Test
    fun `pending enrolments come back oldest first`() = runTest {
        // The clock is advanced between enrolments on purpose: with three identical timestamps
        // SQLite is free to return any order, and a test that passes by luck is worse than none.
        register(id = "JH-DHN-001-W00001")
        fixedClock.millis += 60_000L
        register(id = "JH-DHN-001-W00002")
        fixedClock.millis += 60_000L
        register(id = "JH-DHN-001-W00003")

        val ids = workers.notYetOnServer().map { it.workerId }
        assertThat(ids).containsExactly(
            "JH-DHN-001-W00001",
            "JH-DHN-001-W00002",
            "JH-DHN-001-W00003",
        ).inOrder()
    }

    @Test
    fun `the upload list is capped so one pass cannot be unbounded`() = runTest {
        repeat(5) { index ->
            register(id = "JH-DHN-001-W%05d".format(index + 1))
            fixedClock.millis += 1_000L
        }

        assertThat(workers.notYetOnServer(limit = 2)).hasSize(2)
        assertThat(workers.countNotYetOnServer()).isEqualTo(5)
    }

    @Test
    fun `preferred language round-trips so sign-in can switch to it`() = runTest {
        register(id = "JH-DHN-001-W00007", language = "sat")

        assertThat(workers.find("JH-DHN-001-W00007")!!.preferredLanguage).isEqualTo("sat")
    }
}
