package edu.uwm.cs595.goup11.backend.network
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Test

import org.junit.Assert.*
class LocalNetworkUnitTest {

    /**
     * Summary of tests:
     *
     * Verify joining a network sends a HELLO message
     * Verify that sending a message HELLO to Mock4 returns a HELLO from Mock4
     */

    @Test
    fun joinNetwork_whenSuccessful_receivesRouterMessage() = runTest {
        val received = CompletableDeferred<Message>()

        // Create Mock Network
        var network = LocalNetwork()
        var client = Client(network, "TEST")

        network.init(client, Network.Config(5))



        client.addListener { msg ->
            // complete only once
            if (!received.isCompleted) received.complete(msg)
        }

        // Join the network
        network.join("");

        val msg = withTimeout(2_000) { received.await() }
        assertEquals("Expected a HELLO return message from router", MessageType.HELLO, msg.type)
    }

    @Test
    fun sendMessage_whenSuccessful_receivesHelloMessageFromMock() = runTest {
        val received = CompletableDeferred<Message>()

        // Create Mock Network
        var network = LocalNetwork()
        var client = Client(network, "TEST")

        network.init(client, Network.Config(5))




        client.addListener { msg ->
            if (!received.isCompleted &&
                msg.from == "Mock4" &&
                msg.type == MessageType.ACK
            ) {
                received.complete(msg)
            }
        }

        // Join the network
        network.join("");

        network.sendMessage(Message(
            "MOCK4",
            client.id,
            MessageType.HELLO,
            "Hello Mock4".toByteArray(),
            5
        ))

        val msg = withTimeout(10_000) { received.await() }
        assertEquals("Expected a HELLO return message from Mock4", MessageType.ACK, msg.type)
        assertEquals("Expected message from Mock4", "Mock4", msg.from)
    }
}