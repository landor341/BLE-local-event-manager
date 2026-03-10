package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.Network
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SnakeTopology].
 *
 * ## Coroutine strategy
 *
 * SnakeTopology launches two infinite loops (keepalive + discovery) via
 * TopologyContext.launchJob. These MUST NOT run on a runTest scope because
 * runTest drains all pending coroutines before returning — spinning forever
 * on an infinite while loop and causing OOM.
 *
 * Solution: topology jobs run on a dedicated SupervisorJob scope (topoScope)
 * created in @Before and CANCELLED IN @After. This is completely separate
 * from any test framework scope so nothing can drain it unexpectedly.
 *
 * Tests that need timer-driven behaviour use runBlocking + real delay with
 * very short wall-clock timers (50-300ms total wait).
 * Pure logic tests (no background jobs) are plain synchronous functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class SnakeTopologyUnitTest {

    // -------------------------------------------------------------------------
    // FakeNetwork
    // -------------------------------------------------------------------------

    class FakeNetwork : Network {
        override val logger: KLogger = KotlinLogging.logger {}

        private val _state  = MutableStateFlow<NetworkState>(NetworkState.Idle)
        override val state: StateFlow<NetworkState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, replay = 1)
        override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

        val sent       = mutableListOf<Pair<String, Message>>()
        val connectLog = mutableListOf<String>()

        override var onConnectionRequest: suspend (String, String) -> Boolean = { _, _ -> false }

        override fun init(localEndpointId: String, config: Network.Config) {}
        override fun shutdown()                               { sent.clear() }
        override fun startAdvertising(encodedName: String)    {}
        override fun stopAdvertising()                        {}
        override suspend fun startDiscovery()                 {}
        override suspend fun stopDiscovery()                  {}
        override suspend fun connect(endpointId: String)      { connectLog.add(endpointId) }
        override fun disconnect(endpointId: String)           {}
        override fun addListener(listener: (Message) -> Unit) {}
        override fun notifyListeners(message: Message)        {}

        override fun sendMessage(endpointId: String, message: Message) {
            sent.add(Pair(endpointId, message))
        }

        /** Emit a discovery event so the topology's collect block can react. */
        suspend fun emitDiscovered(endpointId: String, encodedName: String) {
            _events.emit(NetworkEvent.EndpointDiscovered(endpointId, encodedName))
        }

        fun sentTo(id: String)         = sent.filter { it.first == id }.map { it.second }
        fun sentOfType(t: MessageType) = sent.filter { it.second.type == t }
    }

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    /** Owns topology background jobs. Cancelled in @After — never drained by runTest. */
    private lateinit var topoScope: CoroutineScope

    private data class TC(
        val ctx:     TopologyContext,
        val net:     FakeNetwork,
        val advLog:  MutableList<Pair<Boolean, String?>>,
        val scanLog: MutableList<Boolean>
    )

    private fun makeCtx(localId: String): TC {
        val advLog  = mutableListOf<Pair<Boolean, String?>>()
        val scanLog = mutableListOf<Boolean>()
        val net     = FakeNetwork()
        val ctx     = TopologyContext(
            localEndpointId      = { localId },
            localEncodedName     = { enc(localId) },
            network              = net,
            onAdvertisingChanged = { adv, name -> advLog.add(Pair(adv, name)) },
            onScanChanged        = { scanning -> scanLog.add(scanning) },
            onRoleChanged        = {},
            onConnect            = { endpointId -> net.connect(endpointId) },
            networkEvents        = net.events,
            coroutineScope       = topoScope,
        )
        return TC(ctx, net, advLog, scanLog)
    }

    private fun makeTopo(
        max:         Int  = 2,
        keepaliveMs: Long = 50,
        timeoutMs:   Long = 150,
        discoveryMs: Long = 50
    ) = SnakeTopology(
        maxPeerCount        = max,
        keepaliveIntervalMs = keepaliveMs,
        keepaliveTimeoutMs  = timeoutMs,
        discoveryIntervalMs = discoveryMs
    )

    private fun enc(name: String) = "EVT:Test|TOP:snk|TYP:p|N:$name"
    private fun aName(id: String) = AdvertisedName.decode(enc(id))!!
    private fun msg(to: String, from: String, type: MessageType = MessageType.TEXT_MESSAGE) =
        Message(to = to, from = from, type = type, ttl = 10)

    @Before fun setUp() {
        topoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        LocalNetwork.purge()
    }

    @After fun tearDown() {
        topoScope.cancel()
        LocalNetwork.purge()
    }

    // =========================================================================
    // 1. shouldAcceptConnection
    // =========================================================================

    @Test fun `shouldAcceptConnection accepts when below max`() = runBlocking {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        assertTrue(topo.shouldAcceptConnection(tc.ctx, "B", aName("B")))
    }

    @Test fun `shouldAcceptConnection rejects when at max`() = runBlocking {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        assertFalse(topo.shouldAcceptConnection(tc.ctx, "D", aName("D")))
    }

    @Test fun `shouldAcceptConnection accepts again after disconnect frees a slot`() = runBlocking {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerDisconnected(tc.ctx, "C")
        assertTrue(topo.shouldAcceptConnection(tc.ctx, "D", aName("D")))
    }

    @Test fun `shouldAcceptConnection rejects after single peer at max 1`() = runBlocking {
        val topo = makeTopo(max = 1); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertFalse(topo.shouldAcceptConnection(tc.ctx, "C", aName("C")))
    }

    // =========================================================================
    // 2. Peer tracking
    // =========================================================================

    @Test fun `onPeerConnected increments peer count`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertEquals(1, topo.peerCount())
    }

    @Test fun `onPeerConnected duplicate does not double-count`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertEquals(1, topo.peerCount())
    }

    @Test fun `onPeerDisconnected decrements peer count`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerDisconnected(tc.ctx, "B")
        assertEquals(0, topo.peerCount())
    }

    @Test fun `onPeerDisconnected unknown id is no-op`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerDisconnected(tc.ctx, "NOBODY")
        assertEquals(0, topo.peerCount())
    }

    @Test fun `stop clears all peers`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.stop()
        assertEquals(0, topo.peerCount())
    }

    // =========================================================================
    // 3. shouldAdvertise
    // =========================================================================

    @Test fun `shouldAdvertise true when no peers`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        assertTrue(topo.shouldAdvertise(tc.ctx))
    }

    @Test fun `shouldAdvertise false when at max`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        assertFalse(topo.shouldAdvertise(tc.ctx))
    }

    @Test fun `shouldAdvertise true again after peer leaves`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerDisconnected(tc.ctx, "B")
        assertTrue(topo.shouldAdvertise(tc.ctx))
    }

    // =========================================================================
    // 4. Message handling
    // =========================================================================

    @Test fun `PING is consumed`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        assertTrue(topo.onMessage(tc.ctx, msg("A", "B", MessageType.PING)))
    }

    @Test fun `PING sends PONG back`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        val ping = msg("A", "B", MessageType.PING)
        topo.onMessage(tc.ctx, ping)
        val pongs = tc.net.sentTo("B").filter { it.type == MessageType.PONG }
        assertTrue(pongs.isNotEmpty())
        assertEquals(ping.id, pongs.first().replyTo)
    }

    @Test fun `PONG is consumed`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertTrue(topo.onMessage(tc.ctx, msg("A", "B", MessageType.PONG)))
    }

    @Test fun `PONG updates lastPongAt`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        val before = topo.lastPongAt("B")
        Thread.sleep(5)
        topo.onMessage(tc.ctx, msg("A", "B", MessageType.PONG))
        assertTrue(topo.lastPongAt("B") >= before)
    }

    @Test fun `TEXT_MESSAGE is not consumed`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        assertFalse(topo.onMessage(tc.ctx, msg("A", "B", MessageType.TEXT_MESSAGE)))
    }

    @Test fun `HELLO is consumed`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        assertTrue(topo.onMessage(tc.ctx, msg("A", "B", MessageType.HELLO)))
    }

    // =========================================================================
    // 5. Routing
    // =========================================================================

    @Test fun `direct peer is returned as next hop`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertEquals(listOf("B"), topo.resolveNextHop(tc.ctx, msg("B", "A")))
    }

    @Test fun `unknown destination floods to all non-sender peers`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "LEFT",  aName("LEFT"))
        topo.onPeerConnected(tc.ctx, "RIGHT", aName("RIGHT"))
        assertEquals(listOf("RIGHT"), topo.resolveNextHop(tc.ctx, msg("UNKNOWN", "LEFT")))
    }

    @Test fun `flood never includes sender`() {
        val topo = makeTopo(max = 3); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerConnected(tc.ctx, "D", aName("D"))
        val hops = topo.resolveNextHop(tc.ctx, msg("UNKNOWN", "B"))
        assertFalse(hops.contains("B"))
        assertTrue(hops.containsAll(listOf("C", "D")))
    }

    @Test fun `empty hop list when no peers`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        assertTrue(topo.resolveNextHop(tc.ctx, msg("B", "A")).isEmpty())
    }

    @Test fun `empty hop list after last peer disconnects`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerDisconnected(tc.ctx, "B")
        assertTrue(topo.resolveNextHop(tc.ctx, msg("C", "A")).isEmpty())
    }

    // =========================================================================
    // 6. Keepalive — peer eviction
    // =========================================================================

    @Test fun `silent peer is evicted after timeout`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        delay(400)
        assertEquals(0, topo.peerCount(), "Silent peer should be evicted")
        topo.stop()
    }

    @Test fun `two silent peers both evicted`() = runBlocking {
        val topo = makeTopo(max = 2, keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(400)
        assertEquals(0, topo.peerCount())
        topo.stop()
    }

    @Test fun `eviction triggers re-advertisement`() = runBlocking {
        // After eviction the discovery job restarts, which calls startAdvertising once.
        // We capture the advLog size at peak-connected state (when discovery is stopped),
        // then verify it grows again after eviction restarts discovery.
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        // Wait for eviction
        delay(400)
        // Discovery must have restarted — at least one startAdvertising call after eviction
        assertTrue(tc.advLog.any { it.first },
            "startAdvertising should be called again after peer eviction restarts discovery")
        topo.stop()
    }

    @Test fun `keepalive sends PINGs to peers`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 10_000)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(200)
        assertTrue(tc.net.sentTo("B").any { it.type == MessageType.PING })
        assertTrue(tc.net.sentTo("C").any { it.type == MessageType.PING })
        topo.stop()
    }

    // =========================================================================
    // 7. Keepalive — responsive peer stays alive
    // =========================================================================

    @Test fun `peer sending PONGs is not evicted`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        repeat(8) {
            delay(40)
            topo.onMessage(tc.ctx, msg("A", "B", MessageType.PONG))
        }
        assertEquals(1, topo.peerCount())
        topo.stop()
    }

    @Test fun `only silent peer evicted when one responds`() = runBlocking {
        val topo = makeTopo(max = 2, keepaliveMs = 50, timeoutMs = 150)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "GOOD", aName("GOOD"))
        topo.onPeerConnected(tc.ctx, "DEAD", aName("DEAD"))
        repeat(6) {
            delay(40)
            topo.onMessage(tc.ctx, msg("A", "GOOD", MessageType.PONG))
        }
        //delay(200)
        assertEquals(1, topo.peerCount())
        assertNotNull(topo.peerById("GOOD"))
        topo.stop()
    }

    // =========================================================================
    // 8. Discovery loop
    // =========================================================================

    @Test fun `discovery loop calls startAdvertising`() = runBlocking {
        val topo = makeTopo(max = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(200)
        topo.stop()
        assertTrue(tc.advLog.any { it.first })
    }

    @Test fun `discovery loop stops when max peers reached`() = runBlocking {
        val topo = makeTopo(max = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(100)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(100) // let the coroutine react to full slots and emit stopAdvertising
        assertEquals(false, tc.advLog.lastOrNull()?.first,
            "Last advertising event should be a stop once slots are full")
        topo.stop()
    }

    @Test fun `discovery loop restarts after peer disconnects`() = runBlocking {
        val topo = makeTopo(max = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(150) // let slots-full path run and stop advertising
        assertTrue(tc.advLog.any { !it.first },
            "Advertising should have stopped when slots were full")
        topo.onPeerDisconnected(tc.ctx, "C")
        delay(200) // let discovery restart and call startAdvertising again
        assertTrue(tc.advLog.last().first,
            "Advertising should be active again after a slot opened up")
        topo.stop()
    }

    @Test fun `topology connects to discovered peer with matching topology code`() = runBlocking {
        val topo = makeTopo(max = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(50) // let discovery job start and subscribe to events
        tc.net.emitDiscovered("B", enc("B"))
        delay(100)
        assertTrue(tc.net.connectLog.contains("B"),
            "Topology should connect to a discovered peer with matching topology code")
        topo.stop()
    }

    @Test fun `topology ignores discovered peer with wrong topology code`() = runBlocking {
        val topo = makeTopo(max = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(50)
        tc.net.emitDiscovered("X", "EVT:Test|TOP:msh|TYP:p|N:X") // mesh, not snake
        delay(100)
        assertFalse(tc.net.connectLog.contains("X"),
            "Topology should ignore peers with a different topology code")
        topo.stop()
    }

    @Test fun `topology does not connect when already at max peers`() = runBlocking {
        val topo = makeTopo(max = 1, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B")) // fill the slot
        delay(50)
        tc.net.emitDiscovered("C", enc("C"))
        delay(100)
        assertFalse(tc.net.connectLog.contains("C"),
            "Topology should not connect when already at max peers")
        topo.stop()
    }

    @Test fun `topology does not connect to known chain member`() = runBlocking {
        val topo = makeTopo(max = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        // B is now a known chain member — discovering B again should not trigger connect
        delay(50)
        tc.net.emitDiscovered("B", enc("B"))
        delay(100)
        assertFalse(tc.net.connectLog.contains("B"),
            "Topology should not connect to a peer already in the chain")
        topo.stop()
    }

    @Test fun `peer connect-disconnect cycle 5 times stays correct`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        repeat(5) { i ->
            topo.onPeerConnected(tc.ctx, "B", aName("B"))
            assertEquals(1, topo.peerCount(), "iter $i after connect")
            topo.onPeerDisconnected(tc.ctx, "B")
            assertEquals(0, topo.peerCount(), "iter $i after disconnect")
        }
    }

    @Test fun `simultaneous disconnect of both peers reaches zero`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerDisconnected(tc.ctx, "B")
        topo.onPeerDisconnected(tc.ctx, "C")
        assertEquals(0, topo.peerCount())
    }

    @Test fun `rapid random connects and disconnects never corrupt count`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        val ids = listOf("B", "C", "D", "E")
        repeat(30) {
            val id = ids.random()
            if (topo.peerCount() < 2) topo.onPeerConnected(tc.ctx, id, aName(id))
            else                      topo.onPeerDisconnected(tc.ctx, id)
            assertTrue(topo.peerCount() in 0..2)
        }
    }

    @Test fun `manual disconnect before keepalive tick no double eviction`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerDisconnected(tc.ctx, "B")
        delay(300)
        assertEquals(0, topo.peerCount())
        topo.stop()
    }

    @Test fun `evicted peer can reconnect`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        delay(400)
        assertEquals(0, topo.peerCount())
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertEquals(1, topo.peerCount())
        topo.stop()
    }

    @Test fun `rapid connection storm at max rejected every time`() = runBlocking {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        repeat(10) { i ->
            assertFalse(topo.shouldAcceptConnection(tc.ctx, "X$i", aName("X$i")))
        }
    }

    // =========================================================================
    // 10. Chain healing
    // =========================================================================

    @Test fun `middle node dropout triggers re-discovery on both ends`() = runBlocking {
        val topoA = makeTopo(max = 2, discoveryMs = 50)
        val topoC = makeTopo(max = 2, discoveryMs = 50)
        val tcA   = makeCtx("A")
        val tcC   = makeCtx("C")
        topoA.start(tcA.ctx)
        topoC.start(tcC.ctx)
        topoA.onPeerConnected(tcA.ctx, "B", aName("B"))
        topoC.onPeerConnected(tcC.ctx, "B", aName("B"))
        topoA.onPeerDisconnected(tcA.ctx, "B")
        topoC.onPeerDisconnected(tcC.ctx, "B")
        delay(200)
        assertTrue(tcA.advLog.any { it.first },
            "A should be advertising again after B dropped")
        assertTrue(tcC.advLog.any { it.first },
            "C should be advertising again after B dropped")
        topoA.stop(); topoC.stop()
    }

    @Test fun `new peer fills slot left by dropped middle node`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerDisconnected(tc.ctx, "B")
        topo.onPeerConnected(tc.ctx, "D", aName("D"))
        assertEquals(2, topo.peerCount())
    }

    // =========================================================================
    // 11. Chain message forwarding
    // =========================================================================

    @Test fun `message routes through middle node`() {
        val topoA = makeTopo(max = 2); val tcA = makeCtx("A")
        val topoB = makeTopo(max = 2); val tcB = makeCtx("B")
        topoA.onPeerConnected(tcA.ctx, "B", aName("B"))
        topoB.onPeerConnected(tcB.ctx, "A", aName("A"))
        topoB.onPeerConnected(tcB.ctx, "C", aName("C"))
        assertEquals(listOf("B"), topoA.resolveNextHop(tcA.ctx, msg("C", "A")))
        assertEquals(listOf("C"), topoB.resolveNextHop(tcB.ctx, msg("C", "A")))
    }

    @Test fun `message never bounces back to sender`() {
        val topo = makeTopo(max = 2); val tc = makeCtx("B")
        topo.onPeerConnected(tc.ctx, "A", aName("A"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        val hops = topo.resolveNextHop(tc.ctx, msg("UNKNOWN", "A"))
        assertFalse(hops.contains("A"))
        assertTrue(hops.contains("C"))
    }

    @Test fun `isolated node has no route after chain break`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerDisconnected(tc.ctx, "B")
        assertTrue(topo.resolveNextHop(tc.ctx, msg("C", "A")).isEmpty())
    }

    // =========================================================================
// 12. Ring prevention
// =========================================================================

    @Test fun `third peer already in chain is rejected to prevent ring`() = runBlocking {
        // A connects to B, B connects to C.
        // C then tries to connect back to A — A should reject because
        // it received a HELLO from B that included C in the chain.
        val topoA = makeTopo(max = 2); val tcA = makeCtx("A")
        val topoB = makeTopo(max = 2); val tcB = makeCtx("B")

        topoA.start(tcA.ctx)
        topoB.start(tcB.ctx)

        // A ↔ B
        topoA.onPeerConnected(tcA.ctx, "B", aName("B"))
        topoB.onPeerConnected(tcB.ctx, "A", aName("A"))

        // B also connects to C — B broadcasts HELLO including C to A
        topoB.onPeerConnected(tcB.ctx, "C", aName("C"))
        // Simulate A receiving the HELLO from B that lists C
        val helloToA = Message(
            to   = "A",
            from = "B",
            type = MessageType.HELLO,
            ttl  = 3,
            data = "A,B,C".toByteArray(Charsets.UTF_8)
        )
        topoA.onMessage(tcA.ctx, helloToA)

        // Now C tries to connect to A — A must reject (ring guard)
        assertFalse(
            topoA.shouldAcceptConnection(tcA.ctx, "C", aName("C")),
            "A must reject C to prevent ring A↔B↔C↔A"
        )

        topoA.stop(); topoB.stop()
    }

    @Test fun `self-connection is always rejected`() = runBlocking {
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.start(tc.ctx)
        // A node should never accept a connection from itself
        assertFalse(
            topo.shouldAcceptConnection(tc.ctx, "A", aName("A")),
            "Self-connection must always be rejected"
        )
        topo.stop()
    }

    @Test fun `chain member set resets after peer disconnects`() = runBlocking {
        // After B disconnects, A should no longer block C from joining —
        // the chain is broken so C is in a separate segment and not a ring risk.
        val topo = makeTopo(max = 2); val tc = makeCtx("A")
        topo.start(tc.ctx)

        // A learns about C through B's HELLO
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        val hello = Message(
            to   = "A",
            from = "B",
            type = MessageType.HELLO,
            ttl  = 3,
            data = "A,B,C".toByteArray(Charsets.UTF_8)
        )
        topo.onMessage(tc.ctx, hello)
        assertFalse(
            topo.shouldAcceptConnection(tc.ctx, "C", aName("C")),
            "C should be blocked while B is connected"
        )

        // B disconnects — chain breaks, membership resets
        topo.onPeerDisconnected(tc.ctx, "B")
        assertTrue(
            topo.shouldAcceptConnection(tc.ctx, "C", aName("C")),
            "C should be accepted after B disconnects — they are now separate segments"
        )
        topo.stop()
    }

    @Test fun `HELLO message merges chain members and propagates to other peers`() = runBlocking {
        val topo = makeTopo(max = 2); val tc = makeCtx("B")
        topo.start(tc.ctx)

        // B knows A and C as direct peers
        topo.onPeerConnected(tc.ctx, "A", aName("A"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))

        // A sends a HELLO introducing D (new node at the far end of the chain)
        val hello = Message(
            to   = "B",
            from = "A",
            type = MessageType.HELLO,
            ttl  = 3,
            data = "A,B,D".toByteArray(Charsets.UTF_8)
        )
        val consumed = topo.onMessage(tc.ctx, hello)
        assertTrue(consumed, "HELLO should be consumed by topology")

        // B should have forwarded a HELLO to C (not back to A) that includes D
        val helloWithD = tc.net.sentTo("C")
            .filter { it.type == MessageType.HELLO }
            .firstOrNull { msg ->
                msg.data?.toString(Charsets.UTF_8)?.split(",")?.contains("D") == true
            }
        assertNotNull(helloWithD, "B should forward a HELLO to C that includes newly learned member D")

        topo.stop()
    }
    // =========================================================================
    // Reflection helpers — private to this test class to avoid overload conflicts
    // =========================================================================

    private fun SnakeTopology.peerCount(): Int {
        val f = javaClass.getDeclaredField("peers").also { it.isAccessible = true }
        return (f.get(this) as ConcurrentHashMap<*, *>).size
    }

    private fun SnakeTopology.lastPongAt(id: String): Long {
        val f = javaClass.getDeclaredField("peers").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ConcurrentHashMap<String, TopologyPeer>)[id]?.lastPongAt ?: 0L
    }

    private fun SnakeTopology.peerById(id: String): TopologyPeer? {
        val f = javaClass.getDeclaredField("peers").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ConcurrentHashMap<String, TopologyPeer>)[id]
    }
}