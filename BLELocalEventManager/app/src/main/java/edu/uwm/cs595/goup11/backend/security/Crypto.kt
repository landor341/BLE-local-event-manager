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
        return UUID.randomUUID().toString()
    }

    /**
     * Calculates a CRC32 checksum for a redundant data check
     */
    fun calculateChecksum(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /**
     * Generates a HMAC using SHA-256
     */
    fun generateMAC(data: ByteArray, key: ByteArray): ByteArray {
        val hmacKey = SecretKeySpec(key, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(data)
    }

    /**
     * Checks if provided MAC matches data using provided key
     */
    fun authenticateMAC(data: ByteArray, macToVerify: ByteArray, key: ByteArray): Boolean {
        val generatedMAC = generateMAC(data, key)
        return generatedMAC.contentEquals(macToVerify)
    }

    /**
     * Encrypts a message using AES in CBC mode with PKCS5 padding
     * The Initialization Vector (IV) is prepended to the ciphertext
     */
    fun encryptMessage(data: ByteArray, key: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

        // Generate a random IV
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val encryptedData = cipher.doFinal(data)

        // Prepend IV to encrypted data for decryption
        return iv + encryptedData
    }

    /**
     * Decrypts a message that was encrypted using encryptMessage
     */
    fun decryptMessage(encryptedData: ByteArray, key: ByteArray): ByteArray {
        if (encryptedData.size < 16) throw IllegalArgumentException("Invalid encrypted data")

        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

        // Extract IV from encrypted data (first 16 bytes)
        val iv = encryptedData.sliceArray(0 until 16)
        val actualEncryptedData = encryptedData.sliceArray(16 until encryptedData.size)
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(actualEncryptedData)
    }
}