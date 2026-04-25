package edu.uwm.cs595.goup11.backend.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerUnitTest {

    @Test
    fun init_setsIsInitialized() {
        Manager.init()
        assertTrue(Manager.isInitialized())
    }

    @Test
    fun getKey_returnsKeyAfterInit() {
        Manager.init()
        val key = Manager.getKey()
        assertNotNull(key)
        assertEquals(32, key.size) // Assuming 256-bit key
    }

    @Test
    fun rotateKey_changesTheKey() {
        Manager.init()
        val firstKey = Manager.getKey().copyOf()
        Manager.rotateKey()
        val secondKey = Manager.getKey()

        assertFalse(firstKey.contentEquals(secondKey))
    }
}
