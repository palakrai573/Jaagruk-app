package org.jaagruk.core.crypto

import java.security.MessageDigest

/**
 * SHA-256 helpers.
 *
 * A fresh [MessageDigest] per call: the instances are stateful and not thread-safe, and
 * certificate issuance can race with a background chain verification.
 */
object Sha256 {

    const val SIZE_BYTES: Int = 32

    /** 32 zero bytes — the `prevRecordHash` of a site's genesis certificate. */
    val ZERO: ByteArray = ByteArray(SIZE_BYTES)

    fun hash(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part)
        }
        return digest.digest()
    }

    fun hashUtf8(text: String): ByteArray = hash(text.toByteArray(Charsets.UTF_8))

    fun isZero(hash: ByteArray): Boolean = hash.size == SIZE_BYTES && hash.all { it == 0.toByte() }

    /**
     * Length-independent, early-exit-free comparison.
     *
     * Chain and signature comparisons run on attacker-supplied input, so they use this
     * rather than [ByteArray.contentEquals] to avoid leaking a match prefix through timing.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
