package edu.uwm.cs595.goup11.backed.security

/**
 * Handles all of the managing of encryption keys, certs, and other security data that is stored
 * locally
 *
 * This class is a singleton and has only one instance. [init] should be called on startup
 */
class Manager {

    companion object ManagerInstance {
        private var IS_INITIALIZED = false
        fun init() {
            IS_INITIALIZED = true
            TODO("Setup")
        }

        fun getKey() {
            TODO("Finish implementation")
        }

        fun rotateKey() {
            TODO("Finish implementation")
        }

    }
}