package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [LocalNetwork].
 *
 * Uses [runTest] with [StandardTestDispatcher] so coroutine time is virtual —
 * no real wall-clock waiting. [advanceTimeBy] steps the virtual clock forward.
 *
 * Tests are grouped by behaviour:
 *  1. init / state
 *  2. Advertising
 *  3. Discovery
 *  4. Connections (connect / reject / disconnect)
 *  5. Messaging
 *  6. Shutdown
 *  7. Chaos config
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class LocalNetworkUnitTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val testDispatcher = StandardTestDispatcher()
    private val testScope      = TestScope(testDispatcher)

    /**
     * Creates and initialises a [LocalNetwork] backed by the test dispatcher so all
     * coroutines it launches run under virtual time.
     *
     * [acceptAll] controls the default [onConnectionRequest] behaviour.
     */
    private fun makeNetwork(
        endpointId: String,
        acceptAll:  Boolean     = true,
        chaos:      ChaosConfig = ChaosConfig.NONE
    ): LocalNetwork {
        val net = LocalNetwork(chaos = chaos, scope = testScope)
        net.init(endpointId, Network.Config(defaultTtl = 5))
        net.onConnectionRequest = { _, _ -> acceptAll }
        return net
    }

    private fun encodedName(
        event: String = "TestEvent",
        topo:  String = "snk",
        role:  String = "p",
        name:  String = "Alice"
    ) = "EVT:$event|TOP:$topo|TYP:$role|N:$name"

    // -------------------------------------------------------------------------
    // Setup / Teardown
    // -------------------------------------------------------------------------

    @Before fun setUp()    { LocalNetwork.purge() }
    @After  fun tearDown() { LocalNetwork.purge() }

    // =========================================================================
    // 1. init / state
    // =========================================================================

    @Test
    fun `init sets state to Idle`() {
        val net = makeNetwork("A")
        assertEquals(NetworkState.Idle, net.state.value)
    }

    @Test
    fun `calling init twice resets state to Idle`() {
        val net = makeNetwork("A")
        net.startAdvertising(encodedName(name = "A"))
        assertEquals(NetworkState.Advertising, net.state.value)

        net.init("A-v2", Network.Config(5))
        assertEquals(NetworkState.Idle, net.state.value)
    }

    @Test
    fun `sendMessage before init throws`() {
        val net = LocalNetwork()
        assertFailsWith<IllegalStateException> {
            net.sendMessage("B", Message(to = "B", from = "A", type = MessageType.TEXT_MESSAGE, ttl = 1))
        }
    }

    // =========================================================================
    // 2. Advertising
    // =========================================================================

    @Test
    fun `startAdvertising registers node as advertising`() {
        val net  = makeNetwork("A")
        val name = encodedName(name = "A")
        net.startAdvertising(name)

        val node = LocalNetwork.InMemoryNetworkHolder.getNode("A")
        assertNotNull(node)
        assertTrue(node.isAdvertising)
        assertEquals(name, node.encodedName)
    }

    @Test
    fun `startAdvertising sets state to Advertising`() {
        val net = makeNetwork("A")
        net.startAdvertising(encodedName(name = "A"))
        assertEquals(NetworkState.Advertising, net.state.value)
    }

    @Test
    fun `stopAdvertising clears advertising flag`() {
        val net = makeNetwork("A")
        net.startAdvertising(encodedName(name = "A"))
        net.stopAdvertising()
        assertFalse(LocalNetwork.InMemoryNetworkHolder.getNode("A")!!.isAdvertising)
    }

    @Test
    fun `stopAdvertising returns state to Idle`() {
        val net = makeNetwork("A")
        net.startAdvertising(encodedName(name = "A"))
        net.stopAdvertising()
        assertEquals(NetworkState.Idle, net.state.value)
    }

    @Test
    fun `startAdvertising twice updates encodedName without duplicating node`() {
        val net = makeNetwork("A")
        net.startAdvertising(encodedName(role = "p", name = "A"))
        net.startAdvertising(encodedName(role = "r", name = "A"))

        val nodes = LocalNetwork.InMemoryNetworkHolder.allNodes().filter { it.endpointId == "A" }
        assertEquals(1, nodes.size)
        assertTrue(nodes.first().encodedName.contains("TYP:r"))
    }

    // =========================================================================
    // 3. Discovery
    // =========================================================================

    @Test
    fun `startDiscovery immediately emits existing advertisers`() = testScope.runTest {
        val netA = makeNetwork("A")
        netA.startAdvertising(encodedName(name = "A"))

        val netB      = makeNetwork("B")
        val collected = mutableListOf<NetworkEvent.EndpointDiscovered>()

        val job = launch {
            netB.events
                .filterIsInstance<NetworkEvent.EndpointDiscovered>()
                .collect { collected.add(it) }
        }

        // Launch discovery — first batch is emitted synchronously before the poll loop
        val discJob = launch { netB.startDiscovery() }

        // Advance past the first poll cycle
        advanceTimeBy(1_000)
        netB.stopDiscovery()
        discJob.cancel()
        job.cancel()

        assertTrue(collected.any { it.endpointId == "A" },
            "B should have discovered A immediately")
    }

    @Test
    fun `startDiscovery does not emit self`() = testScope.runTest {
        val netA = makeNetwork("A")
        netA.startAdvertising(encodedName(name = "A"))

        val collected = mutableListOf<NetworkEvent.EndpointDiscovered>()
        val job = launch {
            netA.events.filterIsInstance<NetworkEvent.EndpointDiscovered>().collect { collected.add(it) }
        }

        val discJob = launch { netA.startDiscovery() }
        advanceTimeBy(1_000)
        netA.stopDiscovery()
        discJob.cancel()
        job.cancel()

        assertTrue(collected.none { it.endpointId == "A" }, "A should not discover itself")
    }

    @Test
    fun `stopDiscovery returns state to Idle`() = testScope.runTest {
        val net     = makeNetwork("A")
        val discJob = launch { net.startDiscovery() }
        advanceTimeBy(100)
        net.stopDiscovery()
        advanceTimeBy(100)
        discJob.cancel()
        assertEquals(NetworkState.Idle, net.state.value)
    }

    @Test
    fun `newly advertising node is discovered during active scan`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B")

        val collected = mutableListOf<NetworkEvent.EndpointDiscovered>()
        val collectJob = launch {
            netB.events.filterIsInstance<NetworkEvent.EndpointDiscovered>().collect { collected.add(it) }
        }

        // B starts scanning before A starts advertising
        val discJob = launch { netB.startDiscovery() }
        advanceTimeBy(200)

        // A starts advertising mid-scan
        netA.startAdvertising(encodedName(name = "A"))

        // Advance past one more poll interval (750ms in real time, virtual here)
        advanceTimeBy(1_000)

        netB.stopDiscovery()
        discJob.cancel()
        collectJob.cancel()

        assertTrue(collected.any { it.endpointId == "A" },
            "B should discover A after it starts advertising mid-scan")
    }

    // =========================================================================
    // 4. Connections
    // =========================================================================

    @Test
    fun `connect creates bidirectional connection`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))

        netA.connect("B")
        advanceTimeBy(100)

        val nodeA = LocalNetwork.InMemoryNetworkHolder.getNode("A")
        val nodeB = LocalNetwork.InMemoryNetworkHolder.getNode("B")
        assertTrue(nodeA?.connections?.contains("B") == true, "A should list B as connected")
        assertTrue(nodeB?.connections?.contains("A") == true, "B should list A as connected")
    }

    @Test
    fun `connect emits EndpointConnected on both sides`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))

        val aEvents = mutableListOf<NetworkEvent>()
        val bEvents = mutableListOf<NetworkEvent>()
        val jobA = launch { netA.events.collect { aEvents.add(it) } }
        val jobB = launch { netB.events.collect { bEvents.add(it) } }

        netA.connect("B")
        advanceTimeBy(100)

        assertTrue(aEvents.any { it is NetworkEvent.EndpointConnected && it.endpointId == "B" },
            "A should receive EndpointConnected for B")
        assertTrue(bEvents.any { it is NetworkEvent.EndpointConnected && it.endpointId == "A" },
            "B should receive EndpointConnected for A")

        jobA.cancel(); jobB.cancel()
    }

    @Test
    fun `connect is rejected when onConnectionRequest returns false`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = false)
        netB.startAdvertising(encodedName(name = "B"))

        val rejected = mutableListOf<NetworkEvent.ConnectionRejected>()
        val job = launch {
            netA.events.filterIsInstance<NetworkEvent.ConnectionRejected>().collect { rejected.add(it) }
        }

        netA.connect("B")
        advanceTimeBy(100)
        job.cancel()

        assertTrue(rejected.any { it.endpointId == "B" },
            "A should receive ConnectionRejected from B")
        assertFalse(
            LocalNetwork.InMemoryNetworkHolder.getNode("A")?.connections?.contains("B") == true,
            "Connection should not be established after rejection"
        )
    }

    @Test
    fun `connect to unknown endpoint throws`() = testScope.runTest {
        val netA = makeNetwork("A")
        assertFailsWith<IllegalStateException> {
            netA.connect("NOBODY")
        }
    }

    @Test
    fun `disconnect removes connection from both sides`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))

        netA.connect("B")
        advanceTimeBy(100)
        netA.disconnect("B")
        advanceTimeBy(100)

        assertFalse(LocalNetwork.InMemoryNetworkHolder.getNode("A")?.connections?.contains("B") == true)
        assertFalse(LocalNetwork.InMemoryNetworkHolder.getNode("B")?.connections?.contains("A") == true)
    }

    @Test
    fun `disconnect emits EndpointDisconnected on both sides`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))

        netA.connect("B")
        advanceTimeBy(100)

        val aEvents = mutableListOf<NetworkEvent>()
        val bEvents = mutableListOf<NetworkEvent>()
        val jobA = launch { netA.events.collect { aEvents.add(it) } }
        val jobB = launch { netB.events.collect { bEvents.add(it) } }

        netA.disconnect("B")
        advanceTimeBy(100)

        assertTrue(aEvents.any { it is NetworkEvent.EndpointDisconnected && it.endpointId == "B" })
        assertTrue(bEvents.any { it is NetworkEvent.EndpointDisconnected && it.endpointId == "A" })

        jobA.cancel(); jobB.cancel()
    }

    @Test
    fun `shutdown notifies connected peers of disconnection`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))

        netA.connect("B")
        advanceTimeBy(100)

        val bEvents = mutableListOf<NetworkEvent>()
        val job = launch { netB.events.collect { bEvents.add(it) } }

        netA.shutdown()
        advanceTimeBy(100)
        job.cancel()

        assertTrue(
            bEvents.any { it is NetworkEvent.EndpointDisconnected && it.endpointId == "A" },
            "B should be notified when A shuts down"
        )
    }

    // =========================================================================
    // 5. Messaging
    // =========================================================================

    @Test
    fun `sendMessage delivers to connected peer`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))
        netA.connect("B")
        advanceTimeBy(100)

        val received = mutableListOf<Message>()
        netB.addListener { received.add(it) }

        val msg = Message(to = "B", from = "A", type = MessageType.TEXT_MESSAGE, ttl = 1)
        netA.sendMessage("B", msg)
        advanceTimeBy(100)

        assertTrue(received.any { it.id == msg.id })
    }

    @Test
    fun `sendMessage throws when not connected to target`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))
        // NOT connecting

        assertFailsWith<IllegalStateException> {
            netA.sendMessage("B", Message(to = "B", from = "A", type = MessageType.TEXT_MESSAGE, ttl = 1))
        }
    }

    @Test
    fun `receiveMessage fires MessageReceived event`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))
        netA.connect("B")
        advanceTimeBy(100)

        val events = mutableListOf<NetworkEvent.MessageReceived>()
        val job = launch {
            netB.events.filterIsInstance<NetworkEvent.MessageReceived>().collect { events.add(it) }
        }

        val msg = Message(to = "B", from = "A", type = MessageType.TEXT_MESSAGE, ttl = 1)
        netA.sendMessage("B", msg)
        advanceTimeBy(100)
        job.cancel()

        assertTrue(events.any { it.message.id == msg.id })
    }

    @Test
    fun `addListener is invoked on message receive`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))
        netA.connect("B")
        advanceTimeBy(100)

        var fired = false
        netB.addListener { fired = true }

        netA.sendMessage("B", Message(to = "B", from = "A", type = MessageType.PING, ttl = 1))
        advanceTimeBy(100)

        assertTrue(fired)
    }

    @Test
    fun `removeListener stops invocation on message receive`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))
        netA.connect("B")
        advanceTimeBy(100)

        var count = 0
        val listener: (Message) -> Unit = { count++ }
        netB.addListener(listener)

        netA.sendMessage("B", Message(to = "B", from = "A", type = MessageType.PING, ttl = 1))
        advanceTimeBy(100)
        assertEquals(1, count, "Listener should fire once before removal")

        netB.removeListener(listener)
        netA.sendMessage("B", Message(to = "B", from = "A", type = MessageType.PING, ttl = 1))
        advanceTimeBy(100)
        assertEquals(1, count, "Listener should not fire after removal")
    }

    @Test
    fun `encodedNameToHardwareId returns hardwareId for connected peer`() = testScope.runTest {
        val netA = makeNetwork("A")
        val netB = makeNetwork("B", acceptAll = true)
        val name = encodedName(name = "B")
        netB.startAdvertising(name)
        netA.connect("B")
        advanceTimeBy(100)

        // In LocalNetwork, encoded name == hardware ID — returns it if connected
        assertEquals("B", netA.encodedNameToHardwareId("B"))
    }

    @Test
    fun `encodedNameToHardwareId returns null for unknown peer`() = testScope.runTest {
        val netA = makeNetwork("A")
        assertNull(netA.encodedNameToHardwareId("NOBODY"))
    }

    // =========================================================================
    // 6. Shutdown
    // =========================================================================

    @Test
    fun `shutdown removes node from holder`() = testScope.runTest {
        val net = makeNetwork("A")
        net.startAdvertising(encodedName(name = "A"))
        assertNotNull(LocalNetwork.InMemoryNetworkHolder.getNode("A"))

        net.shutdown()
        assertNull(LocalNetwork.InMemoryNetworkHolder.getNode("A"))
    }

    @Test
    fun `shutdown resets state to Idle`() = testScope.runTest {
        val net = makeNetwork("A")
        net.startAdvertising(encodedName(name = "A"))
        net.shutdown()
        assertEquals(NetworkState.Idle, net.state.value)
    }

    // =========================================================================
    // 7. Chaos config
    // =========================================================================

    @Test
    fun `messageDropRate of 1 0 prevents all delivery`() = testScope.runTest {
        val netA = makeNetwork("A", chaos = ChaosConfig(messageDropRate = 1.0))
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))
        netA.connect("B")
        advanceTimeBy(100)

        val received = mutableListOf<Message>()
        netB.addListener { received.add(it) }

        netA.sendMessage("B", Message(to = "B", from = "A", type = MessageType.TEXT_MESSAGE, ttl = 1))
        advanceTimeBy(200)

        assertTrue(received.isEmpty(), "All messages should be dropped with messageDropRate=1.0")
    }

    @Test
    fun `connectionFailureRate of 1 0 always fires ConnectionRejected`() = testScope.runTest {
        val netA = makeNetwork("A", chaos = ChaosConfig(connectionFailureRate = 1.0))
        val netB = makeNetwork("B", acceptAll = true)
        netB.startAdvertising(encodedName(name = "B"))

        val rejected = mutableListOf<NetworkEvent.ConnectionRejected>()
        val job = launch {
            netA.events.filterIsInstance<NetworkEvent.ConnectionRejected>().collect { rejected.add(it) }
        }

        netA.connect("B")
        advanceTimeBy(100)
        job.cancel()

        assertTrue(rejected.isNotEmpty())
    }
}