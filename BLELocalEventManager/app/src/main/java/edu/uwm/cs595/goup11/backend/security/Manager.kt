package edu.uwm.cs595.goup11.backend.security

import edu.uwm.cs595.goup11.backend.security.Manager.init
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature

/**
 * Handles all of the managing of encryption keys, certs, and other security data that is stored
 * locally
 *
 * This class is a singleton and has only one instance. [init] should be called on startup
 */
object Manager {

    private var IS_INITIALIZED = false
    private lateinit var key: ByteArray

    // Asymmetric keys for signing (Identity verification)
    private var keyPair: KeyPair? = null

    /**
     * Initializes the manager. If [providedKey] is not null, it will be used as the encryption key.
     * Otherwise, a new random key will be generated.
     */
    fun init(providedKey: ByteArray? = null) {
        if (!IS_INITIALIZED) {
            if (providedKey != null) {
                key = providedKey
            } else {
                rotateKey()
            }

            // Always generate an identity key pair on initialization if it doesn't exist
            if (keyPair == null) {
                generateIdentityKeys()
            }

            IS_INITIALIZED = true
        }
    }

    fun isInitialized(): Boolean = IS_INITIALIZED

    fun getKey(): ByteArray {
        if (!IS_INITIALIZED) throw IllegalStateException("Manager not initialized")
        return key
    }

    fun rotateKey() {
        key = ByteArray(32)
        SecureRandom().nextBytes(key)
    }

    /**
     * Generates an RSA key pair for digital signatures
     */
    private fun generateIdentityKeys() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        keyPair = kpg.generateKeyPair()
    }

    /**
     * Signs data using the local private key
     */
    fun sign(data: ByteArray): ByteArray {
        val privateKey =
            keyPair?.private ?: throw IllegalStateException("Identity keys not generated")
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Verifies a signature using a provided public key
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val kf = java.security.KeyFactory.getInstance("RSA")
            val publicKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the local public key for distribution to peers
     */
    fun getPublicKey(): ByteArray? {
        return keyPair?.public?.encoded
    }

    /**
     * Resets the manager state. (Mainly for testing or leaving a network)
     */
    fun reset() {
        IS_INITIALIZED = false
        keyPair = null
    }
}
