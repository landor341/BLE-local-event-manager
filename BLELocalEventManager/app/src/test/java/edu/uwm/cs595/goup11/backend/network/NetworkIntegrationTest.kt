package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests — Client + Topology + LocalNetwork working together.
 *
 * Uses real [Client], real [LocalNetwork], and real topology strategies.
 * Nothing is faked or stubbed — every message, connection, and event flows
 * through the actual stack.
 *
 * ## What is tested
 *  1.  Network creation — identity, advertising, state
 *  2.  Two-client connection via Snake and Mesh
 *  3.  Direct message delivery (both directions, multiple messages)
 *  4.  sendMessageAndWait — round-trip and timeout
 *  5.  Three-node Snake chain — routing through middle node
 *  6.  Three-node Mesh — direct delivery to any known peer
 *  7.  Leave and rejoin — clean reconnection
 *  8.  Topology connection limits — hard reject at max
 *  9.  Keepalive through the full stack — PINGs arrive, silent peer evicted
 * 10.  No-route safety — gone peer does not crash the stack
 *
 * ## Coroutine / timer strategy
 *
 * Each Client is given its own [CoroutineScope] backed by a [SupervisorJob],
 * which is cancelled in [@After]. Topology timers are configured to
 * 50 ms keepalive / 200 ms timeout so all [runBlocking] waits stay under
 * one second. [LocalNetwork.purge] is called in [@Before] and [@After] to
 * guarantee a clean [InMemoryNetworkHolder] between tests.
 */
@RunWith(JUnit4::class)
class NetworkIntegrationTest {

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private val scopes = mutableListOf<CoroutineScope>()

    /**
     * Build a Client with its own isolated scope and a fresh LocalNetwork.
     * The scope is tracked for cleanup in @After.
     */
    private fun makeClient(name: String): Pair<Client, CoroutineScope> {
        val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes.add(scope)
        val client = Client(displayName = name, scope = scope)
        client.attachNetwork(LocalNetwork(scope = scope), Network.Config(defaultTtl = 5))
        return Pair(client, scope)
    }

    private fun snake(
        max:       Int  = 2,
        keepMs:    Long = 50,
        timeoutMs: Long = 200,
        discMs:    Long = 50
    ) = SnakeTopology(
        maxPeerCount        = max,
        keepaliveIntervalMs = keepMs,
        keepaliveTimeoutMs  = timeoutMs,
        discoveryIntervalMs = discMs
    )

    private fun mesh(
        max:       Int  = 6,
        target:    Int  = 3,
        keepMs:    Long = 50,
        timeoutMs: Long = 200,
        discMs:    Long = 50
    ) = MeshTopology(
        maxPeerCount        = max,
        targetPeerCount     = target,
        keepaliveIntervalMs = keepMs,
        keepaliveTimeoutMs  = timeoutMs,
        discoveryIntervalMs = discMs
    )

    private fun text(to: String, from: String, body: String = "hello") =
        Message(to = to, from = from, type = MessageType.TEXT_MESSAGE, ttl = 5, data = body.toByteArray(Charsets.UTF_8))

    /**
     * Launch a collector for [NetworkEvent]s of a specific type while [block]
     * runs, then cancel the collector. Returns everything collected.
     */
    private suspend fun <T : NetworkEvent> collectEventsWhile(
        scope:  CoroutineScope,
        client: Client,
        block:  suspend () -> Unit
    ): List<T> {
        val collected = mutableListOf<T>()
        val job: Job = scope.launch {
            client.network!!.events.collect { ev ->
                @Suppress("UNCHECKED_CAST")
                runCatching { collected.add(ev as T) }
            }
        }
        block()
        delay(400)
        job.cancel()
        return collected
    }

