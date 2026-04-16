package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.security.Manager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test

class DifferentiationUnitTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private fun makeScope(): CoroutineScope {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes.add(s)
        return s
    }

    @Before
    fun setup() {
        LocalNetwork.purge()
        Manager.reset()
    }

    @After
    fun cleanup() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        LocalNetwork.purge()
        Manager.reset()
    }

    // --- User Role Differentiation Tests ---

    @Test
    fun message_preservesSenderRole_afterSerialization() {
        val original = Message(
            to = "B",
            from = "A",
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            senderRole = UserRole.ADMIN
        )

        val bytes = original.toBytes()
        val deserialized = Message.fromBytes(bytes)

        assertEquals(UserRole.ADMIN, deserialized.senderRole)
    }

    @Test
    fun client_sendsRole_inHelloMessage() = runBlocking {
        val hostScope = makeScope()
        val hostClient = Client("HOST", UserRole.ADMIN, scope = hostScope)
        val hostNet = LocalNetwork(scope = hostScope)
        hostClient.attachNetwork(hostNet, Network.Config(5))
        hostClient.createNetwork("TEST_NET", MeshTopology(
            keepaliveIntervalMs = 500,
            keepaliveTimeoutMs = 2000,
            discoveryIntervalMs = 500
        ))

        val leafScope = makeScope()
        val leafClient = Client("LEAF", UserRole.PRESENTER, scope = leafScope)
        val leafNet = LocalNetwork(scope = leafScope)
        leafClient.attachNetwork(leafNet, Network.Config(5))

        val hellos = Channel<Message>(Channel.UNLIMITED)
        hostNet.addListener { msg ->
            if (msg.type == MessageType.HELLO) {
                hellos.trySend(msg)
            }
        }

        leafScope.launch { leafClient.joinNetwork("TEST_NET") }

        // We might receive multiple HELLOs (one from each side connecting)
        // We look for the one from the LEAF
        var receivedHello: Message? = null
        withTimeoutOrNull(10000) {
            for (msg in hellos) {
                if (msg.from.contains("LEAF")) {
                    receivedHello = msg
                    break
                }
            }
        }

        assertNotNull("Should receive HELLO message from LEAF", receivedHello)
        assertEquals(UserRole.PRESENTER, receivedHello!!.senderRole)
    }

    @Test
    fun client_respondsWithRole_toPing() = runBlocking {
        val hostScope = makeScope()
        val hostClient = Client("HOST", UserRole.ADMIN, scope = hostScope)
        val hostNet = LocalNetwork(scope = hostScope)
        hostClient.attachNetwork(hostNet, Network.Config(5))
        hostClient.createNetwork("TEST_NET", MeshTopology(
            keepaliveIntervalMs = 500,
            keepaliveTimeoutMs = 2000,
            discoveryIntervalMs = 500
        ))

        val leafScope = makeScope()
        val leafClient = Client("LEAF", UserRole.ATTENDEE, scope = leafScope)
        val leafNet = LocalNetwork(scope = leafScope)
        leafClient.attachNetwork(leafNet, Network.Config(5))
        
        leafScope.launch { leafClient.joinNetwork("TEST_NET") }

        // Wait for connection
        var connected = false
        val deadline = System.currentTimeMillis() + 10_000
        while (!connected && System.currentTimeMillis() < deadline) {
            val id = leafClient.endpointId
            if (id != null) {
                val node = LocalNetwork.InMemoryNetworkHolder.getNode(id)
                if (node != null && node.connections.isNotEmpty()) connected = true
            }
            if (!connected) delay(100)
        }
        assertTrue("Should be connected", connected)

        val ping = Message(
            to = leafClient.endpointId!!,
            from = hostClient.endpointId!!,
            type = MessageType.PING,
            ttl = 5,
            senderRole = UserRole.ADMIN
        )

        val pongs = Channel<Message>(Channel.UNLIMITED)
        hostNet.addListener { msg ->
            if (msg.type == MessageType.PONG) {
                pongs.trySend(msg)
            }
        }

        hostClient.sendMessage(ping)

        val pong = withTimeoutOrNull(5000) { pongs.receive() }
        assertNotNull("Should receive PONG message", pong)
        assertEquals(UserRole.ATTENDEE, pong!!.senderRole)
        assertEquals(leafClient.endpointId, pong.from)
    }

    // --- Presentation Differentiation Tests ---

    @Test
    fun message_preservesPresentationId_afterSerialization() {
        val presentationId = "booth_123"
        val original = Message(
            to = "B",
            from = "A",
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            presentationId = presentationId
        )

        val bytes = original.toBytes()
        val deserialized = Message.fromBytes(bytes)

        assertEquals(presentationId, deserialized.presentationId)
    }

    @Test
    fun client_sendsPresentationId_inHelloMessage() = runBlocking {
        val presentationId = "main_stage"
        val hostScope = makeScope()
        val hostClient = Client("HOST", scope = hostScope)
        val hostNet = LocalNetwork(scope = hostScope)
        hostClient.attachNetwork(hostNet, Network.Config(5))
        hostClient.createNetwork("TEST_NET", MeshTopology(
            keepaliveIntervalMs = 500,
            keepaliveTimeoutMs = 2000,
            discoveryIntervalMs = 500
        ))

        val presenterScope = makeScope()
        val presenterClient = Client("PRESENTER", presentationId = presentationId, scope = presenterScope)
        val presenterNet = LocalNetwork(scope = presenterScope)
        presenterClient.attachNetwork(presenterNet, Network.Config(5))

        val hellos = Channel<Message>(Channel.UNLIMITED)
        hostNet.addListener { msg ->
            if (msg.type == MessageType.HELLO) {
                hellos.trySend(msg)
            }
        }

        presenterScope.launch { presenterClient.joinNetwork("TEST_NET") }

        var receivedHello: Message? = null
        withTimeoutOrNull(10000) {
            for (msg in hellos) {
                if (msg.from.contains("PRESENTER")) {
                    receivedHello = msg
                    break
                }
            }
        }

        assertNotNull("Should receive HELLO message from PRESENTER", receivedHello)
        assertEquals(presentationId, receivedHello!!.presentationId)
    }

    @Test
    fun message_withNullPresentationId_serializesCorrectly() {
        val original = Message(
            to = "B",
            from = "A",
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            presentationId = null
        )

        val bytes = original.toBytes()
        val deserialized = Message.fromBytes(bytes)

        assertEquals(null, deserialized.presentationId)
    }

    // --- Admin Verification Tests ---

    @Test
    fun message_preservesSignatureAndPublicKey_afterSerialization() {
        val original = Message(
            to = "B",
            from = "A",
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            senderRole = UserRole.ADMIN,
            signature = "sig-bytes".toByteArray(),
            senderPublicKey = "pub-key-bytes".toByteArray()
        )

        val bytes = original.toBytes()
        val deserialized = Message.fromBytes(bytes)

        assertArrayEquals("Signature should match", original.signature, deserialized.signature)
        assertArrayEquals("Public key should match", original.senderPublicKey, deserialized.senderPublicKey)
    }

    @Test
    fun adminMessage_isSignedAndVerified_byClient() = runBlocking {
        // Setup Host (Admin)
        val hostScope = makeScope()
        val hostClient = Client("HOST", UserRole.ADMIN, scope = hostScope)
        val hostNet = LocalNetwork(scope = hostScope)
        hostClient.attachNetwork(hostNet, Network.Config(5))
        hostClient.createNetwork("VERIFY_NET", MeshTopology())

        // Setup Attendee
        val attendeeScope = makeScope()
        val attendeeClient = Client("ATTENDEE", UserRole.ATTENDEE, scope = attendeeScope)
        val attendeeNet = LocalNetwork(scope = attendeeScope)
        attendeeClient.attachNetwork(attendeeNet, Network.Config(5))
        
        attendeeScope.launch { attendeeClient.joinNetwork("VERIFY_NET") }

        // Wait for connection and key exchange
        delay(2000)

        val receivedMessages = Channel<Message>(Channel.UNLIMITED)
        attendeeClient.addMessageListener { receivedMessages.trySend(it) }

        // Admin sends a signed message
        val adminMsg = Message(
            to = "ALL",
            from = hostClient.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            data = "Signed Admin Bulletin".toByteArray(),
            ttl = 5
        )
        hostClient.sendMessage(adminMsg)

        val received = withTimeoutOrNull(5000) { receivedMessages.receive() }
        assertNotNull("Attendee should receive message", received)
        assertEquals("Content should match", "Signed Admin Bulletin", String(received!!.data!!))
        assertNotNull("Message should have a signature", received.signature)
        assertNotNull("Message should have a sender public key", received.senderPublicKey)
        
        // Manual verification check using Manager
        val isValid = Manager.verify(
            received.data!!,
            received.signature!!,
            received.senderPublicKey!!
        )
        assertTrue("Signature must be cryptographically valid", isValid)
    }
}
