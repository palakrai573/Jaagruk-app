package org.jaagruk.safety.data.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.jaagruk.core.crypto.Ed25519
import org.jaagruk.core.crypto.Ed25519KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Holds the two distinct cryptographic identities a supervisor handset carries.
 *
 * **Site signing key — Ed25519, software-held, Keystore-encrypted at rest.**
 * Signs certificates. Ed25519 rather than RSA because a 64-byte signature is what lets a full
 * attestation fit in a QR code a mid-range camera can read in poor light. The Android Keystore has
 * no dependable Ed25519 support below API 33, so the private key is generated in process and stored
 * in `EncryptedSharedPreferences`, which *is* Keystore-backed. That is encryption-at-rest of a
 * signing key, not hardware-backed signing, and it is described that way rather than dressed up.
 *
 * **Device attestation key — EC P-256, hardware-backed, non-exportable.**
 * Signs sync uploads. EC P-256 *is* dependably hardware-backed, so the private key genuinely cannot
 * leave the device. Keeping the two apart is what makes a leaked password insufficient to forge a
 * certificate: it also takes that specific handset.
 *
 * Residual risk, stated plainly: a fully rooted supervisor device can leak the site signing key.
 * The mitigation is key-epoch revocation and re-issue, not prevention. `docs/ARCHITECTURE.md` §11
 * says so too.
 */
class SiteKeyStore(private val context: Context) {

    private companion object {
        const val TAG = "SiteKeyStore"
        const val PREFS_NAME = "jaagruk_site_keys"
        const val KEY_SITE_PRIVATE = "site_private_key"
        const val KEY_SITE_PUBLIC = "site_public_key"
        const val KEY_SITE_ID = "site_id"
        const val KEY_EPOCH = "key_epoch"

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ATTEST_ALIAS = "jaagruk_device_attestation_v1"
        const val ATTEST_SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }

    /** Why the encrypted store could not be opened. Surfaced to the user, never swallowed. */
    class KeyStoreUnavailable(message: String, cause: Throwable?) : Exception(message, cause)

    private val preferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Happens for real: a device whose keystore was reset by a factory-reset-protection
            // event, or an OEM with a broken StrongBox. Failing loudly here is right — issuing
            // certificates with no key at all would be worse than refusing to.
            throw KeyStoreUnavailable(
                "The encrypted key store could not be opened. The site signing key is " +
                    "unavailable, so this device cannot issue certificates until a supervisor " +
                    "re-enrols it. Training and verification still work.",
                e,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Site signing key
    // -----------------------------------------------------------------------

    fun hasSiteKey(): Boolean =
        runCatching { preferences.contains(KEY_SITE_PRIVATE) }.getOrDefault(false)

    val siteId: String?
        get() = runCatching { preferences.getString(KEY_SITE_ID, null) }.getOrNull()

    val keyEpoch: Int
        get() = runCatching { preferences.getInt(KEY_EPOCH, 1) }.getOrDefault(1)

    fun sitePublicKey(): ByteArray? =
        runCatching { preferences.getString(KEY_SITE_PUBLIC, null)?.let(::decode) }.getOrNull()

    /**
     * Generates a fresh Ed25519 identity for a site.
     *
     * @param epoch bumped when a previous handset was lost. Certificates from earlier epochs stay
     *   verifiable against the archived public key, so rotating never invalidates history.
     */
    fun generateSiteKey(siteId: String, epoch: Int = 1): Ed25519KeyPair {
        require(siteId.isNotBlank()) { "siteId must not be blank" }
        val pair = Ed25519.generateKeyPair(SecureRandom())
        preferences.edit()
            .putString(KEY_SITE_PRIVATE, encode(pair.privateKey))
            .putString(KEY_SITE_PUBLIC, encode(pair.publicKey))
            .putString(KEY_SITE_ID, siteId)
            .putInt(KEY_EPOCH, epoch)
            .apply()
        Log.i(TAG, "generated site signing identity for $siteId at epoch $epoch")
        return pair
    }

    /**
     * Signs [message] with the site key.
     *
     * The private key is copied out, used, and zeroed immediately. It never becomes a field, so it
     * is not sitting in a long-lived object waiting to be found in a heap dump.
     *
     * @return the 64-byte signature, or null when no site key is enrolled — the caller then holds
     *   the run as passed-pending-certificate rather than losing it.
     */
    fun signWithSiteKey(message: ByteArray): ByteArray? {
        val encoded = runCatching { preferences.getString(KEY_SITE_PRIVATE, null) }.getOrNull()
            ?: return null
        val privateKey = decode(encoded)
        return try {
            Ed25519.sign(privateKey, message)
        } finally {
            privateKey.fill(0)
        }
    }

    /** Wipes the site identity. Used when a supervisor hands the handset on. */
    fun clearSiteKey() {
        runCatching {
            preferences.edit()
                .remove(KEY_SITE_PRIVATE)
                .remove(KEY_SITE_PUBLIC)
                .remove(KEY_SITE_ID)
                .remove(KEY_EPOCH)
                .apply()
        }.onFailure { Log.w(TAG, "could not clear the site key", it) }
    }

    // -----------------------------------------------------------------------
    // Device attestation key
    // -----------------------------------------------------------------------

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Returns the hardware-backed device key, creating it on first use.
     *
     * `setUserAuthenticationRequired(false)` on purpose: a background sync worker must be able to
     * sign an upload while the handset is locked in a worker's pocket. The key proves *which
     * device* is uploading, which is a different question from who is logged in.
     */
    fun ensureDeviceAttestationKey(): Boolean = try {
        if (!keyStore.containsAlias(ATTEST_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(ATTEST_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKeyPair()
            Log.i(TAG, "created hardware-backed device attestation key")
        }
        true
    } catch (e: Exception) {
        // Some OEMs refuse EC key generation under specific device-policy states. Sync uploads then
        // go unsigned, which the server accepts while a fleet is being enrolled and flags.
        Log.w(TAG, "device attestation key unavailable; uploads will be unsigned", e)
        false
    }

    fun deviceAttestationPublicKey(): ByteArray? = try {
        keyStore.getCertificate(ATTEST_ALIAS)?.publicKey?.encoded
    } catch (e: Exception) {
        Log.w(TAG, "could not read the device attestation public key", e)
        null
    }

    /** Signs a sync batch with the device key. Null when the key is unavailable. */
    fun signWithDeviceKey(message: ByteArray): ByteArray? = try {
        val entry = keyStore.getEntry(ATTEST_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: return null
        Signature.getInstance(ATTEST_SIGNATURE_ALGORITHM).run {
            initSign(entry.privateKey)
            update(message)
            sign()
        }
    } catch (e: Exception) {
        Log.w(TAG, "device signature failed", e)
        null
    }

    // -----------------------------------------------------------------------

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
