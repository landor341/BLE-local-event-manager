package edu.uwm.cs595.goup11.backend.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class CryptoUnitTest {

    @Test
    fun generateUUID_isNotNullAndNotEmpty() {
        val uuid = Crypto.generateUUID()
        assertNotNull(uuid)
        assertTrue(uuid.isNotEmpty())
    }

    @Test
    fun calculateChecksum_isCorrectForTestData() {
        val data = "test_data".toByteArray()
        val expectedChecksum = 2827671980L
        assertEquals(expectedChecksum, Crypto.calculateChecksum(data))
    }

    @Test
    fun encryptDecrypt_roundTrip_recoversOriginalData() {
        val originalData = "This is a secret message.".toByteArray()
        val key = ByteArray(16)
        SecureRandom().nextBytes(key)

        val encryptedData = Crypto.encryptMessage(originalData, key)
        val decryptedData = Crypto.decryptMessage(encryptedData, key)

        assertArrayEquals(originalData, decryptedData)
    }

    @Test
    fun generateAndAuthenticateMAC_isSuccessful() {
        val data = "authenticate me".toByteArray()
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)

        val mac = Crypto.generateMAC(data, key)
        val isAuthenticated = Crypto.authenticateMAC(data, mac, key)

        assertTrue(isAuthenticated)
    }

    @Test
    fun authenticateMAC_failsForIncorrectData() {
        val originalData = "some data".toByteArray()
        val wrongData = "wrong data".toByteArray()
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)

        val mac = Crypto.generateMAC(originalData, key)
        val isAuthenticated = Crypto.authenticateMAC(wrongData, mac, key)

        assertFalse(isAuthenticated)
    }

    @Test
    fun authenticateMAC_failsForIncorrectKey() {
        val data = "some data".toByteArray()
        val key1 = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val key2 = ByteArray(32).apply { SecureRandom().nextBytes(this) }


        val mac = Crypto.generateMAC(data, key1)
        val isAuthenticated = Crypto.authenticateMAC(data, mac, key2)

        assertFalse(isAuthenticated)
    }
}
