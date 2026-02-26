package edu.uwm.cs595.goup11.backend.network.topology
import SnakeTopology
import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.Network
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.Peer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SnakeTopologyUnitTest {

    private fun makeSnakeClient(id: String): Pair<Client, LocalNetwork> {
        val network  = LocalNetwork()
        val topology = SnakeTopology(
            maxPeerCount        = 2,
            discoveryIntervalMs = 200,
            keepaliveIntervalMs = 300,
            keepaliveTimeoutMs  = 900
        )
        val client = Client(id = id, network = null, topology = topology)
        client.attachNetwork(network, Network.Config(defaultTtl = 5))
        return Pair(client, network)
    }

    /**
     * Creates a seed node that has already called network.create() and is advertising,
     * ready for other nodes to discover.
     */
    private fun makeSeedNode(networkId: String): Pair<Client, LocalNetwork> {
        val (client, network) = makeSnakeClient("SEED")
        runBlocking { network.create(networkId) }
        return Pair(client, network)
    }



    @Before
    fun setUp() {
        LocalNetwork.purge()
    }

    @After
    fun tearDown() {
        LocalNetwork.purge()
    }

    @Test
    fun `seed node should be advertising`() = runBlocking {
        val (_, network) = makeSeedNode("EVT:Test|TOP:snk|TYP:p|N:SEED")
        delay(100)
        assertEquals(
            "Seed should be in Hosting state immediately after create()",
            NetworkState.Hosting::class,
            network.state.value::class,
        )
    }

    /**
     * shouldAdvertise returns false when the node is at maxPeerCount.
     */
    @Test
    fun `shouldAdvertise returns false when at max peers`() = runBlocking {
        val (seed, _) = makeSeedNode("EVT:Test|TOP:snk|TYP:p|N:SEED")
        val topo = seed.getTopology() as SnakeTopology
        val ctx  = seed.getTopologyContext()

        topo.onPeerConnected(ctx,Peer.generatePeer("Test", "snk", TopologyStrategy.Role.PEER, "X"))
        topo.onPeerConnected(ctx, Peer.generatePeer("Test", "snk", TopologyStrategy.Role.PEER, "Y"))
        delay(100)

        assertTrue("Node at maxPeerCount should NOT advertise",!topo.shouldAdvertise(ctx),)
    }

    /**
     * shouldAdvertise returns true when the node is below maxPeerCount.
     */
    @Test
    fun `shouldAdvertise returns true when below max peers`() = runBlocking {
        val (seed, _) = makeSeedNode("EVT:Test|TOP:snk|TYP:p|N:SEED")
        val topo = seed.getTopology() as SnakeTopology
        val ctx  = seed.getTopologyContext()

        assertTrue("Node with 0 peers should advertise", topo.shouldAdvertise(ctx))
    }


    /**
     * peerCount is 0 initially, increments on connect, decrements on disconnect.
     */
    @Test
    fun `peerCount tracks connect and disconnect correctly`() = runBlocking {
        val (seed, _) = makeSeedNode("EVT:Test|TOP:snk|TYP:p|N:SEED")
        val topo = seed.getTopology() as SnakeTopology
        val ctx  = seed.getTopologyContext()

        assertEquals("Should start with 0 peers",0, topo.peerCount())

        topo.onPeerConnected(ctx,Peer.generatePeer("Test", "snk", TopologyStrategy.Role.PEER, "X"))
        assertEquals("Should have 1 peer after connect",1, topo.peerCount())

        topo.onPeerDisconnected(ctx, Peer.generatePeer("Test", "snk", TopologyStrategy.Role.PEER, "X").endpointId)
        assertEquals("Should have 0 peers after disconnect",0, topo.peerCount(), )
    }

    /**
     * After the first joiner connects to the seed, the joiner should record exactly 1 peer.
     */
    @Test
    fun `first joiner records 1 peer after connecting`() = runBlocking {
        val networkId = "EVT:Test|TOP:snk|TYP:p|N:SEED"
        makeSeedNode(networkId)

        val (nodeB, _) = makeSnakeClient("NODE_B")
        nodeB.joinNetwork(networkId)
        delay(200)

        assertEquals("NODE_B should have exactly 1 peer after joining",1, nodeB.getTopology().peerCount())
    }
}

// =============================================================================
// Test extension helpers
// Expose private internals via reflection so production code stays clean.
// =============================================================================

/** Returns the topology field from a Client instance. */
fun Client.getTopology(): TopologyStrategy {
    val f = this::class.java.getDeclaredField("topology")
    f.isAccessible = true
    return f.get(this) as TopologyStrategy
}

/** Returns the lazily-initialised TopologyContext from a Client instance. */
fun Client.getTopologyContext(): TopologyContext {
    val f = this::class.java.getDeclaredField("topologyContext\$delegate")
    f.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return (f.get(this) as Lazy<TopologyContext>).value
}

/** Returns the current peer count from the strategy's internal ConcurrentHashMap. */
fun TopologyStrategy.peerCount(): Int {
    val f = this::class.java.getDeclaredField("peers")
    f.isAccessible = true
    return (f.get(this) as java.util.concurrent.ConcurrentHashMap<*, *>).size
}

/** Returns the lastPongAt timestamp for a specific peer endpointId. */
fun SnakeTopology.lastPongAt(endpointId: String): Long {
    val f = this::class.java.getDeclaredField("peers")
    f.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val map = f.get(this) as java.util.concurrent.ConcurrentHashMap<String, TopologyPeer>
    return map[endpointId]?.lastPongAt ?: 0L
}

/** Returns true if the discovery coroutine job is currently active. */
fun SnakeTopology.isDiscovering(): Boolean {
    val f = this::class.java.getDeclaredField("discoveryJob")
    f.isAccessible = true
    val job = f.get(this) as? kotlinx.coroutines.Job
    return job?.isActive == true
}