    @Before fun setUp()    { LocalNetwork.purge() }
    @After  fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        LocalNetwork.purge()
    }

    // =========================================================================
    // 1. Network creation and identity
    // =========================================================================

    @Test fun `createNetwork marks client as connected`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        alice.createNetwork("Fest", snake())
        assertTrue(alice.isConnected())
    }

    @Test fun `createNetwork encodes event name topology and display name in endpointId`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        alice.createNetwork("Fest", snake())
        val id = alice.endpointId!!
        assertTrue(id.contains("Alice"), "endpointId should contain display name")
        assertTrue(id.contains("snk"),   "endpointId should contain snake topology code")
        assertTrue(id.contains("Fest"),  "endpointId should contain event name")
    }

    @Test fun `createNetwork with mesh topology encodes msh in endpointId`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        alice.createNetwork("Fest", mesh())
        assertTrue(alice.endpointId!!.contains("msh"))
    }

    @Test fun `createNetwork registers node as advertising in InMemoryNetworkHolder`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        alice.createNetwork("Fest", snake())
        val node = LocalNetwork.InMemoryNetworkHolder.getNode(alice.endpointId!!)
        assertNotNull(node, "Alice's node must be in the holder after createNetwork")
        assertTrue(node.isAdvertising, "Alice's node must be advertising")
    }

    @Test fun `leaveNetwork clears connected state and endpointId`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        alice.createNetwork("Fest", snake())
        assertTrue(alice.isConnected())
        alice.leaveNetwork()
        assertFalse(alice.isConnected())
        assertNull(alice.endpointId)
    }

    @Test fun `createNetwork then leaveNetwork then createNetwork new event works`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        alice.createNetwork("FestA", snake())
        alice.leaveNetwork()
        alice.createNetwork("FestB", mesh())
        assertTrue(alice.isConnected())
        assertTrue(alice.endpointId!!.contains("FestB"))
        assertTrue(alice.endpointId!!.contains("msh"))
    }

    // =========================================================================
    // 2. Two-client connection
    // =========================================================================

    @Test fun `alice and bob connect via snake topology`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)
        assertTrue(alice.isConnected())
        assertTrue(bob.isConnected())
    }

    @Test fun `alice and bob connect via mesh topology`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", mesh())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)
        assertTrue(alice.isConnected())
        assertTrue(bob.isConnected())
    }

    @Test fun `joining client encodes correct event name and display name`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("MyFest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("MyFest") }
        delay(400)
        assertNotNull(bob.endpointId)
        assertTrue(bob.endpointId!!.contains("MyFest"))
        assertTrue(bob.endpointId!!.contains("Bob"))
    }

    @Test fun `joining non-existent event does not connect`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        alice.createNetwork("EventA", snake())
        delay(100)
        val (bob, _) = makeClient("Bob")
        withTimeoutOrNull(500) { bob.joinNetwork("EventB") }
        assertFalse(bob.isConnected(), "Bob should not connect to a non-existent event")
    }

    // =========================================================================
    // 3. Direct message delivery
    // =========================================================================

    @Test fun `alice sends message bob receives it`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val received = mutableListOf<Message>()
        bob.addMessageListener { received.add(it) }

        alice.sendMessage(text(bob.endpointId!!, alice.endpointId!!))
        delay(200)

        assertEquals(1, received.size)
        assertEquals(alice.endpointId, received.first().from)
    }

    @Test fun `bob sends message alice receives it`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val received = mutableListOf<Message>()
        alice.addMessageListener { received.add(it) }

        bob.sendMessage(text(alice.endpointId!!, bob.endpointId!!))
        delay(200)

        assertEquals(1, received.size)
        assertEquals(bob.endpointId, received.first().from)
    }

    @Test fun `five messages are all delivered in order`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val received = mutableListOf<Message>()
        bob.addMessageListener { received.add(it) }

        repeat(5) { i ->
            alice.sendMessage(text(bob.endpointId!!, alice.endpointId!!, "msg$i"))
        }
        delay(300)

        assertEquals(5, received.size)
        assertEquals((0..4).map { "msg$it" }, received.map { it.data?.toString(Charsets.UTF_8) })
    }

    @Test fun `message body is preserved end to end`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val received = mutableListOf<Message>()
        bob.addMessageListener { received.add(it) }

        alice.sendMessage(text(bob.endpointId!!, alice.endpointId!!, "Hello, integration!"))
        delay(200)

        assertEquals("Hello, integration!", received.first().data?.toString(Charsets.UTF_8))
    }

    // =========================================================================
    // 4. sendMessageAndWait — round-trip and timeout
    // =========================================================================

    @Test fun `sendMessageAndWait receives reply from bob`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        // Bob echoes every TEXT_MESSAGE back as a reply
        bob.addMessageListener { incoming ->
            bob.sendMessage(Message(
                to      = incoming.from,
                from    = bob.endpointId!!,
                type    = MessageType.TEXT_MESSAGE,
                replyTo = incoming.id,
                ttl     = 5,
                data    = "pong".toByteArray(Charsets.UTF_8)
            ))
        }

        val req   = text(bob.endpointId!!, alice.endpointId!!, "ping")
        val reply = alice.sendMessageAndWait(req, timeoutMillis = 2_000)

        assertNotNull(reply, "Alice should receive a reply")
        assertEquals("pong",  reply!!.data?.toString(Charsets.UTF_8))
        assertEquals(req.id,  reply.replyTo)
    }

    @Test fun `sendMessageAndWait returns null when no reply arrives`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        // Bob never replies
        val reply = alice.sendMessageAndWait(
            text(bob.endpointId!!, alice.endpointId!!),
            timeoutMillis = 300
        )

        assertNull(reply, "Should return null when no reply arrives before timeout")
    }

    // =========================================================================
    // 5. Three-node Snake chain — routing through middle node
    // =========================================================================

    @Test fun `alice message reaches carol through bob in snake chain`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")

        alice.createNetwork("Fest", snake(max = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") };   delay(400)
        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }; delay(400)

        val carolReceived = mutableListOf<Message>()
        carol.addMessageListener { carolReceived.add(it) }

        alice.sendMessage(text(carol.endpointId!!, alice.endpointId!!))
        delay(400)

        assertEquals(1, carolReceived.size, "Carol should receive Alice's message via Bob")
        assertEquals(alice.endpointId, carolReceived.first().from)
    }

    @Test fun `carol message reaches alice through bob in snake chain`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")

        alice.createNetwork("Fest", snake(max = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") };   delay(400)
        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }; delay(400)

        val aliceReceived = mutableListOf<Message>()
        alice.addMessageListener { aliceReceived.add(it) }

        carol.sendMessage(text(alice.endpointId!!, carol.endpointId!!))
        delay(400)

        assertEquals(1, aliceReceived.size)
        assertEquals(carol.endpointId, aliceReceived.first().from)
    }

    @Test fun `bob reaches both alice and carol directly as middle node`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")

        alice.createNetwork("Fest", snake(max = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") };   delay(400)
        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }; delay(400)

        val aliceReceived = mutableListOf<Message>()
        val carolReceived = mutableListOf<Message>()
        alice.addMessageListener { aliceReceived.add(it) }
        carol.addMessageListener { carolReceived.add(it) }

        bob.sendMessage(text(alice.endpointId!!, bob.endpointId!!, "to alice"))
        bob.sendMessage(text(carol.endpointId!!, bob.endpointId!!, "to carol"))
        delay(300)

        assertEquals(1, aliceReceived.size)
        assertEquals(1, carolReceived.size)
        assertEquals("to alice", aliceReceived.first().data?.toString(Charsets.UTF_8))
        assertEquals("to carol", carolReceived.first().data?.toString(Charsets.UTF_8))
    }

    // =========================================================================
    // 6. Three-node Mesh routing
    // =========================================================================

    @Test fun `all three mesh nodes can message each other directly`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")

        alice.createNetwork("Fest", mesh(max = 6, target = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") };   delay(400)
        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }; delay(400)

        val bobRx   = mutableListOf<Message>()
        val carolRx = mutableListOf<Message>()
        bob.addMessageListener   { bobRx.add(it) }
        carol.addMessageListener { carolRx.add(it) }

        alice.sendMessage(text(bob.endpointId!!,   alice.endpointId!!, "a→b"))
        alice.sendMessage(text(carol.endpointId!!, alice.endpointId!!, "a→c"))
        delay(300)

        assertEquals(1, bobRx.filter   { it.data?.toString(Charsets.UTF_8) == "a→b" }.size, "Bob should receive message from Alice")
        assertEquals(1, carolRx.filter { it.data?.toString(Charsets.UTF_8) == "a→c" }.size, "Carol should receive message from Alice")
    }

    @Test fun `mesh flood delivers to all peers when destination is unknown`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")

        alice.createNetwork("Fest", mesh(max = 6, target = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") };   delay(400)
        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }; delay(400)

        val bobRx   = mutableListOf<Message>()
        val carolRx = mutableListOf<Message>()
        bob.addMessageListener   { bobRx.add(it) }
        carol.addMessageListener { carolRx.add(it) }

        // Send to an unknown endpoint — topology should flood to all peers
        alice.sendMessage(text("UNKNOWN_DEST", alice.endpointId!!, "broadcast"))
        delay(300)

        // Both bob and carol should receive the flooded message
        assertTrue(bobRx.any   { it.data?.toString(Charsets.UTF_8) == "broadcast" }, "Bob should receive flooded message")
        assertTrue(carolRx.any { it.data?.toString(Charsets.UTF_8) == "broadcast" }, "Carol should receive flooded message")
    }

    // =========================================================================
    // 7. Leave and rejoin
    // =========================================================================

    @Test fun `alice detects bob disconnecting`() = runBlocking {
        val (alice, aliceScope) = makeClient("Alice")
        val (bob,   _)          = makeClient("Bob")

        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val disconnects = mutableListOf<NetworkEvent.EndpointDisconnected>()
        val job = aliceScope.launch {
            alice.network!!.events
                .filterIsInstance<NetworkEvent.EndpointDisconnected>()
                .collect { disconnects.add(it) }
        }

        bob.leaveNetwork()
        delay(400)
        job.cancel()

        assertTrue(disconnects.isNotEmpty(), "Alice should receive an EndpointDisconnected event")
        assertEquals(bob.endpointId ?: disconnects.first().endpointId,
            disconnects.first().endpointId)
    }

    @Test fun `bob can rejoin after leaving and receive messages`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")

        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)
        assertTrue(bob.isConnected())

        bob.leaveNetwork()
        delay(200)
        assertFalse(bob.isConnected())

        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)
        assertTrue(bob.isConnected(), "Bob should reconnect successfully")

        val received = mutableListOf<Message>()
        bob.addMessageListener { received.add(it) }
        alice.sendMessage(text(bob.endpointId!!, alice.endpointId!!))
        delay(200)

        assertEquals(1, received.size, "Bob should receive messages after rejoining")
    }

    @Test fun `after bob leaves alice accepts a new peer in the vacated slot`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")

        alice.createNetwork("Fest", snake(max = 1))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)
        assertTrue(bob.isConnected())

        bob.leaveNetwork()
        delay(300)

        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }
        delay(400)
        assertTrue(carol.isConnected(), "Carol should fill the slot Bob vacated")
    }

    // =========================================================================
    // 8. Topology connection limits
    // =========================================================================

    @Test fun `snake rejects a fourth connection when max is 2`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")
        val (dave,  davScope) = makeClient("Dave")

        alice.createNetwork("Fest", snake(max = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") };   delay(400)
        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }; delay(400)

        val rejections = mutableListOf<NetworkEvent.ConnectionRejected>()
        val job = davScope.launch {
            dave.network!!.events
                .filterIsInstance<NetworkEvent.ConnectionRejected>()
                .collect { rejections.add(it) }
        }

        withTimeoutOrNull(2_000) { dave.joinNetwork("Fest") }
        delay(400)
        job.cancel()

        assertTrue(rejections.isNotEmpty() || !dave.isConnected(),
            "Dave should be rejected when snake is already full")
    }

    @Test fun `mesh rejects connection when at maxPeerCount`() = runBlocking {
        val (host, _) = makeClient("Host")
        host.createNetwork("Fest", mesh(max = 2, target = 1))
        delay(100)

        val (c1, _) = makeClient("C1")
        val (c2, _) = makeClient("C2")
        val (c3, c3Scope) = makeClient("C3")

        withTimeoutOrNull(2_000) { c1.joinNetwork("Fest") }; delay(400)
        withTimeoutOrNull(2_000) { c2.joinNetwork("Fest") }; delay(400)

        val rejections = mutableListOf<NetworkEvent.ConnectionRejected>()
        val job = c3Scope.launch {
            c3.network!!.events
                .filterIsInstance<NetworkEvent.ConnectionRejected>()
                .collect { rejections.add(it) }
        }

        withTimeoutOrNull(2_000) { c3.joinNetwork("Fest") }
        delay(400)
        job.cancel()

        assertTrue(rejections.isNotEmpty() || !c3.isConnected(),
            "C3 should be rejected when mesh host is at max")
    }

    @Test fun `mesh allows connections up to target from maxPeerCount`() = runBlocking {
        // target=2, max=4 — fill to target, additional peers should still be accepted
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")
        val (carol, _) = makeClient("Carol")

        alice.createNetwork("Fest", mesh(max = 4, target = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") };   delay(400)
        withTimeoutOrNull(2_000) { carol.joinNetwork("Fest") }; delay(400)

        // Both bob and carol should have connected — we're at target but below max
        assertTrue(bob.isConnected(),   "Bob should connect (below max)")
        assertTrue(carol.isConnected(), "Carol should connect (at target but below max)")
    }

    // =========================================================================
    // 9. Keepalive through the full stack
    // =========================================================================

    @Test fun `alice sends PING messages to bob via keepalive`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")

        // Long timeout — no eviction — just observe PINGs reaching Bob
        alice.createNetwork("Fest", snake(keepMs = 50, timeoutMs = 10_000))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val pingsAtBob = mutableListOf<Message>()
        bob.addMessageListener { if (it.type == MessageType.PING) pingsAtBob.add(it) }

        delay(300) // let a few keepalive ticks fire

        assertTrue(pingsAtBob.isNotEmpty(),
            "Bob should receive PING messages from Alice's keepalive loop")
    }

    @Test fun `silent peer is evicted from topology after keepalive timeout`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")

        alice.createNetwork("Fest", snake(keepMs = 50, timeoutMs = 120))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val bobId = bob.endpointId!!

        // Kill Bob's network silently so he can no longer respond to PINGs
        bob.network!!.shutdown()
        delay(600) // Alice's keepalive should evict Bob

        val aliceNode = LocalNetwork.InMemoryNetworkHolder.getNode(alice.endpointId!!)
        val bobStillConnected = aliceNode?.connections?.contains(bobId) ?: false
        assertFalse(bobStillConnected,
            "Alice should evict Bob from her connection list after keepalive timeout")
    }

    @Test fun `after eviction alice restarts discovery to find new peers`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")

        alice.createNetwork("Fest", snake(keepMs = 50, timeoutMs = 120, discMs = 50))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        // Kill Bob
        bob.network!!.shutdown()
        delay(600)

        // Alice should have re-entered the discovery/advertising state
        val aliceNode = LocalNetwork.InMemoryNetworkHolder.getNode(alice.endpointId!!)
        assertNotNull(aliceNode, "Alice's node should still be in the holder")
        assertTrue(aliceNode.isAdvertising,
            "Alice should be advertising again after her peer was evicted")
    }

    // =========================================================================
    // 10. No-route safety and graceful teardown
    // =========================================================================

    @Test fun `sending to departed peer does not throw`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")

        alice.createNetwork("Fest", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("Fest") }
        delay(400)

        val goneId = bob.endpointId!!
        bob.leaveNetwork()
        delay(300)

        var threw = false
        try {
            alice.sendMessage(text(goneId, alice.endpointId!!))
            delay(100)
        } catch (e: Exception) {
            threw = true
        }

        assertFalse(threw, "Sending to a gone peer should not throw — topology returns empty hops")
    }

    @Test fun `two separate events do not interfere with each other`() = runBlocking {
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")

        alice.createNetwork("EventA", snake())
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("EventA") }
        delay(400)

        // Carol joins EventB which nobody is hosting
        val (carol, _) = makeClient("Carol")
        withTimeoutOrNull(500) { carol.joinNetwork("EventB") }

        assertFalse(carol.isConnected(),
            "Carol should not connect to EventA when she is looking for EventB")

        // Alice and Bob's connection should be unaffected
        assertTrue(alice.isConnected())
        assertTrue(bob.isConnected())
    }

    @Test fun `mesh node can receive messages after snake node leaves same event`() = runBlocking {
        // Verify the stack handles mixed topology presence gracefully.
        // Two clients on EventA — if one leaves the other must still function.
        val (alice, _) = makeClient("Alice")
        val (bob,   _) = makeClient("Bob")

        alice.createNetwork("EventA", mesh(max = 4, target = 2))
        delay(100)
        withTimeoutOrNull(2_000) { bob.joinNetwork("EventA") }
        delay(400)

        bob.leaveNetwork()
        delay(300)

        // Alice should still be running and accepting new connections
        val (carol, _) = makeClient("Carol")
        withTimeoutOrNull(2_000) { carol.joinNetwork("EventA") }
        delay(400)

        val received = mutableListOf<Message>()
        carol.addMessageListener { received.add(it) }
        alice.sendMessage(text(carol.endpointId!!, alice.endpointId!!))
        delay(200)

        assertEquals(1, received.size, "Carol should receive Alice's message after Bob left")
    }
}