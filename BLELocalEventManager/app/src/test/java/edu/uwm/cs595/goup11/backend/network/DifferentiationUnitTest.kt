package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DifferentiationUnitTest {

//    @After
//    fun purge() {
//        LocalNetwork.purge()
//    }
//
//    // --- User Role Differentiation Tests ---
//
//    @Test
//    fun message_preservesSenderRole_afterSerialization() {
//        val original = Message(
//            to = "B",
//            from = "A",
//            type = MessageType.TEXT_MESSAGE,
//            ttl = 5,
//            senderRole = UserRole.ADMIN
//        )
//
//        val bytes = original.toBytes()
//        val deserialized = Message.fromBytes(bytes)
//
//        assertEquals(UserRole.ADMIN, deserialized.senderRole)
//    }
//
//    @Test
//    fun client_sendsRole_inHelloMessage() = runTest {
//        val hostClient = Client("HOST", ClientType.ROUTER, role = UserRole.ADMIN)
//        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
//        hostNet.create("TEST_NET")
//
//        val leafClient = Client("LEAF", ClientType.LEAF, role = UserRole.PRESENTER)
//        val leafNet = LocalNetwork().apply { init(leafClient, Network.Config(5)) }
//
//        val receivedHello = CompletableDeferred<Message>()
//        hostNet.addListener { msg ->
//            if (msg.type == MessageType.HELLO) {
//                receivedHello.complete(msg)
//            }
//        }
//
//        leafClient.attachNetwork(leafNet, Network.Config(5))
//        leafClient.joinNetwork("TEST_NET")
//
//        val hello = receivedHello.await()
//        assertEquals(UserRole.PRESENTER, hello.senderRole)
//        assertEquals("LEAF", hello.from)
//    }
//
//    @Test
//    fun client_respondsWithRole_toPing() = runTest {
//        val hostClient = Client("HOST", ClientType.ROUTER, role = UserRole.ADMIN)
//        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
//        hostNet.create("TEST_NET")
//
//        val leafClient = Client("LEAF", ClientType.LEAF, role = UserRole.ATTENDEE)
//        val leafNet = LocalNetwork().apply { init(leafClient, Network.Config(5)) }
//        leafClient.attachNetwork(leafNet, Network.Config(5))
//        leafClient.joinNetwork("TEST_NET")
//
//        val ping = Message(
//            to = "LEAF",
//            from = "HOST",
//            type = MessageType.PING,
//            ttl = 5,
//            senderRole = UserRole.ADMIN
//        )
//
//        val responseDeferred = CompletableDeferred<Message>()
//        hostNet.addListener { msg ->
//            if (msg.type == MessageType.PONG) {
//                responseDeferred.complete(msg)
//            }
//        }
//
//        hostNet.sendMessage("LEAF", ping)
//
//        val pong = responseDeferred.await()
//        assertEquals(UserRole.ATTENDEE, pong.senderRole)
//        assertEquals("LEAF", pong.from)
//    }
//
//    // --- Presentation Differentiation Tests ---
//
//    @Test
//    fun message_preservesPresentationId_afterSerialization() {
//        val presentationId = "booth_123"
//        val original = Message(
//            to = "B",
//            from = "A",
//            type = MessageType.TEXT_MESSAGE,
//            ttl = 5,
//            presentationId = presentationId
//        )
//
//        val bytes = original.toBytes()
//        val deserialized = Message.fromBytes(bytes)
//
//        assertEquals(presentationId, deserialized.presentationId)
//    }
//
//    @Test
//    fun client_sendsPresentationId_inHelloMessage() = runTest {
//        val presentationId = "main_stage"
//        val hostClient = Client("HOST", ClientType.ROUTER)
//        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
//        hostNet.create("TEST_NET")
//
//        val presenterClient = Client("PRESENTER", ClientType.LEAF, presentationId = presentationId)
//        val presenterNet = LocalNetwork().apply { init(presenterClient, Network.Config(5)) }
//
//        val receivedHello = CompletableDeferred<Message>()
//        hostNet.addListener { msg ->
//            if (msg.type == MessageType.HELLO) {
//                receivedHello.complete(msg)
//            }
//        }
//
//        presenterClient.attachNetwork(presenterNet, Network.Config(5))
//        presenterClient.joinNetwork("TEST_NET")
//
//        val hello = receivedHello.await()
//        assertEquals(presentationId, hello.presentationId)
//        assertEquals("PRESENTER", hello.from)
//    }
//
//    @Test
//    fun message_withNullPresentationId_serializesCorrectly() {
//        val original = Message(
//            to = "B",
//            from = "A",
//            type = MessageType.TEXT_MESSAGE,
//            ttl = 5,
//            presentationId = null
//        )
//
//        val bytes = original.toBytes()
//        val deserialized = Message.fromBytes(bytes)
//
//        assertEquals(null, deserialized.presentationId)
//    }
}
