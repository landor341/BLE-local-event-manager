package edu.uwm.cs595.goup11.backend.security

import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles all data encryption/decryption, ID generation, and integrity checks
 */
object Crypto {

    /**
     * Generates a unique UUID
     */
    fun generateUUID(): String {

    }

    /**
     * Calculates a CRC32 checksum for a redundant data check
     */
    fun calculateChecksum(data: ByteArray): Long {

    }

    /**
     * Generates a HMAC using SHA-256
     */
    fun generateMAC(data: ByteArray, key: ByteArray): ByteArray {

    }

    /**
     * Checks if provided MAC matches data using provided key
     */
    fun authenticateMAC(data: ByteArray, macToVerify: ByteArray, key: ByteArray): Boolean {

    }

    /**
     * Encrypts a message using AES in CBC mode with PKCS5 padding
     * The Initialization Vector (IV) is prepended to the ciphertext
     */
    fun encryptMessage(data: ByteArray, key: ByteArray): ByteArray {

    }

    /**
     * Decrypts a message that was encrypted using encryptMessage
     */
    fun decryptMessage(encryptedData: ByteArray, key: ByteArray): ByteArray {

    }
}