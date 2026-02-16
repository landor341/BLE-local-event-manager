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


    /**
     * When joining the network, the client should receive a HELLO message from the router
     */
    @Test
    fun joinNetwork_whenSuccessful_receivesRouterMessage() = runTest {
        val received = CompletableDeferred<Message>()

        // Create Mock Network
        var network = LocalNetwork()
        var client = Client("TEST", ClientType.LEAF)
        network.init(client, Network.Config(5))

//        network.init(client, Network.Config(5))
//
//
//
//        client.addListener { msg ->
//            // complete only once
//            if (!received.isCompleted) received.complete(msg)
//        }
//
//        // Join the network
//        network.join("");
//
//        val msg = withTimeout(2_000) { received.await() }
//        assertEquals("Expected a HELLO return message", MessageType.HELLO, msg.type)
//        assertEquals("Expected a HELLO return message from router", "ROUTER1", msg.from)
    }

    /**
     * When sending a HELLO message, the client should receive a ACK message from the client it
     * was sent too
     */
    @Test
    fun sendMessage_whenSuccessful_receivesHelloMessageFromMock() = runTest {
//        val received = CompletableDeferred<Message>()
//
//        // Create Mock Network
//        var network = LocalNetwork()
//        var client = Client(network, "TEST")
//
//        network.init(client, Network.Config(5))
//
//
//
//
//        client.addListener { msg ->
//            if (!received.isCompleted &&
//                msg.from == "MOCK4" &&
//                msg.type == MessageType.ACK
//            ) {
//                received.complete(msg)
//            }
//        }
//
//        // Join the network
//        network.join("");
//
//        network.sendMessage(Message(
//            "MOCK4",
//            client.id,
//            MessageType.HELLO,
//            "Hello Mock4".toByteArray(),
//            5
//        ))
//
//        val msg = withTimeout(10_000) { received.await() }
//        assertEquals("Expected a HELLO return message from Mock4", MessageType.ACK, msg.type)
//        assertEquals("Expected message from Mock4", "MOCK4", msg.from)
    }

    /**
     * When joining a network, the client should receive a list of all clients on that
     * network
     */
    @Test
    fun joinNetwork_onSuccess_clientShouldReceiveDirectory() {

    }
}