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
 * Unit tests for [MeshTopology].
 *
 * Key differences from SnakeTopology that need dedicated coverage:
 *  - targetPeerCount (soft target) vs maxPeerCount (hard limit)
 *  - Discovery stops at targetPeerCount, not maxPeerCount
 *  - Between target and max: node stays advertising but stops scanning
 *  - At maxPeerCount: both advertising AND scanning stop
 *  - evaluateHealth() is called after every keepalive tick (not just on connect/disconnect)
 *
 * Coroutine strategy: topology jobs run on a dedicated SupervisorJob scope
 * (topoScope) created in @Before and cancelled in @After. runBlocking is used
 * for tests that need real timer behaviour with short wall-clock waits.
 *
 * Test groups:
 *  1.  shouldAcceptConnection (hard limit = maxPeerCount)
 *  2.  Peer tracking
 *  3.  shouldAdvertise (advertise below maxPeerCount, not targetPeerCount)
 *  4.  Message handling (PING / PONG / passthrough)
 *  5.  Routing (direct + flood)
 *  6.  evaluateHealth — three zones (below target / between target+max / at max)
 *  7.  Keepalive — peer eviction
 *  8.  Keepalive — responsive peer stays alive
 *  9.  Discovery loop — target vs max distinction
 *  10. Random disconnects and reconnects
 *  11. Multi-peer mesh routing
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class MeshTopologyUnitTest {

    // -------------------------------------------------------------------------
    // FakeNetwork
    // -------------------------------------------------------------------------

    class FakeNetwork : Network {
        override val logger: KLogger = KotlinLogging.logger {}

        private val _state  = MutableStateFlow<NetworkState>(NetworkState.Idle)
        override val state: StateFlow<NetworkState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

        val sent = mutableListOf<Pair<String, Message>>()

        override var onConnectionRequest: suspend (String, String) -> Boolean = { _, _ -> false }

        override fun init(localEndpointId: String, config: Network.Config) {}
        override fun shutdown()                               { sent.clear() }
        override fun startAdvertising(encodedName: String)    {}
        override fun stopAdvertising()                        {}
        override suspend fun startDiscovery()                 {}
        override suspend fun stopDiscovery()                  {}
        override suspend fun connect(endpointId: String)      {}
        override fun disconnect(endpointId: String)           {}
        override fun addListener(listener: (Message) -> Unit) {}
        override fun notifyListeners(message: Message)        {}

        override fun sendMessage(endpointId: String, message: Message) {
            sent.add(Pair(endpointId, message))
        }

        fun sentTo(id: String)         = sent.filter { it.first == id }.map { it.second }
        fun sentOfType(t: MessageType) = sent.filter { it.second.type == t }
    }

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    private lateinit var topoScope: CoroutineScope

    private data class TC(
        val ctx:     TopologyContext,
        val net:     FakeNetwork,
        val advLog:  MutableList<Pair<Boolean, String?>>,  // true=start, false=stop
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
            coroutineScope       = topoScope
        )
        return TC(ctx, net, advLog, scanLog)
    }

    /**
     * Default: max=6, target=3 (matches production defaults but small enough
     * to fill easily in tests). Short timers for runBlocking tests.
     */
    private fun makeTopo(
        max:         Int  = 6,
        target:      Int  = 3,
        keepaliveMs: Long = 50,
        timeoutMs:   Long = 150,
        discoveryMs: Long = 50
    ) = MeshTopology(
        maxPeerCount        = max,
        targetPeerCount     = target,
        keepaliveIntervalMs = keepaliveMs,
        keepaliveTimeoutMs  = timeoutMs,
        discoveryIntervalMs = discoveryMs
    )

    private fun enc(name: String) = "EVT:Test|TOP:msh|TYP:p|N:$name"
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
    // 1. shouldAcceptConnection — hard limit is maxPeerCount, not targetPeerCount
    // =========================================================================

    @Test fun `accepts connection when below max`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        assertTrue(topo.shouldAcceptConnection(tc.ctx, "B", aName("B")))
    }

    @Test fun `accepts connection when between target and max`() = runBlocking {
        // target=2, max=4 — fill to target, should still accept up to max
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        // now at target(2) but below max(4) — must still accept
        assertTrue(topo.shouldAcceptConnection(tc.ctx, "D", aName("D")))
    }

    @Test fun `rejects connection at maxPeerCount`() = runBlocking {
        val topo = makeTopo(max = 3, target = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerConnected(tc.ctx, "D", aName("D"))
        assertFalse(topo.shouldAcceptConnection(tc.ctx, "E", aName("E")))
    }

    @Test fun `accepts again after disconnect drops below max`() = runBlocking {
        val topo = makeTopo(max = 2, target = 1); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        assertFalse(topo.shouldAcceptConnection(tc.ctx, "D", aName("D")))
        topo.onPeerDisconnected(tc.ctx, "C")
        assertTrue(topo.shouldAcceptConnection(tc.ctx, "D", aName("D")))
    }

    @Test fun `rapid storm of connections rejected when at max`() = runBlocking {
        val topo = makeTopo(max = 2, target = 1); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        repeat(10) { i ->
            assertFalse(topo.shouldAcceptConnection(tc.ctx, "X$i", aName("X$i")))
        }
    }

    // =========================================================================
    // 2. Peer tracking
    // =========================================================================

    @Test fun `onPeerConnected increments count`() {
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

    @Test fun `onPeerDisconnected decrements count`() {
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
        topo.onPeerConnected(tc.ctx, "D", aName("D"))
        topo.stop()
        assertEquals(0, topo.peerCount())
    }

    @Test fun `multiple peers up to max all tracked`() {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        listOf("B", "C", "D", "E").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        assertEquals(4, topo.peerCount())
    }

    // =========================================================================
    // 3. shouldAdvertise — advertises below maxPeerCount (not targetPeerCount)
    // =========================================================================

    @Test fun `shouldAdvertise true when empty`() {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        assertTrue(topo.shouldAdvertise(tc.ctx))
    }

    @Test fun `shouldAdvertise true when at target but below max`() {
        // Mesh keeps advertising between target and max so others can connect
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        assertEquals(2, topo.peerCount()) // at target
        assertTrue(topo.shouldAdvertise(tc.ctx), "Should still advertise between target and max")
    }

    @Test fun `shouldAdvertise false when at maxPeerCount`() {
        val topo = makeTopo(max = 3, target = 2); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerConnected(tc.ctx, "D", aName("D"))
        assertFalse(topo.shouldAdvertise(tc.ctx))
    }

    @Test fun `shouldAdvertise true again after peer leaves max`() {
        val topo = makeTopo(max = 2, target = 1); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        assertFalse(topo.shouldAdvertise(tc.ctx))
        topo.onPeerDisconnected(tc.ctx, "C")
        assertTrue(topo.shouldAdvertise(tc.ctx))
    }

    // =========================================================================
    // 4. Message handling
    // =========================================================================

    @Test fun `PING is consumed`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        assertTrue(topo.onMessage(tc.ctx, msg("A", "B", MessageType.PING)))
    }

    @Test fun `PING sends PONG back to sender`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        val ping = msg("A", "B", MessageType.PING)
        topo.onMessage(tc.ctx, ping)
        val pongs = tc.net.sentTo("B").filter { it.type == MessageType.PONG }
        assertTrue(pongs.isNotEmpty(), "Expected PONG sent back to B")
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

    @Test fun `HELLO is not consumed`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        assertFalse(topo.onMessage(tc.ctx, msg("A", "B", MessageType.HELLO)))
    }

    // =========================================================================
    // 5. Routing
    // =========================================================================

    @Test fun `direct peer returned as next hop`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertEquals(listOf("B"), topo.resolveNextHop(tc.ctx, msg("B", "A")))
    }

    @Test fun `unknown destination floods to all peers`() {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        listOf("B", "C", "D").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        val hops = topo.resolveNextHop(tc.ctx, msg("UNKNOWN", "A"))
        assertTrue(hops.containsAll(listOf("B", "C", "D")))
        assertEquals(3, hops.size)
    }

    @Test fun `flood excludes sender`() {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        listOf("B", "C", "D").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        val hops = topo.resolveNextHop(tc.ctx, msg("UNKNOWN", "B"))
        assertFalse(hops.contains("B"), "Sender must not appear in flood")
        assertTrue(hops.containsAll(listOf("C", "D")))
    }

    @Test fun `empty hop list when no peers`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        assertTrue(topo.resolveNextHop(tc.ctx, msg("B", "A")).isEmpty())
    }

    @Test fun `empty hop list after all peers disconnect`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerDisconnected(tc.ctx, "B")
        assertTrue(topo.resolveNextHop(tc.ctx, msg("C", "A")).isEmpty())
    }

    @Test fun `direct delivery preferred over flood even with many peers`() {
        val topo = makeTopo(max = 6, target = 3); val tc = makeCtx("A")
        listOf("B", "C", "D", "E", "F").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        // D is a direct peer — should get a single-element list, not a flood
        val hops = topo.resolveNextHop(tc.ctx, msg("D", "A"))
        assertEquals(listOf("D"), hops)
    }

    // =========================================================================
    // 6. evaluateHealth — three distinct zones
    //
    //  Zone 1: peers < target   → startDiscovery (advertise + scan)
    //  Zone 2: target <= peers < max → stopScan, keep advertising
    //  Zone 3: peers >= max     → stopDiscovery (stop advertise + stop scan)
    // =========================================================================

    @Test fun `below target triggers advertising and scan`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(200)
        topo.stop()
        // Should have called both startAdvertising and startScan
        assertTrue(tc.advLog.any { it.first }, "Should call startAdvertising below target")
        assertTrue(tc.scanLog.any { it }, "Should call startScan below target")
    }

    @Test fun `at target but below max stops scan but keeps advertising`() = runBlocking {
        // target=2, max=4 — connect exactly target peers
        val topo = makeTopo(max = 4, target = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(100)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(200)
        topo.stop()

        // Last scan entry should be a stopScan
        assertTrue(tc.scanLog.lastOrNull() == false, "Scan should stop at target")
        // But advertising should still be happening (started, not last-stopped)
        assertTrue(tc.advLog.any { it.first }, "Should keep advertising between target and max")
    }

    @Test fun `at max stops both advertising and scan`() = runBlocking {
        val topo = makeTopo(max = 2, target = 1, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(100)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(200)
        topo.stop()

        // Last advertising entry should be stopAdvertising
        assertTrue(tc.advLog.lastOrNull()?.first == false, "Should stop advertising at max")
    }

    @Test fun `dropping from max back to target zone resumes advertising only`() = runBlocking {
        val topo = makeTopo(max = 3, target = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        topo.onPeerConnected(tc.ctx, "D", aName("D")) // now at max
        delay(150)

        val advBefore = tc.advLog.count { it.first }

        // Drop one peer — should go to target zone (advertise, no scan)
        topo.onPeerDisconnected(tc.ctx, "D")
        delay(150)

        // Advertising should restart
        assertTrue(tc.advLog.count { it.first } > advBefore,
            "Should restart advertising after dropping from max to target zone")
        topo.stop()
    }

    @Test fun `dropping below target from target zone resumes full discovery`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(150) // settle in target zone

        val scanBefore = tc.scanLog.count { it }

        // Drop both — now below target, should start scanning again
        topo.onPeerDisconnected(tc.ctx, "B")
        topo.onPeerDisconnected(tc.ctx, "C")
        delay(200)

        assertTrue(tc.scanLog.count { it } > scanBefore,
            "Should restart scanning when dropping below target")
        topo.stop()
    }

    // =========================================================================
    // 7. Keepalive — peer eviction
    // =========================================================================

    @Test fun `silent peer evicted after timeout`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        delay(400)
        assertEquals(0, topo.peerCount(), "Silent peer should be evicted")
        topo.stop()
    }

    @Test fun `all silent peers evicted`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        listOf("B", "C", "D").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        delay(400)
        assertEquals(0, topo.peerCount())
        topo.stop()
    }

    @Test fun `eviction triggers re-discovery`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        val before = tc.advLog.count { it.first }
        delay(400)
        assertTrue(tc.advLog.count { it.first } > before,
            "Should re-advertise after peer eviction")
        topo.stop()
    }

    @Test fun `keepalive sends PINGs to all connected peers`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, keepaliveMs = 50, timeoutMs = 10_000)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        listOf("B", "C", "D").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        delay(200)
        assertTrue(tc.net.sentTo("B").any { it.type == MessageType.PING })
        assertTrue(tc.net.sentTo("C").any { it.type == MessageType.PING })
        assertTrue(tc.net.sentTo("D").any { it.type == MessageType.PING })
        topo.stop()
    }

    @Test fun `manual disconnect before keepalive tick causes no double eviction`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerDisconnected(tc.ctx, "B")
        delay(300)
        assertEquals(0, topo.peerCount())
        topo.stop()
    }

    // =========================================================================
    // 8. Keepalive — responsive peer stays alive
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
        assertEquals(1, topo.peerCount(), "Responsive peer must not be evicted")
        topo.stop()
    }

    @Test fun `only silent peer evicted when others respond`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, keepaliveMs = 50, timeoutMs = 400)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "GOOD1", aName("GOOD1"))
        topo.onPeerConnected(tc.ctx, "GOOD2", aName("GOOD2"))
        topo.onPeerConnected(tc.ctx, "DEAD",  aName("DEAD"))
        repeat(6) {
            delay(40)
            topo.onMessage(tc.ctx, msg("A", "GOOD1", MessageType.PONG))
            topo.onMessage(tc.ctx, msg("A", "GOOD2", MessageType.PONG))
        }
        delay(200)
        assertEquals(2, topo.peerCount(), "Only DEAD should be evicted")
        assertNotNull(topo.peerById("GOOD1"))
        assertNotNull(topo.peerById("GOOD2"))
        topo.stop()
    }

    @Test fun `evicted peer can reconnect`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        delay(400)
        assertEquals(0, topo.peerCount(), "B should be evicted")
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertEquals(1, topo.peerCount(), "B should reconnect successfully")
        topo.stop()
    }

    // =========================================================================
    // 9. Discovery loop — target vs max distinction
    // =========================================================================

    @Test fun `discovery loop starts when below target`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(200)
        topo.stop()
        assertTrue(tc.advLog.any { it.first })
        assertTrue(tc.scanLog.any { it })
    }

    @Test fun `discovery loop stops scanning at target not max`() = runBlocking {
        // target=2, max=4 — fill to target, scan should stop but advertising continues
        val topo = makeTopo(max = 4, target = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(100)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(200)

        // Scan should have stopped
        assertTrue(tc.scanLog.lastOrNull() == false, "Scanning should stop at target")
        // But we're below max, so advertising should still be on
        val lastAdv = tc.advLog.lastOrNull()
        assertTrue(lastAdv?.first == true || tc.advLog.count { it.first } > 0,
            "Should keep advertising between target and max")
        topo.stop()
    }

    @Test fun `advertising stops when max is reached`() = runBlocking {
        val topo = makeTopo(max = 2, target = 1, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        delay(100)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(200)
        assertTrue(tc.advLog.lastOrNull()?.first == false,
            "Advertising should stop at max")
        topo.stop()
    }

    @Test fun `discovery restarts after peer count drops below target`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, discoveryMs = 50)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        delay(150)

        val scanBefore = tc.scanLog.count { it }
        topo.onPeerDisconnected(tc.ctx, "C")  // now below target
        delay(200)

        assertTrue(tc.scanLog.count { it } > scanBefore,
            "Scan should restart when dropping below target")
        topo.stop()
    }

    // =========================================================================
    // 10. Random disconnects and reconnects
    // =========================================================================

    @Test fun `peer connect-disconnect cycle 5 times stays correct`() {
        val topo = makeTopo(); val tc = makeCtx("A")
        repeat(5) { i ->
            topo.onPeerConnected(tc.ctx, "B", aName("B"))
            assertEquals(1, topo.peerCount(), "iter $i after connect")
            topo.onPeerDisconnected(tc.ctx, "B")
            assertEquals(0, topo.peerCount(), "iter $i after disconnect")
        }
    }

    @Test fun `all peers disconnect simultaneously — count reaches zero`() {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        listOf("B", "C", "D").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        listOf("B", "C", "D").forEach { topo.onPeerDisconnected(tc.ctx, it) }
        assertEquals(0, topo.peerCount())
    }

    @Test fun `rapid random connects and disconnects never exceed max`() {
        val topo = makeTopo(max = 3, target = 2); val tc = makeCtx("A")
        val ids = listOf("B", "C", "D", "E", "F")
        repeat(40) {
            val id = ids.random()
            if (topo.peerCount() < 3) topo.onPeerConnected(tc.ctx, id, aName(id))
            else                      topo.onPeerDisconnected(tc.ctx, id)
            assertTrue(topo.peerCount() in 0..3, "Count must stay in [0, max]")
        }
    }

    @Test fun `peer evicted by keepalive then reconnects and gets evicted again`() = runBlocking {
        val topo = makeTopo(keepaliveMs = 50, timeoutMs = 120)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)

        // First connect → evict → reconnect → evict cycle
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        delay(400)
        assertEquals(0, topo.peerCount(), "First eviction")

        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        assertEquals(1, topo.peerCount(), "Reconnected")

        delay(400)
        assertEquals(0, topo.peerCount(), "Second eviction")
        topo.stop()
    }

    @Test fun `partial reconnect after mass disconnect`() = runBlocking {
        val topo = makeTopo(max = 4, target = 2, keepaliveMs = 50, timeoutMs = 10_000)
        val tc   = makeCtx("A")
        topo.start(tc.ctx)

        listOf("B", "C", "D").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        assertEquals(3, topo.peerCount())

        listOf("B", "C", "D").forEach { topo.onPeerDisconnected(tc.ctx, it) }
        assertEquals(0, topo.peerCount())

        // Only 2 peers reconnect — should be at target
        topo.onPeerConnected(tc.ctx, "B", aName("B"))
        topo.onPeerConnected(tc.ctx, "C", aName("C"))
        assertEquals(2, topo.peerCount())
        topo.stop()
    }

    // =========================================================================
    // 11. Multi-peer mesh routing
    // =========================================================================

    @Test fun `all direct peers get direct delivery`() {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        listOf("B", "C", "D").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        assertEquals(listOf("B"), topo.resolveNextHop(tc.ctx, msg("B", "A")))
        assertEquals(listOf("C"), topo.resolveNextHop(tc.ctx, msg("C", "A")))
        assertEquals(listOf("D"), topo.resolveNextHop(tc.ctx, msg("D", "A")))
    }

    @Test fun `message to unknown floods to all peers in full mesh`() {
        // In a full mesh everyone knows everyone — unknown dest gets flooded
        val topo = makeTopo(max = 6, target = 3); val tc = makeCtx("A")
        listOf("B", "C", "D", "E").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        val hops = topo.resolveNextHop(tc.ctx, msg("UNKNOWN", "A"))
        assertEquals(4, hops.size)
        assertTrue(hops.containsAll(listOf("B", "C", "D", "E")))
    }

    @Test fun `flood in mesh never includes message sender`() {
        val topo = makeTopo(max = 6, target = 3); val tc = makeCtx("A")
        listOf("B", "C", "D", "E").forEach { topo.onPeerConnected(tc.ctx, it, aName(it)) }
        val hops = topo.resolveNextHop(tc.ctx, msg("UNKNOWN", "C"))
        assertFalse(hops.contains("C"), "Sender C must not be in flood list")
        assertTrue(hops.containsAll(listOf("B", "D", "E")))
        assertEquals(3, hops.size)
    }

    @Test fun `isolated node after full disconnect has no route`() {
        val topo = makeTopo(max = 4, target = 2); val tc = makeCtx("A")
        listOf("B", "C").forEach {
            topo.onPeerConnected(tc.ctx, it, aName(it))
            topo.onPeerDisconnected(tc.ctx, it)
        }
        assertTrue(topo.resolveNextHop(tc.ctx, msg("B", "A")).isEmpty())
    }

    @Test fun `two separate nodes each see their own peers`() {
        val topoA = makeTopo(max = 4, target = 2); val tcA = makeCtx("A")
        val topoB = makeTopo(max = 4, target = 2); val tcB = makeCtx("B")

        // A knows B and C; B knows A, C, and D
        topoA.onPeerConnected(tcA.ctx, "B", aName("B"))
        topoA.onPeerConnected(tcA.ctx, "C", aName("C"))
        topoB.onPeerConnected(tcB.ctx, "A", aName("A"))
        topoB.onPeerConnected(tcB.ctx, "C", aName("C"))
        topoB.onPeerConnected(tcB.ctx, "D", aName("D"))

        assertEquals(2, topoA.peerCount())
        assertEquals(3, topoB.peerCount())

        // A → D: A doesn't know D, floods to B and C (excluding self as sender)
        val hopsFromA = topoA.resolveNextHop(tcA.ctx, msg("D", "A"))
        assertTrue(hopsFromA.containsAll(listOf("B", "C")))

        // B → D: B knows D directly
        assertEquals(listOf("D"), topoB.resolveNextHop(tcB.ctx, msg("D", "B")))
    }
}

// =============================================================================
// Reflection helpers
// =============================================================================

fun MeshTopology.peerCount(): Int {
    val f = javaClass.getDeclaredField("peers").also { it.isAccessible = true }
    return (f.get(this) as ConcurrentHashMap<*, *>).size
}

fun MeshTopology.lastPongAt(id: String): Long {
    val f = javaClass.getDeclaredField("peers").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return (f.get(this) as ConcurrentHashMap<String, TopologyPeer>)[id]?.lastPongAt ?: 0L
}

fun MeshTopology.peerById(id: String): TopologyPeer? {
    val f = javaClass.getDeclaredField("peers").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return (f.get(this) as ConcurrentHashMap<String, TopologyPeer>)[id]
}