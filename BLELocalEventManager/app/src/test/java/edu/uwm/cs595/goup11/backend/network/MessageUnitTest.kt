package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.payloads.PingPayload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.*
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertNotNull


@RunWith(JUnit4::class)
class MessageUnitTest {

    @Test
    fun `Message can fully encode data class`() {
        val payload = PingPayload("TEST", 200, 55)
        val m = Message(
            to = "TEST",
            from = "TEST",
            ttl = 1,
            type = MessageType.ACK
        ).addBody(PingPayload.serializer(), payload);
        
        assertNotNull(m.data, "Data should contain a byte array")
    }


    @Test
    fun `Message can fully decode data class`() {
        val payload = PingPayload("TEST", 200, 55)
        val m = Message(
            to = "TEST",
            from = "TEST",
            ttl = 1,
            type = MessageType.ACK
        ).addBody(PingPayload.serializer(), payload);

        assertNotNull(m.data, "Data should contain a byte array")
    }
}
