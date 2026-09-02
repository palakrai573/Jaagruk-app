package org.jaagruk.safety.data.auth

import android.os.SystemClock
import android.util.Base64
import org.jaagruk.safety.data.db.WorkerDao
import org.jaagruk.safety.data.db.WorkerEntity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Offline PIN authentication for workers.
 *
 * A PIN, not a password, and no OTP. Workers register once where there is connectivity — at the
 * site office — and then log in every day underground where there is none. An OTP flow would need
 * live SMS on each login, which is exactly what is unavailable.
 *
 * PBKDF2-HMAC-SHA256 with a per-worker salt. Argon2id would be preferable, but it needs a native
 * library and PBKDF2 is in the platform; with a four-to-six digit PIN the real defence is the
 * lockout schedule below, not the KDF, so a dependency-free choice is the better trade.
 *
 * The lockout is stored twice, against both clocks, because either alone is trivially defeated:
 * winding the device clock back beats a wall-clock deadline, and a reboot resets an uptime-based
 * one. A lockout expires only when *both* have passed.
 */
class PinAuthenticator(private val workerDao: WorkerDao) {

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5

        /** Escalating lockouts, mirroring the backend's login policy. */
        val LOCKOUT_SCHEDULE_SECONDS = intArrayOf(30, 60, 300, 900)

        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_BYTES = 16
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"

        /** PINs that are effectively no protection at all. */
        private val TRIVIAL_PINS = setOf(
            "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
            "1234", "4321", "0123", "1230", "123456", "654321", "000000", "111111",
        )
    }

    sealed interface Result {
        data class Success(val worker: WorkerEntity) : Result

        data class WrongPin(val attemptsRemaining: Int) : Result

        /** Both deadlines are reported so the UI can show the longer of the two honestly. */
        data class LockedOut(val secondsRemaining: Long) : Result

        data object NoPinSet : Result

        data object UnknownWorker : Result
    }

    sealed interface PinValidity {
        data object Acceptable : PinValidity

        data class TooShort(val minimum: Int) : PinValidity

        data class TooLong(val maximum: Int) : PinValidity

        data object NotDigits : PinValidity

        /** Repeated or sequential digits. Refused with an explanation, not silently accepted. */
        data object TooGuessable : PinValidity
    }

    fun validate(pin: String): PinValidity = when {
        pin.length < MIN_PIN_LENGTH -> PinValidity.TooShort(MIN_PIN_LENGTH)
        pin.length > MAX_PIN_LENGTH -> PinValidity.TooLong(MAX_PIN_LENGTH)
        !pin.all(Char::isDigit) -> PinValidity.NotDigits
        pin in TRIVIAL_PINS -> PinValidity.TooGuessable
        pin.toSet().size == 1 -> PinValidity.TooGuessable
        isSequential(pin) -> PinValidity.TooGuessable
        else -> PinValidity.Acceptable
    }

    suspend fun setPin(workerId: String, pin: String): Boolean {
        if (validate(pin) !is PinValidity.Acceptable) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        workerDao.setPin(workerId, encode(hash), encode(salt))
        return true
    }

    suspend fun authenticate(workerId: String, pin: String): Result {
        val worker = workerDao.find(workerId) ?: return Result.UnknownWorker
        val storedHash = worker.pinHash
        val storedSalt = worker.pinSalt
        if (storedHash == null || storedSalt == null) return Result.NoPinSet

        remainingLockoutSeconds(worker)?.let { return Result.LockedOut(it) }

        val candidate = derive(pin, decode(storedSalt))
        if (MessageDigest.isEqual(candidate, decode(storedHash))) {
            workerDao.updateLockout(workerId, attempts = 0, 0L, 0L)
            return Result.Success(worker)
        }

        val attempts = worker.failedPinAttempts + 1
        if (attempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            val seconds = lockoutSecondsFor(attempts)
            workerDao.updateLockout(
                workerId = workerId,
                attempts = attempts,
                lockedUntilEpochMs = System.currentTimeMillis() + seconds * 1_000L,
                lockedUntilElapsedMs = SystemClock.elapsedRealtime() + seconds * 1_000L,
            )
            return Result.LockedOut(seconds.toLong())
        }

        workerDao.updateLockout(workerId, attempts, 0L, 0L)
        return Result.WrongPin(attemptsRemaining = MAX_ATTEMPTS_BEFORE_LOCKOUT - attempts)
    }

    /**
     * Seconds still to serve, or null when not locked.
     *
     * Takes the **larger** of the two remaining times. Rolling the clock back makes the wall-clock
     * deadline look distant, so it cannot shorten the lockout; rebooting resets the monotonic
     * deadline, so the wall-clock one still applies.
     */
    fun remainingLockoutSeconds(worker: WorkerEntity): Long? {
        val nowEpoch = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        val byWallClock = worker.lockedUntilEpochMs
            .takeIf { it > 0L }
            ?.let { (it - nowEpoch) / 1_000L }
            ?.coerceAtLeast(0L)
            ?: 0L

        val byUptime = worker.lockedUntilElapsedMs
            .takeIf { it > 0L && it > nowElapsed }
            ?.let { (it - nowElapsed) / 1_000L }
            ?.coerceAtLeast(0L)
            ?: 0L

        val remaining = maxOf(byWallClock, byUptime)
        return remaining.takeIf { it > 0L }
    }

    /**
     * Supervisor-authorised reset, for a forgotten PIN.
     *
     * Deliberately clears the PIN rather than setting a known one, so the worker chooses a new PIN
     * themselves and the supervisor never knows it. The caller writes an audit row.
     */
    suspend fun clearPinForReset(workerId: String) {
        workerDao.setPin(workerId, pinHash = "", pinSalt = "")
        workerDao.updateLockout(workerId, attempts = 0, 0L, 0L)
    }

    private fun lockoutSecondsFor(attempts: Int): Int {
        val over = (attempts - MAX_ATTEMPTS_BEFORE_LOCKOUT).coerceAtLeast(0)
        return LOCKOUT_SCHEDULE_SECONDS[over.coerceAtMost(LOCKOUT_SCHEDULE_SECONDS.lastIndex)]
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun isSequential(pin: String): Boolean {
        if (pin.length < 3) return false
        val ascending = pin.zipWithNext().all { (a, b) -> b.code == a.code + 1 }
        val descending = pin.zipWithNext().all { (a, b) -> b.code == a.code - 1 }
        return ascending || descending
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
