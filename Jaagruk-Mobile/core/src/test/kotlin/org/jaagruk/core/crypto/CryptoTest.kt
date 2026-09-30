package org.jaagruk.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import java.util.Random

class Ed25519Test {

    @Test
    fun `generates keys of the documented sizes`() {
        val pair = Ed25519.generateKeyPair()
        assertThat(pair.privateKey).hasLength(32)
        assertThat(pair.publicKey).hasLength(32)
    }

    @Test
    fun `signs and verifies`() {
        val pair = Ed25519.generateKeyPair()
        val message = "certificate payload".toByteArray()
        val signature = Ed25519.sign(pair.privateKey, message)

        assertThat(signature).hasLength(64)
        assertThat(Ed25519.verify(pair.publicKey, message, signature)).isTrue()
    }

    @Test
    fun `signatures are deterministic for the same key and message`() {
        // Ed25519 is deterministic by construction, which is what lets a certificate be
        // re-derived and compared byte for byte during a dispute.
        val pair = Ed25519.generateKeyPair()
        val message = "score=842".toByteArray()
        assertThat(Ed25519.sign(pair.privateKey, message))
            .isEqualTo(Ed25519.sign(pair.privateKey, message))
    }

    @Test
    fun `recovers the public key from the private key`() {
        val pair = Ed25519.generateKeyPair()
        assertThat(Ed25519.publicKeyFromPrivate(pair.privateKey)).isEqualTo(pair.publicKey)
    }

    @Test
    fun `rejects a tampered message`() {
        val pair = Ed25519.generateKeyPair()
        val message = "score=700".toByteArray()
        val signature = Ed25519.sign(pair.privateKey, message)
        val tampered = "score=900".toByteArray()

        assertThat(Ed25519.verify(pair.publicKey, tampered, signature)).isFalse()
    }

    @Test
    fun `rejects a single flipped bit anywhere in the message`() {
        val pair = Ed25519.generateKeyPair()
        val message = ByteArray(64) { it.toByte() }
        val signature = Ed25519.sign(pair.privateKey, message)

        for (index in message.indices) {
            val mutated = message.copyOf()
            mutated[index] = (mutated[index].toInt() xor 0x01).toByte()
            assertThat(Ed25519.verify(pair.publicKey, mutated, signature)).isFalse()
        }
    }

    @Test
    fun `rejects a signature from a different key`() {
        val issuer = Ed25519.generateKeyPair()
        val impostor = Ed25519.generateKeyPair()
        val message = "certificate".toByteArray()

        val forged = Ed25519.sign(impostor.privateKey, message)
        assertThat(Ed25519.verify(issuer.publicKey, message, forged)).isFalse()
    }

    @Test
    fun `verification returns false rather than throwing on malformed input`() {
        val pair = Ed25519.generateKeyPair()
        val message = "x".toByteArray()
        val signature = Ed25519.sign(pair.privateKey, message)

        assertThat(Ed25519.verify(ByteArray(31), message, signature)).isFalse()
        assertThat(Ed25519.verify(ByteArray(33), message, signature)).isFalse()
        assertThat(Ed25519.verify(pair.publicKey, message, ByteArray(63))).isFalse()
        assertThat(Ed25519.verify(pair.publicKey, message, ByteArray(0))).isFalse()
        assertThat(Ed25519.verify(ByteArray(32), message, signature)).isFalse()
    }

    @Test
    fun `signing rejects a wrong sized key`() {
        assertThrows<IllegalArgumentException> { Ed25519.sign(ByteArray(31), "x".toByteArray()) }
        assertThrows<IllegalArgumentException> {
            Ed25519.publicKeyFromPrivate(ByteArray(16))
        }
    }

    @Test
    fun `keypair constructor rejects wrong sizes`() {
        assertThrows<IllegalArgumentException> { Ed25519KeyPair(ByteArray(31), ByteArray(32)) }
        assertThrows<IllegalArgumentException> { Ed25519KeyPair(ByteArray(32), ByteArray(31)) }
    }

    @Test
    fun `wipe zeroes the private key`() {
        val pair = Ed25519.generateKeyPair()
        pair.wipePrivateKey()
        assertThat(pair.privateKey.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `handles an empty message`() {
        val pair = Ed25519.generateKeyPair()
        val signature = Ed25519.sign(pair.privateKey, ByteArray(0))
        assertThat(Ed25519.verify(pair.publicKey, ByteArray(0), signature)).isTrue()
    }

    @Test
    fun `works with a deterministic seeded random`() {
        // The fixture generator relies on this: identical seed, identical keypair.
        fun seeded(): Ed25519KeyPair {
            val random = SecureRandom.getInstance("SHA1PRNG")
            random.setSeed(byteArrayOf(1, 2, 3, 4))
            return Ed25519.generateKeyPair(random)
        }
        assertThat(seeded().publicKey).isEqualTo(seeded().publicKey)
    }
}

class Sha256Test {

    @Test
    fun `matches the known digest of the empty input`() {
        assertThat(org.jaagruk.core.util.Hex.encode(Sha256.hash(ByteArray(0))))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `matches the known digest of abc`() {
        assertThat(org.jaagruk.core.util.Hex.encode(Sha256.hashUtf8("abc")))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `concatenates parts in order`() {
        assertThat(Sha256.hash("ab".toByteArray(), "c".toByteArray()))
            .isEqualTo(Sha256.hashUtf8("abc"))
        assertThat(Sha256.hash("a".toByteArray(), "bc".toByteArray()))
            .isEqualTo(Sha256.hashUtf8("abc"))
        assertThat(Sha256.hash("c".toByteArray(), "ab".toByteArray()))
            .isNotEqualTo(Sha256.hashUtf8("abc"))
    }

    @Test
    fun `zero is 32 zero bytes and is detected`() {
        assertThat(Sha256.ZERO).hasLength(32)
        assertThat(Sha256.isZero(Sha256.ZERO)).isTrue()
        assertThat(Sha256.isZero(ByteArray(32) { 1 })).isFalse()
        assertThat(Sha256.isZero(ByteArray(31))).isFalse()
    }

    @Test
    fun `constant time equality agrees with content equality`() {
        val random = Random(11L)
        repeat(200) {
            val a = ByteArray(32).also { random.nextBytes(it) }
            val b = if (random.nextBoolean()) a.copyOf() else ByteArray(32).also { random.nextBytes(it) }
            assertThat(Sha256.constantTimeEquals(a, b)).isEqualTo(a.contentEquals(b))
        }
    }

    @Test
    fun `constant time equality rejects different lengths`() {
        assertThat(Sha256.constantTimeEquals(ByteArray(32), ByteArray(31))).isFalse()
        assertThat(Sha256.constantTimeEquals(ByteArray(0), ByteArray(0))).isTrue()
    }
}
