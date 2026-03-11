package edu.uwm.cs595.goup11.backend.security

import java.security.SecureRandom

/**
 * Handles all of the managing of encryption keys, certs, and other security data that is stored
 * locally
 *
 * This class is a singleton and has only one instance. [init] should be called on startup
 */
object Manager {

    private var IS_INITIALIZED = false
    private lateinit var key: ByteArray

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
     * Resets the manager state. (Mainly for testing or leaving a network)
     */
    fun reset() {
        IS_INITIALIZED = false
    }
}
