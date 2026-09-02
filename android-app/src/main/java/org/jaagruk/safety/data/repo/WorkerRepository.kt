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
 * `serverSynced = false`, queued, and reconciled on the next bootstrap. A contractor arriving on
 * shift must be trainable immediately; making them wait for the uplink would mean they work
 * uncertified instead.
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

        data class Invalid(val reason: String) : RegisterResult
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
        val trimmedId = workerId.trim()
        val trimmedName = fullName.trim()

        if (trimmedId.isEmpty()) return RegisterResult.Invalid("worker id must not be empty")
        // The floor mirrors the server's own validation. Rejecting here means a supervisor is told
        // at the point of entry rather than discovering a 422 on the next sync, days later.
        if (trimmedId.length < MIN_WORKER_ID_LENGTH) {
            return RegisterResult.Invalid(
                "worker id must be at least $MIN_WORKER_ID_LENGTH characters " +
                    "(site prefix plus serial, as printed on the card)",
            )
        }
        if (trimmedId.length > MAX_WORKER_ID_LENGTH) {
            return RegisterResult.Invalid("worker id is longer than $MAX_WORKER_ID_LENGTH characters")
        }
        if (!trimmedId.all { it.isLetterOrDigit() || it == '-' || it == '/' }) {
            return RegisterResult.Invalid(
                "worker id may contain only letters, digits, '-' and '/'",
            )
        }
        if (trimmedName.isEmpty()) return RegisterResult.Invalid("name must not be empty")
        if (siteId.isBlank()) return RegisterResult.Invalid("this device is not enrolled to a site")

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

    companion object {
        /** Matches `worker_id` validation in `backend/app/schemas.py`. */
        const val MIN_WORKER_ID_LENGTH: Int = 8
        const val MAX_WORKER_ID_LENGTH: Int = 32
    }
}
