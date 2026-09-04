package org.jaagruk.safety.data.repo

import kotlinx.coroutines.flow.Flow
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.util.Hex
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.db.WorkerEntity

/**
 * The local roster and the worker sign-in path.
 *
 * A worker's daily login is a local PIN check against a row already on the device, not a server
 * call. That is not a shortcut: sign-in happens at the start of a shift, underground, on a handset
 * with no signal. Anything requiring a round trip — OTP, password, OAuth — simply does not work
 * there, so the design puts registration where connectivity exists and authentication where it does
 * not.
 *
 * A worker can also be registered on the device with no connectivity at all. The row is marked
 * `serverSynced = false` and [notYetOnServer] hands it to `SyncWorker`, which posts it the moment
 * there is an uplink and then flips the flag. A contractor arriving on shift must be trainable
 * immediately; making them wait for the uplink would mean they work uncertified instead.
 *
 * The PIN never travels. It is set on the handset, verified on the handset, and is not part of the
 * upload — the server has no business holding it and could not check it offline anyway.
 */
class WorkerRepository(
    private val database: JaagrukDatabase,
    private val pinAuthenticator: PinAuthenticator,
    private val clock: WallClock,
) {

    private val workers = database.workerDao()

    fun observe(workerId: String): Flow<WorkerEntity?> = workers.observe(workerId)

    fun observeForSite(siteId: String): Flow<List<WorkerEntity>> = workers.observeForSite(siteId)

    suspend fun find(workerId: String): WorkerEntity? = workers.find(workerId)

    suspend fun all(): List<WorkerEntity> = workers.all()

    /** Looks a worker up from the hash a certificate QR carries. */
    suspend fun findByHash(workerIdHashHex: String): WorkerEntity? =
        workers.findByHash(workerIdHashHex.lowercase())

    suspend fun countWithPin(): Int = workers.countWithPin()

    /** Outcome of a local registration. Every failure names the field at fault. */
    sealed interface RegisterResult {
        data class Registered(val worker: WorkerEntity) : RegisterResult

        data class AlreadyExists(val worker: WorkerEntity) : RegisterResult

        data class Invalid(val problem: Problem) : RegisterResult
    }

    /**
     * Why a registration was refused.
     *
     * An enum rather than a message string so the reason survives the trip to the UI layer and is
     * shown in the supervisor's own language. A repository that returned English prose would make
     * every enrolment error untranslatable, which on this project is a functional defect.
     */
    enum class Problem {
        EMPTY_ID,
        BAD_ID_FORMAT,
        EMPTY_NAME,
        NAME_TOO_SHORT,
        NO_SITE,
    }

    /**
     * Registers a worker on this handset.
     *
     * The worker id is supplied by the site — it is their existing employee number, which is what an
     * inspector will read off a physical card and hash to confirm a scanned certificate. Generating
     * one here would break that check.
     */
    suspend fun register(
        workerId: String,
        siteId: String,
        fullName: String,
        preferredLanguage: String,
        pictogramMode: Boolean,
    ): RegisterResult {
        // Upper-cased before anything else, because the server upper-cases too and the worker id is
        // hashed into every certificate this worker earns. A row stored as typed would hash to a
        // different value than the canonical id an inspector reads off the physical card, and the
        // identity check on a scanned certificate would fail for a worker who did nothing wrong.
        val trimmedId = workerId.trim().uppercase()
        val trimmedName = fullName.trim()

        if (trimmedId.isEmpty()) return RegisterResult.Invalid(Problem.EMPTY_ID)
        // The same pattern the server enforces, character for character. Anything looser means a
        // supervisor enrols somebody the server will reject with a 422 on every future sync — and
        // because a 422 is a definite verdict rather than a retryable one, that worker's uploads
        // would be abandoned rather than retried.
        if (!WORKER_ID_PATTERN.matches(trimmedId)) {
            return RegisterResult.Invalid(Problem.BAD_ID_FORMAT)
        }
        if (trimmedName.isEmpty()) return RegisterResult.Invalid(Problem.EMPTY_NAME)
        if (trimmedName.length < MIN_NAME_LENGTH) {
            return RegisterResult.Invalid(Problem.NAME_TOO_SHORT)
        }
        if (siteId.isBlank()) return RegisterResult.Invalid(Problem.NO_SITE)

        workers.find(trimmedId)?.let { return RegisterResult.AlreadyExists(it) }

        val entity = WorkerEntity(
            workerId = trimmedId,
            siteId = siteId,
            fullName = trimmedName,
            workerIdHash = Hex.encode(AttestationCodec.workerIdHash(trimmedId)),
            preferredLanguage = preferredLanguage,
            pictogramMode = pictogramMode,
            pinHash = null,
            pinSalt = null,
            registeredAtSec = clock.epochSeconds(),
            serverSynced = false,
            active = true,
        )
        workers.upsert(entity)
        return RegisterResult.Registered(entity)
    }

    suspend fun updatePreferences(
        workerId: String,
        preferredLanguage: String,
        pictogramMode: Boolean,
    ) {
        val worker = workers.find(workerId) ?: return
        workers.upsert(
            worker.copy(
                preferredLanguage = preferredLanguage,
                pictogramMode = pictogramMode,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // PIN
    // -----------------------------------------------------------------------

    fun validatePin(pin: String): PinAuthenticator.PinValidity = pinAuthenticator.validate(pin)

    suspend fun setPin(workerId: String, pin: String): Boolean =
        pinAuthenticator.setPin(workerId, pin)

    suspend fun authenticate(workerId: String, pin: String): PinAuthenticator.Result =
        pinAuthenticator.authenticate(workerId, pin)

    /** Seconds of lockout still to serve, or null when the worker can attempt a PIN. */
    suspend fun lockoutRemainingSeconds(workerId: String): Long? {
        val worker = workers.find(workerId) ?: return null
        return pinAuthenticator.remainingLockoutSeconds(worker)
    }

    /**
     * Supervisor-authorised PIN reset.
     *
     * Clears rather than sets, so the worker chooses the replacement themselves and the supervisor
     * never learns it. Returns false when the worker is unknown, so the caller can report that
     * instead of silently doing nothing.
     */
    suspend fun clearPinForReset(workerId: String): Boolean {
        workers.find(workerId) ?: return false
        pinAuthenticator.clearPinForReset(workerId)
        return true
    }

    suspend fun hasPin(workerId: String): Boolean =
        workers.find(workerId)?.pinHash?.isNotBlank() == true

    // -----------------------------------------------------------------------
    // Reconciliation with the server
    // -----------------------------------------------------------------------

    /** Workers enrolled offline on this handset that the server has never seen. */
    suspend fun notYetOnServer(limit: Int = UPLOAD_BATCH): List<WorkerEntity> =
        workers.notYetOnServer(limit)

    suspend fun countNotYetOnServer(): Int = workers.countNotYetOnServer()

    suspend fun markServerSynced(workerId: String) = workers.markServerSynced(workerId)

    companion object {
        /**
         * The worker id format, mirroring `WORKER_ID_PATTERN` in `backend/app/schemas.py`.
         *
         * State code, district code, three-digit site serial, then `W` and a five-digit worker
         * serial — `JH-DHN-001-W00042`. It is the number already printed on the worker's card,
         * which is what an inspector reads and hashes to confirm a scanned certificate, so the app
         * must not invent its own shape for it.
         */
        val WORKER_ID_PATTERN = Regex("^[A-Z]{2}-[A-Z0-9]{2,6}-[0-9]{3}-W[0-9]{5}$")

        /** An example in the exact accepted shape, for the hint under the field. */
        const val WORKER_ID_EXAMPLE: String = "JH-DHN-001-W00042"

        /** Mirrors `full_name` min_length in `backend/app/schemas.py`. */
        const val MIN_NAME_LENGTH: Int = 2

        /** How many offline enrolments to push per sync pass. */
        const val UPLOAD_BATCH: Int = 25
    }
}
