package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for Client + LocalNetwork + DirectoryManager working together.
 *
 * DirectoryManager is owned by Client — no manual wiring is needed here.
 * Tests verify that directory state stays consistent as clients join, leave,
 * and exchange messages through the real LocalNetwork stack.
 *
 * Each test gets a fresh LocalNetwork state via LocalNetwork.purge() in @Before/@After.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientDirectoryIntegrationTest {

    companion object {
        private const val EVENT_NAME = "IntegrationTestEvent"
        private const val CONVERGE_TIMEOUT = 15_000L  // mesh topology needs time to fully connect
        private const val SETTLE_DELAY = 200L
    }

    // -------------------------------------------------------------------------
    // Test node
    // -------------------------------------------------------------------------

    /**
     * Wraps a Client + LocalNetwork pair.
     * DirectoryManager is owned internally by Client — no manual wiring needed.
     */
    inner class TestNode(val name: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val network = LocalNetwork(scope = scope)
        val client = Client(displayName = name, scope = scope)

        suspend fun createNetwork() {
            client.attachNetwork(network, Network.Config(defaultTtl = 5))
            client.createNetwork(EVENT_NAME, MeshTopology())
        }

        suspend fun joinNetwork() {
            client.attachNetwork(network, Network.Config(defaultTtl = 5))
            client.joinNetwork(EVENT_NAME)
        }

        suspend fun leave() {
            client.leaveNetwork()
        }

        val endpointId: String? get() = client.endpointId
        fun activePeers() = client.networkPeers()
        fun allPeers() = client.fullDirectory()
    }

    private val nodes = mutableListOf<TestNode>()

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @Before
    fun setUp() {
        LocalNetwork.purge()
        nodes.clear()
    }

    @After
    fun tearDown() {
        runBlocking {
            nodes.forEach { node ->
                try {
                    node.leave()
                } catch (_: Exception) {
                }
            }
        }
        LocalNetwork.purge()
    }

    private fun node(name: String): TestNode {
        val n = TestNode(name)
        nodes.add(n)
        return n
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun awaitPeerCount(
        vararg testNodes: TestNode,
        minPeerCount: Int,
        timeoutMs: Long = CONVERGE_TIMEOUT
    ) {
        withTimeout(timeoutMs) {
            while (true) {
                if (testNodes.all { it.activePeers().size >= minPeerCount }) return@withTimeout
                delay(50)
            }
        }
    }

    /**
     * Waits until all nodes have identical active peer sets and each set
     * contains exactly [testNodes.size] peers (i.e. everyone sees everyone).
     */
    private suspend fun awaitConvergence(
        vararg testNodes: TestNode,
        timeoutMs: Long = CONVERGE_TIMEOUT
    ) {
        withTimeout(timeoutMs) {
            while (true) {
                val views = testNodes.map { n ->
                    n.activePeers().map { it.endpointId }.toSortedSet()
                }
                if (views.distinct().size == 1 && views.first().size == testNodes.size) {
                    return@withTimeout
                }
                delay(50)
            }
        }
    }

    private suspend fun awaitPeerGone(
        node: TestNode,
        peerId: String,
        timeoutMs: Long = CONVERGE_TIMEOUT
    ) {
        withTimeout(timeoutMs) {
            while (node.activePeers().any { it.endpointId == peerId }) delay(50)
        }
    }

    // -------------------------------------------------------------------------
    // Tests — single node
    // -------------------------------------------------------------------------

    @Test
    fun singleNode_afterCreateNetwork_appearsInOwnDirectory() = runBlocking {
        val alice = node("Alice")
        alice.createNetwork()

        assertTrue(alice.activePeers().any { it.endpointId == alice.endpointId })
    }

    @Test
    fun singleNode_afterCreateNetwork_directoryHasExactlyOneEntry() = runBlocking {
        val alice = node("Alice")
        alice.createNetwork()

        assertEquals(1, alice.allPeers().size)
    }

    @Test
    fun singleNode_localEntryHasActiveStatus() = runBlocking {
        val alice = node("Alice")
        alice.createNetwork()

        val entry = alice.allPeers().first()
        assertEquals(PeerStatus.ACTIVE, entry.status)
    }

    // -------------------------------------------------------------------------
    // Tests — two nodes
    // -------------------------------------------------------------------------

    @Test
    fun twoNodes_afterJoin_bothAppearInEachOthersDirectory() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        alice.createNetwork()
        bob.joinNetwork()

        awaitConvergence(alice, bob)

        assertTrue(alice.activePeers().any { it.endpointId == bob.endpointId })
        assertTrue(bob.activePeers().any { it.endpointId == alice.endpointId })
    }

    @Test
    fun twoNodes_afterJoin_eachDirectoryHasTwoActiveEntries() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        alice.createNetwork()
        bob.joinNetwork()

        awaitConvergence(alice, bob)

        assertEquals(2, alice.activePeers().size)
        assertEquals(2, bob.activePeers().size)
    }

    @Test
    fun twoNodes_bobLeaves_aliceDirectoryTombstonesBob() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        alice.createNetwork()
        bob.joinNetwork()
        awaitConvergence(alice, bob)

        val bobId = bob.endpointId!!
        bob.leave()
        awaitPeerGone(alice, bobId)

        val tombstone = alice.allPeers().firstOrNull { it.endpointId == bobId }
        assertNotNull("Bob's entry should exist as a tombstone", tombstone)
        assertEquals(PeerStatus.DISCONNECTED, tombstone!!.status)
    }

    @Test
    fun twoNodes_bobLeaves_aliceActivePeersOnlyShowsAlice() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        alice.createNetwork()
        bob.joinNetwork()
        awaitConvergence(alice, bob)

        val bobId = bob.endpointId!!
        bob.leave()
        awaitPeerGone(alice, bobId)

        assertEquals(1, alice.activePeers().size)
        assertEquals(alice.endpointId, alice.activePeers().first().endpointId)
    }

    // -------------------------------------------------------------------------
    // Tests — three nodes
    // -------------------------------------------------------------------------

    @Test
    fun threeNodes_allJoin_allDirectoriesContainAllThree() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")
        val charlie = node("Charlie")

        alice.createNetwork()
        bob.joinNetwork()
        charlie.joinNetwork()

        awaitConvergence(alice, bob, charlie)

        val expected = setOf(alice.endpointId!!, bob.endpointId!!, charlie.endpointId!!)
        assertEquals(expected, alice.activePeers().map { it.endpointId }.toSet())
        assertEquals(expected, bob.activePeers().map { it.endpointId }.toSet())
        assertEquals(expected, charlie.activePeers().map { it.endpointId }.toSet())
    }

    @Test
    fun threeNodes_oneLeaves_remainingTwoStillKnowAboutEachOther() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")
        val charlie = node("Charlie")

        alice.createNetwork()
        bob.joinNetwork()
        charlie.joinNetwork()
        awaitConvergence(alice, bob, charlie)

        val bobId = bob.endpointId!!
        bob.leave()
        awaitPeerGone(alice, bobId)
        awaitPeerGone(charlie, bobId)

        assertTrue(alice.activePeers().any { it.endpointId == charlie.endpointId })
        assertTrue(charlie.activePeers().any { it.endpointId == alice.endpointId })
    }

    @Test
    fun threeNodes_oneLeaves_leaverIsTombstonedInRemainingDirectories() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")
        val charlie = node("Charlie")

        alice.createNetwork()
        bob.joinNetwork()
        charlie.joinNetwork()
        awaitConvergence(alice, bob, charlie)

        val charlieId = charlie.endpointId!!
        charlie.leave()
        awaitPeerGone(alice, charlieId)
        awaitPeerGone(bob, charlieId)

        val aliceTombstone = alice.allPeers().firstOrNull { it.endpointId == charlieId }
        val bobTombstone = bob.allPeers().firstOrNull { it.endpointId == charlieId }
        assertNotNull("Charlie should be tombstoned in Alice's directory", aliceTombstone)
        assertNotNull("Charlie should be tombstoned in Bob's directory", bobTombstone)
        assertEquals(PeerStatus.DISCONNECTED, aliceTombstone!!.status)
        assertEquals(PeerStatus.DISCONNECTED, bobTombstone!!.status)
    }

    // -------------------------------------------------------------------------
    // Tests — directory message routing
    // -------------------------------------------------------------------------

    @Test
    fun directoryMessages_areConsumedByDirectoryManager_notForwardedToAppListeners() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        val appMessages = mutableListOf<Message>()
        alice.createNetwork()
        alice.client.addMessageListener { message -> appMessages.add(message) }

        bob.joinNetwork()
        awaitConvergence(alice, bob)
        delay(SETTLE_DELAY)

        assertTrue(
            "Directory messages leaked to app layer: ${appMessages.map { it.type }}",
            appMessages.none { it.type.name.startsWith("DIRECTORY_") }
        )
    }

    @Test
    fun textMessage_sentBetweenConnectedPeers_isDelivered() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        val received = mutableListOf<Message>()
        alice.createNetwork()
        bob.joinNetwork()
        awaitConvergence(alice, bob)

        alice.client.addMessageListener { received.add(it) }

        bob.client.sendMessage(
            Message(
                to = alice.endpointId!!,
                from = bob.endpointId!!,
                type = MessageType.TEXT_MESSAGE,
                ttl = 5
            )
        )
        delay(SETTLE_DELAY)

        assertTrue(received.any { it.type == MessageType.TEXT_MESSAGE })
    }

    // -------------------------------------------------------------------------
    // Tests — leave and rejoin
    // -------------------------------------------------------------------------

    @Test
    fun rejoin_afterLeaving_networkStillFunctions() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        alice.createNetwork()
        bob.joinNetwork()
        awaitConvergence(alice, bob)

        val bobOriginalId = bob.endpointId!!
        bob.leave()
        awaitPeerGone(alice, bobOriginalId)

        val bobRejoined = node("Bob")
        bobRejoined.joinNetwork()
        awaitPeerCount(alice, minPeerCount = 2)

        assertEquals(2, alice.activePeers().size)
    }

    // -------------------------------------------------------------------------
    // Tests — public API
    // -------------------------------------------------------------------------

    @Test
    fun networkPeers_returnsOnlyActivePeers() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")
        val charlie = node("Charlie")

        alice.createNetwork()
        bob.joinNetwork()
        charlie.joinNetwork()
        awaitConvergence(alice, bob, charlie)

        val charlieId = charlie.endpointId!!
        charlie.leave()
        awaitPeerGone(alice, charlieId)

        assertTrue(alice.client.networkPeers().none { it.status == PeerStatus.DISCONNECTED })
    }

    @Test
    fun fullDirectory_includesTombstones() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")

        alice.createNetwork()
        bob.joinNetwork()
        awaitConvergence(alice, bob)

        val bobId = bob.endpointId!!
        bob.leave()
        awaitPeerGone(alice, bobId)

        assertTrue(alice.client.fullDirectory().any { it.endpointId == bobId })
    }

    // -------------------------------------------------------------------------
    // Tests — larger network
    // -------------------------------------------------------------------------

    @Test
    fun fiveNodes_allJoin_allDirectoriesEventuallyConverge() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")
        val charlie = node("Charlie")
        val dave = node("Dave")
        val eve = node("Eve")

        alice.createNetwork()
        bob.joinNetwork()
        charlie.joinNetwork()
        dave.joinNetwork()
        eve.joinNetwork()

        awaitConvergence(alice, bob, charlie, dave, eve)

        val expected = setOf(
            alice.endpointId!!,
            bob.endpointId!!,
            charlie.endpointId!!,
            dave.endpointId!!,
            eve.endpointId!!
        )

        listOf(alice, bob, charlie, dave, eve).forEach { n ->
            assertEquals(
                "Directory for ${n.name} is inconsistent",
                expected,
                n.activePeers().map { it.endpointId }.toSet()
            )
        }
    }

    @Test
    fun fiveNodes_twoLeave_remainingThreeDirectoriesConverge() = runBlocking {
        val alice = node("Alice")
        val bob = node("Bob")
        val charlie = node("Charlie")
        val dave = node("Dave")
        val eve = node("Eve")

        alice.createNetwork()
        bob.joinNetwork()
        charlie.joinNetwork()
        dave.joinNetwork()
        eve.joinNetwork()

        awaitConvergence(alice, bob, charlie, dave, eve)

        val daveId = dave.endpointId!!
        val eveId = eve.endpointId!!

        dave.leave()
        eve.leave()

        awaitPeerGone(alice, daveId)
        awaitPeerGone(alice, eveId)
        awaitPeerGone(bob, daveId)
        awaitPeerGone(bob, eveId)
        awaitPeerGone(charlie, daveId)
        awaitPeerGone(charlie, eveId)

        val expected = setOf(alice.endpointId!!, bob.endpointId!!, charlie.endpointId!!)
        assertEquals(expected, alice.activePeers().map { it.endpointId }.toSet())
        assertEquals(expected, bob.activePeers().map { it.endpointId }.toSet())
        assertEquals(expected, charlie.activePeers().map { it.endpointId }.toSet())
    }
}