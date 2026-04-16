package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import edu.uwm.cs595.goup11.backend.security.Manager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MessageRoutingUnitTest {

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

    /**
     * Test case: Presentation Isolation
     * An ATTENDEE on "Presentation A" should NOT receive a broadcast message sent to "Presentation B".
     */
    @Test
    fun message_isFiltered_byPresentationId() = runBlocking {
        val hostScope = makeScope()
        val hostClient = Client("HOST", scope = hostScope)
        val hostNet = LocalNetwork(scope = hostScope)
        hostClient.attachNetwork(hostNet, Network.Config(5))
        hostClient.createNetwork("TEST_NET", MeshTopology())

        val attendeeAScope = makeScope()
        val attendeeA = Client("ATTENDEE_A", presentationId = "Presentation_A", scope = attendeeAScope)
        attendeeA.attachNetwork(LocalNetwork(scope = attendeeAScope), Network.Config(5))
        attendeeAScope.launch { attendeeA.joinNetwork("TEST_NET") }

        val attendeeBScope = makeScope()
        val attendeeB = Client("ATTENDEE_B", presentationId = "Presentation_B", scope = attendeeBScope)
        attendeeB.attachNetwork(LocalNetwork(scope = attendeeBScope), Network.Config(5))
        attendeeBScope.launch { attendeeB.joinNetwork("TEST_NET") }

        // Wait for connections
        delay(3000)

        val messagesA = Channel<Message>(Channel.UNLIMITED)
        val messagesB = Channel<Message>(Channel.UNLIMITED)
        attendeeA.addMessageListener { messagesA.trySend(it) }
        attendeeB.addMessageListener { messagesB.trySend(it) }

        // Host sends broadcast for Presentation B
        val broadcastMsg = Message(
            to = "ALL",
            from = hostClient.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            presentationId = "Presentation_B"
        )
        hostClient.sendMessage(broadcastMsg)

        // Attendee B should receive it, Attendee A should not
        val receivedB = withTimeoutOrNull(2000) { messagesB.receive() }
        val receivedA = withTimeoutOrNull(2000) { messagesA.receive() }

        assertNotNull("Attendee B should receive message for their presentation", receivedB)
        assertNull("Attendee A should NOT receive message for a different presentation", receivedA)
    }

    /**
     * Test case: Admin Override
     * An ADMIN should receive broadcast messages regardless of their assigned presentationId.
     */
    @Test
    fun admin_receivesMessages_fromAllPresentations() = runBlocking {
        val hostScope = makeScope()
        val hostClient = Client("HOST", role = UserRole.ADMIN, scope = hostScope)
        val hostNet = LocalNetwork(scope = hostScope)
        hostClient.attachNetwork(hostNet, Network.Config(5))
        hostClient.createNetwork("TEST_NET", MeshTopology())

        val adminScope = makeScope()
        val adminClient = Client("ADMIN_USER", role = UserRole.ADMIN, presentationId = "Presentation_A", scope = adminScope)
        adminClient.attachNetwork(LocalNetwork(scope = adminScope), Network.Config(5))
        adminScope.launch { adminClient.joinNetwork("TEST_NET") }

        delay(3000)

        val receivedMessages = Channel<Message>(Channel.UNLIMITED)
        adminClient.addMessageListener { receivedMessages.trySend(it) }

        // Send broadcast for Presentation B
        val msg = Message(
            to = "ALL",
            from = hostClient.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            presentationId = "Presentation_B"
        )
        hostClient.sendMessage(msg)

        val received = withTimeoutOrNull(2000) { receivedMessages.receive() }
        assertNotNull("Admin should receive message even if presentationId mismatch", received)
    }

    /**
     * Test case: Broadcast Forwarding even if presentation mismatch
     */
    @Test
    fun broadcast_isForwarded_evenIfPresentationMismatch() = runBlocking {
        val scopeA = makeScope()
        val clientA = Client("NODE_A", presentationId = "PresA", scope = scopeA)
        clientA.attachNetwork(LocalNetwork(scope = scopeA), Network.Config(5))
        clientA.createNetwork("FWD_TEST", SnakeTopology(maxPeerCount = 1))

        val scopeB = makeScope()
        val clientB = Client("NODE_B", presentationId = "PresB", scope = scopeB)
        clientB.attachNetwork(LocalNetwork(scope = scopeB), Network.Config(5))
        scopeB.launch { clientB.joinNetwork("FWD_TEST") }

        delay(2000)

        val scopeC = makeScope()
        val clientC = Client("NODE_C", presentationId = "PresA", scope = scopeC)
        clientC.attachNetwork(LocalNetwork(scope = scopeC), Network.Config(5))
        scopeC.launch { clientC.joinNetwork("FWD_TEST") }

        delay(4000)

        val messagesC = Channel<Message>(Channel.UNLIMITED)
        clientC.addMessageListener { messagesC.trySend(it) }

        val msg = Message(
            to = "ALL",
            from = clientA.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            presentationId = "PresA"
        )
        clientA.sendMessage(msg)

        val receivedC = withTimeoutOrNull(5000) { messagesC.receive() }
        assertNotNull("Node C should receive the broadcast even if it had to jump through Node B", receivedC)
    }

    /**
     * Test case: TTL Exhaustion
     */
    @Test
    fun message_isDropped_whenTtlExhausted() = runBlocking {
        val scopeA = makeScope()
        val clientA = Client("NODE_A", scope = scopeA)
        clientA.attachNetwork(LocalNetwork(scope = scopeA), Network.Config(5))
        clientA.createNetwork("TTL_TEST", SnakeTopology(maxPeerCount = 1))

        val scopeB = makeScope()
        val clientB = Client("NODE_B", scope = scopeB)
        clientB.attachNetwork(LocalNetwork(scope = scopeB), Network.Config(5))
        scopeB.launch { clientB.joinNetwork("TTL_TEST") }

        delay(2000)
        
        val scopeC = makeScope()
        val clientC = Client("NODE_C", scope = scopeC)
        clientC.attachNetwork(LocalNetwork(scope = scopeC), Network.Config(5))
        scopeC.launch { clientC.joinNetwork("TTL_TEST") }

        delay(4000)

        val receivedC = Channel<Message>(Channel.UNLIMITED)
        clientC.addMessageListener { receivedC.trySend(it) }

        val msg = Message(
            to = clientC.endpointId!!,
            from = clientA.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            ttl = 1
        )
        clientA.sendMessage(msg)

        val result = withTimeoutOrNull(3000) { receivedC.receive() }
        assertNull("Node C should NOT receive message because TTL was exhausted at Node B", result)
    }

    /**
     * Test case: Duplicate Message Suppression
     */
    @Test
    fun client_suppressesDuplicateMessages() = runBlocking {
        val hostScope = makeScope()
        val hostClient = Client("HOST", scope = hostScope)
        hostClient.attachNetwork(LocalNetwork(scope = hostScope), Network.Config(5))
        hostClient.createNetwork("DUP_TEST", MeshTopology())

        delay(1000)

        val receiveCount = AtomicInteger(0)
        hostClient.addMessageListener { receiveCount.incrementAndGet() }

        val msg = Message(
            to = hostClient.endpointId!!,
            from = "SOMEONE_ELSE",
            type = MessageType.TEXT_MESSAGE,
            ttl = 5,
            id = "CONSTANT_ID"
        )

        hostClient.onMessageReceived(msg)
        hostClient.onMessageReceived(msg)

        assertEquals("Should only process the message once despite receiving it twice", 1, receiveCount.get())
    }

    /**
     * Test case: Router Integrity
     */
    @Test
    fun router_preservesPayload_regardlessOfLocalKey() = runBlocking {
        val key1 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toByteArray()
        val key2 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB".toByteArray()

        val scopeA = makeScope()
        val clientA = Client("A", scope = scopeA)
        clientA.attachNetwork(LocalNetwork(scope = scopeA), Network.Config(5))
        clientA.createNetwork("INTEGRITY_TEST", SnakeTopology(maxPeerCount = 1))
        Manager.init(key1)

        val scopeB = makeScope()
        val clientB = Client("B", scope = scopeB)
        clientB.attachNetwork(LocalNetwork(scope = scopeB), Network.Config(5))
        scopeB.launch { clientB.joinNetwork("INTEGRITY_TEST") }
        
        delay(2000)
        scopeB.launch { Manager.init(key2) } 

        val scopeC = makeScope()
        val clientC = Client("C", scope = scopeC)
        clientC.attachNetwork(LocalNetwork(scope = scopeC), Network.Config(5))
        scopeC.launch { clientC.joinNetwork("INTEGRITY_TEST") }

        delay(4000)
        scopeC.launch { Manager.init(key1) }

        val receivedC = Channel<Message>(Channel.UNLIMITED)
        clientC.addMessageListener { receivedC.trySend(it) }

        val msg = Message(
            to = clientC.endpointId!!,
            from = clientA.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            data = "Secret Data".toByteArray(),
            ttl = 5
        )
        
        clientA.sendMessage(msg)

        val result = withTimeoutOrNull(5000) { receivedC.receive() }
        assertNotNull(result)
        assertEquals("Secret Data", String(result!!.data!!))
    }

    /**
     * Test case: Self-Message Loopback
     */
    @Test
    fun client_receivesSelfMessage() = runBlocking {
        val scope = makeScope()
        val client = Client("SELF", scope = scope)
        client.attachNetwork(LocalNetwork(scope = scope), Network.Config(5))
        client.createNetwork("SELF_TEST", MeshTopology())

        delay(1000)

        val messages = Channel<Message>(Channel.UNLIMITED)
        client.addMessageListener { messages.trySend(it) }

        val msg = Message(
            to = client.endpointId!!,
            from = client.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            ttl = 1
        )
        client.sendMessage(msg)

        val received = withTimeoutOrNull(2000) { messages.receive() }
        assertNotNull("Should receive message sent to self", received)
        assertEquals(client.endpointId, received!!.to)
    }

    /**
     * Test case: Reply to Broadcast flipped to direct address
     */
    @Test
    fun reply_toBroadcast_isDirectlyAddressed() = runBlocking {
        val scopeA = makeScope()
        val clientA = Client("A", scope = scopeA)
        clientA.attachNetwork(LocalNetwork(scope = scopeA), Network.Config(5))
        clientA.createNetwork("REPLY_TEST", MeshTopology())

        val scopeB = makeScope()
        val clientB = Client("B", scope = scopeB)
        clientB.attachNetwork(LocalNetwork(scope = scopeB), Network.Config(5))
        scopeB.launch { clientB.joinNetwork("REPLY_TEST") }

        delay(2000)

        val broadcast = Message(
            to = "ALL",
            from = clientA.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            ttl = 5
        )

        val reply = broadcast.createReply(
            from = clientB.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            data = "Got it".toByteArray(),
            ttl = 5
        )

        assertEquals("Reply to broadcast should be addressed to the specific sender", clientA.endpointId, reply.to)
    }

    /**
     * Test case: Dynamic Presentation Switching
     */
    @Test
    fun client_updatesFiltering_afterPresentationSwitch() = runBlocking {
        val hostScope = makeScope()
        val hostClient = Client("HOST", scope = hostScope)
        hostClient.attachNetwork(LocalNetwork(scope = hostScope), Network.Config(5))
        hostClient.createNetwork("SWITCH_TEST", MeshTopology())

        val clientScope = makeScope()
        val client = Client("USER", presentationId = "BoothA", scope = clientScope)
        client.attachNetwork(LocalNetwork(scope = clientScope), Network.Config(5))
        clientScope.launch { client.joinNetwork("SWITCH_TEST") }

        delay(2000)

        val receivedMessages = Channel<Message>(Channel.UNLIMITED)
        client.addMessageListener { receivedMessages.trySend(it) }

        // 1. Message for Booth A should be received
        hostClient.sendMessage(Message(to = "ALL", from = hostClient.endpointId!!, type = MessageType.TEXT_MESSAGE, ttl = 5, presentationId = "BoothA"))
        assertNotNull("Should receive Booth A message", withTimeoutOrNull(2000) { receivedMessages.receive() })

        // 2. Switch to Booth B
        client.presentationId = "BoothB"

        // 3. Message for Booth A should now be filtered
        hostClient.sendMessage(Message(to = "ALL", from = hostClient.endpointId!!, type = MessageType.TEXT_MESSAGE, ttl = 5, presentationId = "BoothA"))
        assertNull("Should NOT receive Booth A message after switching", withTimeoutOrNull(2000) { receivedMessages.receive() })

        // 4. Message for Booth B should be received
        hostClient.sendMessage(Message(to = "ALL", from = hostClient.endpointId!!, type = MessageType.TEXT_MESSAGE, ttl = 5, presentationId = "BoothB"))
        assertNotNull("Should receive Booth B message", withTimeoutOrNull(2000) { receivedMessages.receive() })
    }

    /**
     * Test case: Reply Waiter Timeout
     */
    @Test
    fun sendMessageAndWait_timesOutCorrectly() = runBlocking {
        val hostScope = makeScope()
        val hostClient = Client("HOST", scope = hostScope)
        hostClient.attachNetwork(LocalNetwork(scope = hostScope), Network.Config(5))
        hostClient.createNetwork("TIMEOUT_TEST", MeshTopology())

        val msg = Message(
            to = "NON_EXISTENT",
            from = hostClient.endpointId!!,
            type = MessageType.TEXT_MESSAGE,
            ttl = 1
        )

        val start = System.currentTimeMillis()
        val response = hostClient.sendMessageAndWait(msg, timeoutMillis = 1000)
        val end = System.currentTimeMillis()

        assertNull("Should return null on timeout", response)
        assertTrue("Should wait at least 1 second", (end - start) >= 1000)
    }
}
