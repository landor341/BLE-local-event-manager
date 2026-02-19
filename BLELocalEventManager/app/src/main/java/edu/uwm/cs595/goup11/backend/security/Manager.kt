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

    fun init() {
        if (!IS_INITIALIZED) {
            rotateKey()
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
}
