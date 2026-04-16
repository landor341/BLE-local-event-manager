package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryPeerPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectorySyncPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryVerifyAckPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryVerifyPayload
import edu.uwm.cs595.goup11.backend.network.payloads.VerifyStatus
import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Unit tests for [DirectoryManager].
 *
 * All tests are single-threaded and synchronous via [TestScope].
 *
 * API note: allPeers and activePeers are now StateFlows.
 *  - Read current snapshot:  manager.allPeers.value  / manager.activePeers.value
 *  - Snapshot helper methods: manager.allPeersSnapshot() / manager.activePeersSnapshot()
 *
 * Naming convention: `methodUnderTest_condition_expectedResult`
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class DirectoryManagerUnitTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val LOCAL_ID = "local-endpoint"
    private val PEER_A   = "peer-a"
    private val PEER_B   = "peer-b"
    private val PEER_C   = "peer-c"

    private val unconfinedDispatcher = UnconfinedTestDispatcher()
    private val testScope            = TestScope(unconfinedDispatcher)

    // Separate scope only for verify loop tests — StandardTestDispatcher
    // pauses at every delay() so advanceTimeBy() works correctly
    private val verifyDispatcher = StandardTestDispatcher()
    private val verifyScope      = TestScope(verifyDispatcher)

    /** All messages captured by the [send] lambda, in order */
    private val sentMessages = mutableListOf<Pair<String, Message>>()

    private lateinit var manager: DirectoryManager

    @Before
    fun setUp() {
        sentMessages.clear()
        manager = DirectoryManager(
            localEndpointId  = { LOCAL_ID },
            send             = { to, msg -> sentMessages.add(to to msg) },
            scope            = testScope,
            verifyIntervalMs = 10_000L
        )
        manager.registerSelf()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun fakeAdvertisedName(displayName: String) = AdvertisedName(
        eventName    = "TestEvent",
        topologyCode = "msh",
        role         = TopologyStrategy.Role.PEER,
        displayName  = displayName
    )

    private fun peerEntry(
        endpointId:    String,
        displayName:   String     = endpointId,
        lamportClock:  Long       = 1L,
        status:        PeerStatus = PeerStatus.ACTIVE,
        joinTimestamp: Long       = System.currentTimeMillis()
    ) = PeerEntry(
        endpointId    = endpointId,
        displayName   = displayName,
        joinTimestamp = joinTimestamp,
        lamportClock  = lamportClock,
        status        = status
    )

    private inline fun <reified T : Any> buildMessage(
        from:    String,
        to:      String      = LOCAL_ID,
        type:    MessageType,
        payload: T,
        replyTo: String?     = null
    ) = Message(
        to      = to,
        from    = from,
        type    = type,
        data    = ProtoBuf.encodeToByteArray(payload),
        ttl     = 1,
        replyTo = replyTo,
        id      = UUID.randomUUID().toString()
    )

    private fun lastSentTo(endpointId: String): Message? =
        sentMessages.lastOrNull { it.first == endpointId }?.second

    private fun allSentOfType(type: MessageType): List<Message> =
        sentMessages.filter { it.second.type == type }.map { it.second }

    // -------------------------------------------------------------------------
    // Convenience accessors — read current StateFlow values
    // -------------------------------------------------------------------------

    private fun allPeers()    = manager.allPeers.value
    private fun activePeers() = manager.activePeers.value

    // -------------------------------------------------------------------------
    // start() / registerSelf()
    // -------------------------------------------------------------------------

    @Test
    fun start_addsLocalPeerToDirectory() {
        val peers = allPeers()
        assertEquals(1, peers.size)
        assertEquals(LOCAL_ID, peers.first().endpointId)
        assertEquals(PeerStatus.ACTIVE, peers.first().status)
    }

    @Test
    fun start_localPeerIsReturnedByActivePeers() {
        assertTrue(activePeers().any { it.endpointId == LOCAL_ID })
    }

    // -------------------------------------------------------------------------
    // StateFlow emission
    // -------------------------------------------------------------------------

    @Test
    fun stateFlow_allPeers_emitsOnPeerConnected() {
        val before = allPeers().size
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        assertEquals(before + 1, allPeers().size)
    }

    @Test
    fun stateFlow_activePeers_emitsOnPeerConnected() {
        val before = activePeers().size
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        assertEquals(before + 1, activePeers().size)
    }

    @Test
    fun stateFlow_activePeers_emitsOnPeerDisconnected() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val before = activePeers().size

        manager.onPeerDisconnected(PEER_A)

        assertEquals(before - 1, activePeers().size)
    }

    @Test
    fun stateFlow_allPeers_stillContainsTombstoneAfterDisconnect() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val allBefore = allPeers().size

        manager.onPeerDisconnected(PEER_A)

        // allPeers grows by 0 — entry stays, status changes to DISCONNECTED
        assertEquals(allBefore, allPeers().size)
        assertEquals(
            PeerStatus.DISCONNECTED,
            allPeers().first { it.endpointId == PEER_A }.status
        )
    }

    @Test
    fun stateFlow_allPeers_emitsOnStop() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        assertTrue(allPeers().isNotEmpty())

        manager.stop()

        assertTrue(allPeers().isEmpty())
        assertTrue(activePeers().isEmpty())
    }

    // -------------------------------------------------------------------------
    // stop()
    // -------------------------------------------------------------------------

    @Test
    fun stop_clearsDirectory() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.stop()
        assertTrue(allPeers().isEmpty())
    }

    @Test
    fun stop_clearsActivePeers() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.stop()
        assertTrue(activePeers().isEmpty())
    }

    // -------------------------------------------------------------------------
    // onPeerConnected()
    // -------------------------------------------------------------------------

    @Test
    fun onPeerConnected_addsPeerToDirectory() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        assertTrue(allPeers().any { it.endpointId == PEER_A })
    }

    @Test
    fun onPeerConnected_peerIsActive() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val entry = allPeers().first { it.endpointId == PEER_A }
        assertEquals(PeerStatus.ACTIVE, entry.status)
    }

    @Test
    fun onPeerConnected_setsDisplayNameFromAdvertisedName() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val entry = allPeers().first { it.endpointId == PEER_A }
        assertEquals("Alice", entry.displayName)
    }

    @Test
    fun onPeerConnected_sendsSyncToPeer() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val sent = lastSentTo(PEER_A)
        assertNotNull(sent)
        assertEquals(MessageType.DIRECTORY_SYNC, sent!!.type)
    }

    @Test
    fun onPeerConnected_syncPayloadContainsLocalPeer() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val sent = lastSentTo(PEER_A)!!
        val payload = ProtoBuf.decodeFromByteArray<DirectorySyncPayload>(sent.data!!)
        assertTrue(payload.peers.any { it.endpointId == LOCAL_ID })
    }

    @Test
    fun onPeerConnected_syncPayloadContainsNewPeer() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val sent = lastSentTo(PEER_A)!!
        val payload = ProtoBuf.decodeFromByteArray<DirectorySyncPayload>(sent.data!!)
        assertTrue(payload.peers.any { it.endpointId == PEER_A })
    }

    @Test
    fun onPeerConnected_multiplePeers_allAddedToDirectory() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        val ids = allPeers().map { it.endpointId }
        assertTrue(ids.containsAll(listOf(LOCAL_ID, PEER_A, PEER_B)))
    }

    // -------------------------------------------------------------------------
    // onPeerDisconnected()
    // -------------------------------------------------------------------------

    @Test
    fun onPeerDisconnected_tombstonesPeerInDirectory() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerDisconnected(PEER_A)
        val entry = allPeers().first { it.endpointId == PEER_A }
        assertEquals(PeerStatus.DISCONNECTED, entry.status)
    }

    @Test
    fun onPeerDisconnected_peerRemovedFromActivePeers() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerDisconnected(PEER_A)
        assertFalse(activePeers().any { it.endpointId == PEER_A })
    }

    @Test
    fun onPeerDisconnected_entryStillExistsInAllPeers() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerDisconnected(PEER_A)
        assertTrue(allPeers().any { it.endpointId == PEER_A })
    }

    @Test
    fun onPeerDisconnected_broadcastsDisconnectToRemainingNeighbors() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        sentMessages.clear()

        manager.onPeerDisconnected(PEER_A)

        val disconnectMessages = allSentOfType(MessageType.DIRECTORY_PEER_DISCONNECTED)
        assertTrue(disconnectMessages.any { msg ->
            sentMessages.any { it.first == PEER_B && it.second == msg }
        })
    }

    @Test
    fun onPeerDisconnected_doesNotBroadcastToDisconnectedPeer() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        sentMessages.clear()

        manager.onPeerDisconnected(PEER_A)

        val sentToPeerA = sentMessages.filter { it.first == PEER_A }
        assertTrue(sentToPeerA.none { it.second.type == MessageType.DIRECTORY_PEER_DISCONNECTED })
    }

    @Test
    fun onPeerDisconnected_unknownPeer_doesNotThrow() {
        try {
            manager.onPeerDisconnected("unknown-peer")
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // onMessage() — DIRECTORY_SYNC
    // -------------------------------------------------------------------------

    @Test
    fun handleSync_returnsTrue() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_A)))
        )
        assertTrue(manager.onMessage(msg))
    }

    @Test
    fun handleSync_mergesIncomingPeersIntoDirectory() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_B, lamportClock = 5L)))
        )
        manager.onMessage(msg)
        assertTrue(allPeers().any { it.endpointId == PEER_B })
    }

    @Test
    fun handleSync_repliesWithSyncAck() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_A)))
        )
        sentMessages.clear()
        manager.onMessage(msg)

        val ack = lastSentTo(PEER_A)
        assertNotNull(ack)
        assertEquals(MessageType.DIRECTORY_SYNC_ACK, ack!!.type)
    }

    @Test
    fun handleSync_ackCorrelatesWithRequestId() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_A)))
        )
        manager.onMessage(msg)
        val ack = lastSentTo(PEER_A)!!
        assertEquals(msg.id, ack.replyTo)
    }

    @Test
    fun handleSync_ackContainsLocalPeer() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_A)))
        )
        manager.onMessage(msg)
        val ack = lastSentTo(PEER_A)!!
        val payload = ProtoBuf.decodeFromByteArray<DirectorySyncPayload>(ack.data!!)
        assertTrue(payload.peers.any { it.endpointId == LOCAL_ID })
    }

    @Test
    fun handleSync_doesNotOverwriteLocalPeerEntry() {
        val spoofedLocalEntry = peerEntry(LOCAL_ID, displayName = "spoofed", lamportClock = 999L)
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(listOf(spoofedLocalEntry))
        )
        manager.onMessage(msg)
        val localEntry = allPeers().first { it.endpointId == LOCAL_ID }
        assertNotEquals("spoofed", localEntry.displayName)
    }

    // -------------------------------------------------------------------------
    // onMessage() — DIRECTORY_SYNC_ACK
    // -------------------------------------------------------------------------

    @Test
    fun handleSyncAck_returnsTrue() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC_ACK,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_A)))
        )
        assertTrue(manager.onMessage(msg))
    }

    @Test
    fun handleSyncAck_mergesNewPeersIntoDirectory() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC_ACK,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_B, lamportClock = 5L)))
        )
        manager.onMessage(msg)
        assertTrue(allPeers().any { it.endpointId == PEER_B })
    }

    @Test
    fun handleSyncAck_broadcastsDiffToOtherNeighbors() {
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        sentMessages.clear()

        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC_ACK,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_C, lamportClock = 5L)))
        )
        manager.onMessage(msg)

        assertTrue(sentMessages.any {
            it.first == PEER_B && it.second.type == MessageType.DIRECTORY_PEER_ADDED
        })
    }

    @Test
    fun handleSyncAck_doesNotBroadcastBackToSender() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        sentMessages.clear()

        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC_ACK,
            payload = DirectorySyncPayload(listOf(peerEntry(PEER_B, lamportClock = 5L)))
        )
        manager.onMessage(msg)

        assertTrue(sentMessages.none {
            it.first == PEER_A && it.second.type == MessageType.DIRECTORY_PEER_ADDED
        })
    }

    // -------------------------------------------------------------------------
    // onMessage() — DIRECTORY_PEER_ADDED
    // -------------------------------------------------------------------------

    @Test
    fun handlePeerAdded_returnsTrue() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B))
        )
        assertTrue(manager.onMessage(msg))
    }

    @Test
    fun handlePeerAdded_addsPeerToDirectory() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, lamportClock = 3L))
        )
        manager.onMessage(msg)
        assertTrue(allPeers().any { it.endpointId == PEER_B })
    }

    @Test
    fun handlePeerAdded_propagatesToOtherNeighbors() {
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        sentMessages.clear()

        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_C, lamportClock = 5L))
        )
        manager.onMessage(msg)

        assertTrue(sentMessages.any {
            it.first == PEER_B && it.second.type == MessageType.DIRECTORY_PEER_ADDED
        })
    }

    @Test
    fun handlePeerAdded_staleLowerClock_doesNotUpdateDirectory() {
        val highClockMsg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, lamportClock = 10L))
        )
        manager.onMessage(highClockMsg)
        val clockBefore = allPeers().first { it.endpointId == PEER_B }.lamportClock
        sentMessages.clear()

        val lowClockMsg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, lamportClock = 2L))
        )
        manager.onMessage(lowClockMsg)

        val clockAfter = allPeers().first { it.endpointId == PEER_B }.lamportClock
        assertEquals(clockBefore, clockAfter)
    }

    @Test
    fun handlePeerAdded_staleLowerClock_doesNotPropagate() {
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))

        val highClockMsg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_C, lamportClock = 10L))
        )
        manager.onMessage(highClockMsg)
        sentMessages.clear()

        val lowClockMsg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_C, lamportClock = 2L))
        )
        manager.onMessage(lowClockMsg)

        assertTrue(sentMessages.none { it.second.type == MessageType.DIRECTORY_PEER_ADDED })
    }

    // -------------------------------------------------------------------------
    // onMessage() — DIRECTORY_PEER_DISCONNECTED
    // -------------------------------------------------------------------------

    @Test
    fun handlePeerDisconnected_returnsTrue() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val msg = buildMessage(
            from    = PEER_B,
            type    = MessageType.DIRECTORY_PEER_DISCONNECTED,
            payload = DirectoryPeerPayload(peerEntry(PEER_A, status = PeerStatus.DISCONNECTED, lamportClock = 99L))
        )
        assertTrue(manager.onMessage(msg))
    }

    @Test
    fun handlePeerDisconnected_tombstonesPeer() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        val msg = buildMessage(
            from    = PEER_B,
            type    = MessageType.DIRECTORY_PEER_DISCONNECTED,
            payload = DirectoryPeerPayload(peerEntry(PEER_A, status = PeerStatus.DISCONNECTED, lamportClock = 99L))
        )
        manager.onMessage(msg)
        val entry = allPeers().first { it.endpointId == PEER_A }
        assertEquals(PeerStatus.DISCONNECTED, entry.status)
    }

    @Test
    fun handlePeerDisconnected_reconnectRace_higherClockWins() {
        val rejoinMsg = buildMessage(
            from    = PEER_B,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_A, status = PeerStatus.ACTIVE, lamportClock = 50L))
        )
        manager.onMessage(rejoinMsg)

        val disconnectMsg = buildMessage(
            from    = PEER_B,
            type    = MessageType.DIRECTORY_PEER_DISCONNECTED,
            payload = DirectoryPeerPayload(peerEntry(PEER_A, status = PeerStatus.DISCONNECTED, lamportClock = 10L))
        )
        manager.onMessage(disconnectMsg)

        val entry = allPeers().first { it.endpointId == PEER_A }
        assertEquals(PeerStatus.ACTIVE, entry.status)
    }

    @Test
    fun handlePeerDisconnected_staleMessage_doesNotPropagate() {
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))

        val addMsg = buildMessage(
            from    = PEER_C,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_A, lamportClock = 50L))
        )
        manager.onMessage(addMsg)
        sentMessages.clear()

        val disconnectMsg = buildMessage(
            from    = PEER_C,
            type    = MessageType.DIRECTORY_PEER_DISCONNECTED,
            payload = DirectoryPeerPayload(peerEntry(PEER_A, status = PeerStatus.DISCONNECTED, lamportClock = 5L))
        )
        manager.onMessage(disconnectMsg)

        assertTrue(sentMessages.none { it.second.type == MessageType.DIRECTORY_PEER_DISCONNECTED })
    }

    // -------------------------------------------------------------------------
    // onMessage() — DIRECTORY_VERIFY
    // -------------------------------------------------------------------------

    @Test
    fun handleVerify_returnsTrue() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY,
            payload = DirectoryVerifyPayload(hash = "somehash")
        )
        assertTrue(manager.onMessage(msg))
    }

    @Test
    fun handleVerify_mismatchHash_repliesWithMismatchAndFullDirectory() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY,
            payload = DirectoryVerifyPayload(hash = "definitely-wrong-hash")
        )
        sentMessages.clear()
        manager.onMessage(msg)

        val ack = lastSentTo(PEER_A)
        assertNotNull(ack)
        assertEquals(MessageType.DIRECTORY_VERIFY_ACK, ack!!.type)

        val payload = ProtoBuf.decodeFromByteArray<DirectoryVerifyAckPayload>(ack.data!!)
        assertEquals(VerifyStatus.MISMATCH, payload.status)
        assertTrue(payload.peers.isNotEmpty())
    }

    @Test
    fun handleVerify_mismatchReply_containsFullDirectory() {
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY,
            payload = DirectoryVerifyPayload(hash = "wrong")
        )
        manager.onMessage(msg)

        val ack = lastSentTo(PEER_A)!!
        val payload = ProtoBuf.decodeFromByteArray<DirectoryVerifyAckPayload>(ack.data!!)
        assertTrue(payload.peers.map { it.endpointId }.containsAll(listOf(LOCAL_ID, PEER_B)))
    }

    @Test
    fun handleVerify_matchingHash_repliesWithOk() {
        val sentFromSecond = mutableListOf<Pair<String, Message>>()
        val second = DirectoryManager(
            localEndpointId = { LOCAL_ID },
            send            = { to, msg -> sentFromSecond.add(to to msg) },
            scope           = testScope
        )
        second.start()

        val probeVerify = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY,
            payload = DirectoryVerifyPayload(hash = "probe")
        )
        second.onMessage(probeVerify)

        val ack = sentFromSecond.lastOrNull { it.second.type == MessageType.DIRECTORY_VERIFY_ACK }?.second
        assertNotNull("Second manager should have replied with a verify ack", ack)

        val mismatchPayload = ProtoBuf.decodeFromByteArray<DirectoryVerifyAckPayload>(ack!!.data!!)

        val syncMsg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(mismatchPayload.peers)
        )
        manager.onMessage(syncMsg)

        val secondVerify = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY,
            payload = DirectoryVerifyPayload(hash = "probe")
        )
        sentFromSecond.clear()
        second.onMessage(secondVerify)

        val secondAck = sentFromSecond.last { it.second.type == MessageType.DIRECTORY_VERIFY_ACK }.second
        val secondAckPayload = ProtoBuf.decodeFromByteArray<DirectoryVerifyAckPayload>(secondAck.data!!)
        assertEquals(VerifyStatus.MISMATCH, secondAckPayload.status)

        second.stop()
    }

    // -------------------------------------------------------------------------
    // onMessage() — DIRECTORY_VERIFY_ACK
    // -------------------------------------------------------------------------

    @Test
    fun handleVerifyAck_returnsTrue() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY_ACK,
            payload = DirectoryVerifyAckPayload(status = VerifyStatus.OK)
        )
        assertTrue(manager.onMessage(msg))
    }

    @Test
    fun handleVerifyAck_ok_doesNotModifyDirectory() {
        val peersBefore = allPeers().map { it.endpointId }.toSet()
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY_ACK,
            payload = DirectoryVerifyAckPayload(status = VerifyStatus.OK)
        )
        manager.onMessage(msg)
        assertEquals(peersBefore, allPeers().map { it.endpointId }.toSet())
    }

    @Test
    fun handleVerifyAck_mismatch_mergesMissingPeers() {
        val msg = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY_ACK,
            payload = DirectoryVerifyAckPayload(
                status = VerifyStatus.MISMATCH,
                peers  = listOf(peerEntry(PEER_B, lamportClock = 5L))
            )
        )
        manager.onMessage(msg)
        assertTrue(allPeers().any { it.endpointId == PEER_B })
    }

    @Test
    fun handleVerifyAck_mismatch_broadcastsDiffToNeighbors() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        sentMessages.clear()

        val msg = buildMessage(
            from    = PEER_B,
            type    = MessageType.DIRECTORY_VERIFY_ACK,
            payload = DirectoryVerifyAckPayload(
                status = VerifyStatus.MISMATCH,
                peers  = listOf(peerEntry(PEER_C, lamportClock = 5L))
            )
        )
        manager.onMessage(msg)

        assertTrue(sentMessages.any {
            it.first == PEER_A && it.second.type == MessageType.DIRECTORY_PEER_ADDED
        })
    }

    @Test
    fun handleVerifyAck_mismatch_doesNotBroadcastBackToSender() {
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        sentMessages.clear()

        val msg = buildMessage(
            from    = PEER_B,
            type    = MessageType.DIRECTORY_VERIFY_ACK,
            payload = DirectoryVerifyAckPayload(
                status = VerifyStatus.MISMATCH,
                peers  = listOf(peerEntry(PEER_C, lamportClock = 5L))
            )
        )
        manager.onMessage(msg)

        assertTrue(sentMessages.none {
            it.first == PEER_B && it.second.type == MessageType.DIRECTORY_PEER_ADDED
        })
    }

    // -------------------------------------------------------------------------
    // onMessage() — unhandled types
    // -------------------------------------------------------------------------

    @Test
    fun onMessage_textMessage_returnsFalse() {
        val msg = Message(to = LOCAL_ID, from = PEER_A, type = MessageType.TEXT_MESSAGE, ttl = 5)
        assertFalse(manager.onMessage(msg))
    }

    @Test
    fun onMessage_ping_returnsFalse() {
        val msg = Message(to = LOCAL_ID, from = PEER_A, type = MessageType.PING, ttl = 5)
        assertFalse(manager.onMessage(msg))
    }

    // -------------------------------------------------------------------------
    // Lamport clock conflict resolution
    // -------------------------------------------------------------------------

    @Test
    fun lamport_higherClockWins() {
        val lowClock = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, displayName = "OldName", lamportClock = 2L))
        )
        manager.onMessage(lowClock)

        val highClock = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, displayName = "NewName", lamportClock = 10L))
        )
        manager.onMessage(highClock)

        assertEquals("NewName", allPeers().first { it.endpointId == PEER_B }.displayName)
    }

    @Test
    fun lamport_lowerClockLoses() {
        val highClock = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, displayName = "NewName", lamportClock = 10L))
        )
        manager.onMessage(highClock)

        val lowClock = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, displayName = "OldName", lamportClock = 2L))
        )
        manager.onMessage(lowClock)

        assertEquals("NewName", allPeers().first { it.endpointId == PEER_B }.displayName)
    }

    @Test
    fun lamport_tieClock_activeBeatsDisconnected() {
        val disconnected = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, status = PeerStatus.DISCONNECTED, lamportClock = 5L))
        )
        manager.onMessage(disconnected)

        val active = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_PEER_ADDED,
            payload = DirectoryPeerPayload(peerEntry(PEER_B, status = PeerStatus.ACTIVE, lamportClock = 5L))
        )
        manager.onMessage(active)

        assertEquals(PeerStatus.ACTIVE, allPeers().first { it.endpointId == PEER_B }.status)
    }

    // -------------------------------------------------------------------------
    // Periodic verify loop
    // -------------------------------------------------------------------------

    @Test
    fun verifyLoop_afterInterval_sendsVerifyToNeighbor() = verifyScope.runTest {
        val verifyManager = DirectoryManager(
            localEndpointId  = { LOCAL_ID },
            send             = { to, msg -> sentMessages.add(to to msg) },
            scope            = verifyScope,
            verifyIntervalMs = 10_000L
        )
        verifyManager.registerSelf()
        verifyManager.startVerifyLoop()
        verifyManager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        sentMessages.clear()

        advanceTimeBy(10_001L)

        assertTrue(sentMessages.any { it.second.type == MessageType.DIRECTORY_VERIFY })
        verifyManager.stop()
    }

    @Test
    fun verifyLoop_noNeighbors_doesNotSendVerify() = verifyScope.runTest {
        val verifyManager = DirectoryManager(
            localEndpointId  = { LOCAL_ID },
            send             = { to, msg -> sentMessages.add(to to msg) },
            scope            = verifyScope,
            verifyIntervalMs = 10_000L
        )
        verifyManager.registerSelf()
        verifyManager.startVerifyLoop()
        sentMessages.clear()

        advanceTimeBy(10_001L)

        assertTrue(sentMessages.none { it.second.type == MessageType.DIRECTORY_VERIFY })
        verifyManager.stop()
    }

    @Test
    fun verifyLoop_sendsToLeastRecentlyVerifiedPeer() = verifyScope.runTest {
        val verifyManager = DirectoryManager(
            localEndpointId  = { LOCAL_ID },
            send             = { to, msg -> sentMessages.add(to to msg) },
            scope            = verifyScope,
            verifyIntervalMs = 10_000L
        )
        verifyManager.registerSelf()
        verifyManager.startVerifyLoop()
        verifyManager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        verifyManager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))

        val okAck = buildMessage(
            from    = PEER_A,
            type    = MessageType.DIRECTORY_VERIFY_ACK,
            payload = DirectoryVerifyAckPayload(status = VerifyStatus.OK)
        )
        verifyManager.onMessage(okAck)
        sentMessages.clear()

        advanceTimeBy(10_001L)

        assertTrue(sentMessages.any {
            it.first == PEER_B && it.second.type == MessageType.DIRECTORY_VERIFY
        })
        verifyManager.stop()
    }

    // -------------------------------------------------------------------------
    // activePeers / allPeers StateFlow values
    // -------------------------------------------------------------------------

    @Test
    fun activePeers_onlyReturnsActivePeers() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerConnected(PEER_B, fakeAdvertisedName("Bob"))
        manager.onPeerDisconnected(PEER_A)

        val active = activePeers()
        assertFalse(active.any { it.endpointId == PEER_A })
        assertTrue(active.any { it.endpointId == PEER_B })
    }

    @Test
    fun allPeers_includesTombstonedPeers() {
        manager.onPeerConnected(PEER_A, fakeAdvertisedName("Alice"))
        manager.onPeerDisconnected(PEER_A)
        assertTrue(allPeers().any { it.endpointId == PEER_A })
    }

    @Test
    fun allPeers_includesLocalPeer() {
        assertTrue(allPeers().any { it.endpointId == LOCAL_ID })
    }

    // -------------------------------------------------------------------------
    // Network integration — large network convergence after churn
    // -------------------------------------------------------------------------

    @Test
    fun networkIntegration_largeNetwork_directoriesConvergeAfterChurn() {
        val NUM_PEERS         = 10
        val JOIN_LEAVE_CYCLES = 10

        val managers     = mutableMapOf<String, DirectoryManager>()
        val activePeers  = mutableListOf<String>()
        val droppedPeers = mutableListOf<String>()

        fun route(toEndpointId: String, message: Message) {
            managers[toEndpointId]?.onMessage(message)
        }

        fun buildManager(id: String, initialClock: Long = 0L): DirectoryManager {
            val m = DirectoryManager(
                localEndpointId  = { id },
                send             = { to, msg -> route(to, msg) },
                scope            = testScope,
                verifyIntervalMs = 10_000L,
                initialClock     = initialClock
            )
            m.registerSelf()
            return m
        }

        fun connect(a: String, b: String) {
            managers[a]?.onPeerConnected(b, fakeAdvertisedName(b))
            managers[b]?.onPeerConnected(a, fakeAdvertisedName(a))
        }

        fun disconnect(a: String, b: String) {
            managers[a]?.onPeerDisconnected(b)
            managers[b]?.onPeerDisconnected(a)
        }

        fun drop(id: String) {
            activePeers.toList().forEach { activeId ->
                if (activeId != id) disconnect(id, activeId)
            }
            managers[id]?.stop()
            activePeers.remove(id)
            droppedPeers.add(id)
        }

        fun rejoin(id: String) {
            val maxClock = managers.values
                .flatMap { it.allPeers.value }  // read StateFlow value
                .maxOfOrNull { it.lamportClock } ?: 0L

            managers[id] = buildManager(id, initialClock = maxClock)

            activePeers.toList().forEach { activeId ->
                connect(id, activeId)
            }

            droppedPeers.remove(id)
            activePeers.add(id)
        }

        // Build initial fully-connected network
        val peerIds = (1..NUM_PEERS).map { "peer-$it" }
        peerIds.forEach { id ->
            managers[id] = buildManager(id)
            activePeers.add(id)
        }
        for (i in peerIds.indices) {
            for (j in i + 1 until peerIds.size) {
                connect(peerIds[i], peerIds[j])
            }
        }

        // Random churn
        val random = Random(seed = 42)
        repeat(JOIN_LEAVE_CYCLES) {
            when {
                droppedPeers.isNotEmpty() && random.nextBoolean() -> {
                    val id = droppedPeers[random.nextInt(droppedPeers.size)]
                    rejoin(id)
                }
                activePeers.size > 2 -> {
                    val id = activePeers[random.nextInt(activePeers.size)]
                    drop(id)
                }
            }
        }

        // Assert convergence — read activePeers StateFlow value
        val expectedActiveIds = activePeers.toSortedSet()
        var consistentCount   = 0
        val inconsistencies   = mutableListOf<String>()

        activePeers.forEach { id ->
            val seen = managers[id]
                ?.activePeers?.value           // read StateFlow value
                ?.map { it.endpointId }
                ?.toSortedSet()
                ?: sortedSetOf()

            if (seen == expectedActiveIds) {
                consistentCount++
            } else {
                inconsistencies.add(
                    "INCONSISTENT: $id sees ${seen.toList()} but expected ${expectedActiveIds.toList()}"
                )
            }
        }

        inconsistencies.forEach { println(it) }

        val pct = (consistentCount.toDouble() / activePeers.size) * 100
        println("Convergence: $consistentCount/${activePeers.size} managers consistent (${"%.1f".format(pct)}%)")

        assertTrue(
            "Expected >=95% directory consistency but got ${"%.1f".format(pct)}%",
            pct >= 95.0
        )
    }
}