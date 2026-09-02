package org.jaagruk.core.crypto

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** A raw Ed25519 keypair. Both halves are 32 bytes; the signature is 64. */
class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == Ed25519.PRIVATE_KEY_SIZE) {
            "Ed25519 private key must be ${Ed25519.PRIVATE_KEY_SIZE} bytes, got ${privateKey.size}"
        }
        require(publicKey.size == Ed25519.PUBLIC_KEY_SIZE) {
            "Ed25519 public key must be ${Ed25519.PUBLIC_KEY_SIZE} bytes, got ${publicKey.size}"
        }
    }

    /** Zeroes the private key in place. Call after persisting or after a signing burst. */
    fun wipePrivateKey() {
        privateKey.fill(0)
    }
}

/**
 * Ed25519 over BouncyCastle's lightweight API.
 *
 * Two deliberate choices:
 *
 *  * **Ed25519 rather than RSA** — a 64-byte signature is the reason a full certificate
 *    attestation fits in a QR code that a mid-range camera can read in poor light. A
 *    2048-bit RSA signature alone would be 256 bytes and would push the symbol past
 *    practical scanning density.
 *  * **Lightweight API rather than JCE** — `org.bouncycastle.crypto.*` needs no
 *    `Security.addProvider` call. Registering a JCE provider on Android collides with the
 *    platform's own trimmed BouncyCastle and is a classic source of
 *    `NoSuchAlgorithmException` on exactly one OEM's firmware.
 *
 * Verification never throws. A forged or corrupt signature is a `false`, because callers
 * must branch on trust, not on exception handling.
 */
object Ed25519 {

    const val PRIVATE_KEY_SIZE: Int = 32
    const val PUBLIC_KEY_SIZE: Int = 32
    const val SIGNATURE_SIZE: Int = 64

    fun generateKeyPair(random: SecureRandom = SecureRandom()): Ed25519KeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(random))
        val pair = generator.generateKeyPair()
        val private = (pair.private as Ed25519PrivateKeyParameters).encoded
        val public = (pair.public as Ed25519PublicKeyParameters).encoded
        return Ed25519KeyPair(private, public)
    }

    /** Recovers the public half, so only the 32-byte private key needs to be stored. */
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "Ed25519 private key must be $PRIVATE_KEY_SIZE bytes, got ${privateKey.size}"
        }
        return Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "Ed25519 private key must be $PRIVATE_KEY_SIZE bytes, got ${privateKey.size}"
        }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()
        check(signature.size == SIGNATURE_SIZE) {
            "unexpected Ed25519 signature size ${signature.size}"
        }
        return signature
    }

    /**
     * @return true only if [signature] is a valid Ed25519 signature over [message] by the
     *   holder of [publicKey]. Wrong sizes, malformed keys and internal failures all
     *   return false rather than propagating.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE) return false
        if (signature.size != SIGNATURE_SIZE) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: IllegalArgumentException) {
            // Malformed key encoding (e.g. a point not on the curve).
            false
        } catch (e: IllegalStateException) {
            false
        }
    }
}
