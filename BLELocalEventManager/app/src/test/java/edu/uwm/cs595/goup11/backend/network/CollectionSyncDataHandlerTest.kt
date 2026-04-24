package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.PresentationEntry
import edu.uwm.cs595.goup11.backend.network.PresentationStatus
import edu.uwm.cs595.goup11.backend.network.handlers.CollectionDataSyncHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Integration tests for [CollectionDataSyncHandler] using [PresentationEntry] as
 * the concrete type. Tests verify sync, add, update, remove, deduplication, flood
 * re-broadcast, and multi-node convergence behavior.
 *
 * Test structure mirrors [DirectoryManagerUnitTest] — single-threaded via
 * [UnconfinedTestDispatcher], messages routed via captured lambda lists.
 *
 * Naming convention: `method_condition_expectedResult`
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class CollectionDataSyncHandlerTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val LOCAL_ID = "local-endpoint"
    private val PEER_A   = "peer-a"
    private val PEER_B   = "peer-b"
    private val PEER_C   = "peer-c"
    private val IDENTIFIER = "presentations"

    private val unconfinedDispatcher = UnconfinedTestDispatcher()
    private val testScope            = TestScope(unconfinedDispatcher)

    /** All messages sent via [send] lambda, in order */
    private val sentMessages = mutableListOf<Pair<String, Message>>()

    /** All messages sent via [broadcast] lambda, in order */
    private val broadcastMessages = mutableListOf<Message>()

    private lateinit var handler: CollectionDataSyncHandler<PresentationEntry>

    @Before
    fun setUp() {
        sentMessages.clear()
        broadcastMessages.clear()
        handler = makeHandler(LOCAL_ID)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeHandler(
        localId: String,
        sent: MutableList<Pair<String, Message>> = sentMessages,
        broadcast: MutableList<Message> = broadcastMessages
    ) = CollectionDataSyncHandler(
        identifier      = IDENTIFIER,
        serializer      = PresentationEntry.serializer(),
        localEndpointId = { localId },
        send            = { to, msg -> sent.add(to to msg) },
        broadcast       = { msg -> broadcast.add(msg) },
        isSameItem      = { a, b -> a.id == b.id }
    )

    private fun presentation(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Presentation",
        startTime: Long = System.currentTimeMillis(),
        endTime: Long = System.currentTimeMillis() + 3_600_000,
        location: String = "Room 101",
        speakerName: String = "Alice",
        speakerEndpointId: String = PEER_A,
        status: PresentationStatus = PresentationStatus.ACTIVE
    ) = PresentationEntry(
        id                = id,
        name              = name,
        startTime         = startTime,
        endTime           = endTime,
        location          = location,
        speakerName       = speakerName,
        speakerEndpointId = speakerEndpointId,
        status            = status
    )

    /**
     * Builds a Message the same way [CollectionDataSyncHandler] does internally,
     * so we can simulate inbound messages from remote peers.
     */
    private fun buildSyncMessage(
        from: String,
        to: String = LOCAL_ID,
        type: MessageType,
        items: List<PresentationEntry>? = null,
        singleItem: PresentationEntry? = null,
        ttl: Int = 5,
        id: String = UUID.randomUUID().toString()
    ): Message {
        val envelope = CollectionDataSyncHandler.DataEnvelope(
            identifier = IDENTIFIER,
            items      = items,
            singleItem = singleItem
        )
        val serializer = CollectionDataSyncHandler.DataEnvelope.serializer(
            PresentationEntry.serializer()
        )
        return Message(
            to   = to,
            from = from,
            type = type,
            data = ProtoBuf.encodeToByteArray(serializer, envelope),
            ttl  = ttl,
            id   = id
        )
    }

    /** Builds a message with a mismatched identifier — should be ignored. */
    private fun buildWrongIdentifierMessage(
        from: String,
        type: MessageType,
        singleItem: PresentationEntry
    ): Message {
        val envelope = CollectionDataSyncHandler.DataEnvelope(
            identifier = "wrong-identifier",
            singleItem = singleItem
        )
        val serializer = CollectionDataSyncHandler.DataEnvelope.serializer(
            PresentationEntry.serializer()
        )
        return Message(
            to   = LOCAL_ID,
            from = from,
            type = type,
            data = ProtoBuf.encodeToByteArray(serializer, envelope),
            ttl  = 5,
            id   = UUID.randomUUID().toString()
        )
    }

    private fun currentData() = handler.data.value

    private fun lastSentTo(endpointId: String): Message? =
        sentMessages.lastOrNull { it.first == endpointId }?.second

    private fun allSentOfType(type: MessageType): List<Message> =
        sentMessages.filter { it.second.type == type }.map { it.second }

    private fun allBroadcastOfType(type: MessageType): List<Message> =
        broadcastMessages.filter { it.type == type }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun initialState_dataIsEmpty() {
        assertTrue(currentData().isEmpty())
    }

    // -------------------------------------------------------------------------
    // addItem()
    // -------------------------------------------------------------------------

    @Test
    fun addItem_addsToLocalCollection() {
        val p = presentation()
        handler.addItem(p)
        assertTrue(currentData().any { it.id == p.id })
    }

    @Test
    fun addItem_broadcastsDataUpdateAdd() {
        handler.addItem(presentation())
        assertEquals(1, allBroadcastOfType(MessageType.DATA_UPDATE_ADD).size)
    }

    @Test
    fun addItem_duplicateItem_doesNotAddAgain() {
        val p = presentation(id = "fixed-id")
        handler.addItem(p)
        handler.addItem(p.copy(name = "Updated Name"))
        assertEquals(1, currentData().size)
    }

    @Test
    fun addItem_duplicateItem_doesNotBroadcastAgain() {
        val p = presentation(id = "fixed-id")
        handler.addItem(p)
        broadcastMessages.clear()
        handler.addItem(p)
        assertTrue(allBroadcastOfType(MessageType.DATA_UPDATE_ADD).isEmpty())
    }

    @Test
    fun addItem_multipleDistinctItems_allAdded() {
        handler.addItem(presentation(id = "p1"))
        handler.addItem(presentation(id = "p2"))
        handler.addItem(presentation(id = "p3"))
        assertEquals(3, currentData().size)
    }

    // -------------------------------------------------------------------------
    // updateItem()
    // -------------------------------------------------------------------------

    @Test
    fun updateItem_updatesExistingItem() {
        val p = presentation(id = "fixed-id", name = "Original")
        handler.addItem(p)
        handler.updateItem(p.copy(name = "Updated"))
        assertEquals("Updated", currentData().first { it.id == "fixed-id" }.name)
    }

    @Test
    fun updateItem_broadcastsDataUpdateMod() {
        val p = presentation(id = "fixed-id")
        handler.addItem(p)
        broadcastMessages.clear()
        handler.updateItem(p.copy(name = "Updated"))
        assertEquals(1, allBroadcastOfType(MessageType.DATA_UPDATE_MOD).size)
    }

    @Test
    fun updateItem_itemNotFound_doesNothing() {
        handler.updateItem(presentation(id = "nonexistent"))
        assertTrue(currentData().isEmpty())
        assertTrue(broadcastMessages.isEmpty())
    }

    @Test
    fun updateItem_doesNotChangeSizeOfCollection() {
        val p = presentation(id = "fixed-id")
        handler.addItem(p)
        handler.updateItem(p.copy(name = "Updated"))
        assertEquals(1, currentData().size)
    }

    // -------------------------------------------------------------------------
    // removeItem()
    // -------------------------------------------------------------------------

    @Test
    fun removeItem_removesFromLocalCollection() {
        val p = presentation(id = "fixed-id")
        handler.addItem(p)
        handler.removeItem(p)
        assertTrue(currentData().none { it.id == "fixed-id" })
    }

    @Test
    fun removeItem_broadcastsDataUpdateRemove() {
        val p = presentation(id = "fixed-id")
        handler.addItem(p)
        broadcastMessages.clear()
        handler.removeItem(p)
        assertEquals(1, allBroadcastOfType(MessageType.DATA_UPDATE_REMOVE).size)
    }

    @Test
    fun removeItem_itemNotFound_doesNothing() {
        handler.removeItem(presentation(id = "nonexistent"))
        assertTrue(broadcastMessages.isEmpty())
    }

    @Test
    fun removeItem_leavesOtherItemsIntact() {
        handler.addItem(presentation(id = "p1"))
        handler.addItem(presentation(id = "p2"))
        handler.removeItem(presentation(id = "p1"))
        assertEquals(1, currentData().size)
        assertEquals("p2", currentData().first().id)
    }

    // -------------------------------------------------------------------------
    // clear()
    // -------------------------------------------------------------------------

    @Test
    fun clear_removesAllItems() {
        handler.addItem(presentation(id = "p1"))
        handler.addItem(presentation(id = "p2"))
        handler.clear()
        assertTrue(currentData().isEmpty())
    }

    @Test
    fun clear_doesNotBroadcast() {
        handler.addItem(presentation())
        broadcastMessages.clear()
        handler.clear()
        assertTrue(broadcastMessages.isEmpty())
    }

    @Test
    fun clear_allowsReAddAfterClear() {
        val p = presentation(id = "fixed-id")
        handler.addItem(p)
        handler.clear()
        handler.addItem(p)
        assertEquals(1, currentData().size)
    }

    // -------------------------------------------------------------------------
    // onPeerConnected() — DATA_SYNC send
    // -------------------------------------------------------------------------

    @Test
    fun onPeerConnected_sendsDataSync() {
        handler.onPeerConnected(PEER_A)
        val msg = lastSentTo(PEER_A)
        assertNotNull(msg)
        assertEquals(MessageType.DATA_SYNC, msg!!.type)
    }

    @Test
    fun onPeerConnected_syncContainsCurrentItems() {
        handler.addItem(presentation(id = "p1"))
        handler.addItem(presentation(id = "p2"))
        handler.onPeerConnected(PEER_A)

        val syncMsg = lastSentTo(PEER_A)!!
        // Route message back to a fresh handler to verify items
        val receiver = makeHandler(PEER_A)
        receiver.processMessage(syncMsg)
        assertEquals(2, receiver.data.value.size)
    }

    @Test
    fun onPeerConnected_emptyCollection_syncSentWithNoItems() {
        handler.onPeerConnected(PEER_A)
        val syncMsg = lastSentTo(PEER_A)!!

        val receiver = makeHandler(PEER_A)
        receiver.processMessage(syncMsg)
        assertTrue(receiver.data.value.isEmpty())
    }

    // -------------------------------------------------------------------------
    // processMessage() — DATA_SYNC (inbound)
    // -------------------------------------------------------------------------

    @Test
    fun processMessage_dataSync_mergesIncomingItems() {
        val p1 = presentation(id = "p1")
        val p2 = presentation(id = "p2")
        val msg = buildSyncMessage(
            from  = PEER_A,
            type  = MessageType.DATA_SYNC,
            items = listOf(p1, p2)
        )
        handler.processMessage(msg)
        assertEquals(2, currentData().size)
    }

    @Test
    fun processMessage_dataSync_repliesWithDataSyncAck() {
        val msg = buildSyncMessage(
            from  = PEER_A,
            type  = MessageType.DATA_SYNC,
            items = emptyList()
        )
        handler.processMessage(msg)
        val reply = lastSentTo(PEER_A)
        assertNotNull(reply)
        assertEquals(MessageType.DATA_SYNC_ACK, reply!!.type)
    }

    @Test
    fun processMessage_dataSync_ackContainsLocalItems() {
        handler.addItem(presentation(id = "local-p1"))
        val msg = buildSyncMessage(
            from  = PEER_A,
            type  = MessageType.DATA_SYNC,
            items = emptyList()
        )
        handler.processMessage(msg)

        val ack = lastSentTo(PEER_A)!!
        val receiver = makeHandler(PEER_A)
        receiver.processMessage(ack)
        assertTrue(receiver.data.value.any { it.id == "local-p1" })
    }

    @Test
    fun processMessage_dataSync_doesNotDuplicateExistingItems() {
        val p = presentation(id = "p1")
        handler.addItem(p)
        val msg = buildSyncMessage(
            from  = PEER_A,
            type  = MessageType.DATA_SYNC,
            items = listOf(p)
        )
        handler.processMessage(msg)
        assertEquals(1, currentData().size)
    }

    @Test
    fun processMessage_dataSync_returnsTrueWhenConsumed() {
        val msg = buildSyncMessage(
            from  = PEER_A,
            type  = MessageType.DATA_SYNC,
            items = emptyList()
        )
        assertTrue(handler.processMessage(msg))
    }

    // -------------------------------------------------------------------------
    // processMessage() — DATA_SYNC_ACK (inbound)
    // -------------------------------------------------------------------------

    @Test
    fun processMessage_dataSyncAck_mergesIncomingItems() {
        val p = presentation(id = "p1")
        val msg = buildSyncMessage(
            from  = PEER_A,
            type  = MessageType.DATA_SYNC_ACK,
            items = listOf(p)
        )
        handler.processMessage(msg)
        assertTrue(currentData().any { it.id == "p1" })
    }

    @Test
    fun processMessage_dataSyncAck_doesNotReply() {
        val msg = buildSyncMessage(
            from  = PEER_A,
            type  = MessageType.DATA_SYNC_ACK,
            items = emptyList()
        )
        handler.processMessage(msg)
        assertTrue(sentMessages.isEmpty())
    }

    // -------------------------------------------------------------------------
    // processMessage() — DATA_UPDATE_ADD (inbound)
    // -------------------------------------------------------------------------

    @Test
    fun processMessage_dataUpdateAdd_addsItem() {
        val p = presentation(id = "p1")
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = p,
            ttl        = 5
        )
        handler.processMessage(msg)
        assertTrue(currentData().any { it.id == "p1" })
    }

    @Test
    fun processMessage_dataUpdateAdd_rebroadcastsWhenTtlAboveOne() {
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = presentation(id = "p1"),
            ttl        = 3
        )
        handler.processMessage(msg)
        assertEquals(1, allBroadcastOfType(MessageType.DATA_UPDATE_ADD).size)
    }

    @Test
    fun processMessage_dataUpdateAdd_doesNotRebroadcastWhenTtlIsOne() {
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = presentation(id = "p1"),
            ttl        = 1
        )
        handler.processMessage(msg)
        assertTrue(allBroadcastOfType(MessageType.DATA_UPDATE_ADD).isEmpty())
    }

    @Test
    fun processMessage_dataUpdateAdd_duplicateItem_doesNotAdd() {
        val p = presentation(id = "p1")
        handler.addItem(p)
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = p,
            ttl        = 5
        )
        handler.processMessage(msg)
        assertEquals(1, currentData().size)
    }

    // -------------------------------------------------------------------------
    // processMessage() — DATA_UPDATE_MOD (inbound)
    // -------------------------------------------------------------------------

    @Test
    fun processMessage_dataUpdateMod_updatesExistingItem() {
        val p = presentation(id = "p1", name = "Original")
        handler.addItem(p)
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_MOD,
            singleItem = p.copy(name = "Updated"),
            ttl        = 5
        )
        handler.processMessage(msg)
        assertEquals("Updated", currentData().first { it.id == "p1" }.name)
    }

    @Test
    fun processMessage_dataUpdateMod_rebroadcastsWhenTtlAboveOne() {
        val p = presentation(id = "p1")
        handler.addItem(p)
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_MOD,
            singleItem = p.copy(name = "Updated"),
            ttl        = 3
        )
        handler.processMessage(msg)
        assertEquals(1, allBroadcastOfType(MessageType.DATA_UPDATE_MOD).size)
    }

    @Test
    fun processMessage_dataUpdateMod_itemNotFound_doesNothing() {
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_MOD,
            singleItem = presentation(id = "nonexistent"),
            ttl        = 5
        )
        handler.processMessage(msg)
        assertTrue(currentData().isEmpty())
        assertTrue(broadcastMessages.isEmpty())
    }

    // -------------------------------------------------------------------------
    // processMessage() — DATA_UPDATE_REMOVE (inbound)
    // -------------------------------------------------------------------------

    @Test
    fun processMessage_dataUpdateRemove_removesItem() {
        val p = presentation(id = "p1")
        handler.addItem(p)
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_REMOVE,
            singleItem = p,
            ttl        = 5
        )
        handler.processMessage(msg)
        assertTrue(currentData().none { it.id == "p1" })
    }

    @Test
    fun processMessage_dataUpdateRemove_rebroadcastsWhenTtlAboveOne() {
        val p = presentation(id = "p1")
        handler.addItem(p)
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_REMOVE,
            singleItem = p,
            ttl        = 3
        )
        handler.processMessage(msg)
        assertEquals(1, allBroadcastOfType(MessageType.DATA_UPDATE_REMOVE).size)
    }

    @Test
    fun processMessage_dataUpdateRemove_itemNotFound_doesNothing() {
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_REMOVE,
            singleItem = presentation(id = "nonexistent"),
            ttl        = 5
        )
        handler.processMessage(msg)
        assertTrue(broadcastMessages.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Identifier filtering
    // -------------------------------------------------------------------------

    @Test
    fun processMessage_wrongIdentifier_returnsFalse() {
        val msg = buildWrongIdentifierMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = presentation()
        )
        assertFalse(handler.processMessage(msg))
    }

    @Test
    fun processMessage_wrongIdentifier_doesNotModifyData() {
        val msg = buildWrongIdentifierMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = presentation()
        )
        handler.processMessage(msg)
        assertTrue(currentData().isEmpty())
    }

    @Test
    fun processMessage_unrelatedMessageType_returnsFalse() {
        val msg = Message(
            to   = LOCAL_ID,
            from = PEER_A,
            type = MessageType.TEXT_MESSAGE,
            data = "hello".toByteArray(),
            ttl  = 5
        )
        assertFalse(handler.processMessage(msg))
    }

    // -------------------------------------------------------------------------
    // Deduplication
    // -------------------------------------------------------------------------

    @Test
    fun processMessage_duplicateMessageId_processedOnlyOnce() {
        val fixedId = UUID.randomUUID().toString()
        val p = presentation(id = "p1")
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = p,
            ttl        = 5,
            id         = fixedId
        )
        handler.processMessage(msg)
        handler.processMessage(msg) // second call with same message id
        assertEquals(1, currentData().size)
    }

    @Test
    fun processMessage_duplicateMessageId_rebroadcastsOnlyOnce() {
        val fixedId = UUID.randomUUID().toString()
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = presentation(id = "p1"),
            ttl        = 5,
            id         = fixedId
        )
        handler.processMessage(msg)
        handler.processMessage(msg)
        assertEquals(1, allBroadcastOfType(MessageType.DATA_UPDATE_ADD).size)
    }

    @Test
    fun clear_resetsDeduplications_allowsReprocessingSameMessageId() {
        val fixedId = UUID.randomUUID().toString()
        val p = presentation(id = "p1")
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = p,
            ttl        = 5,
            id         = fixedId
        )
        handler.processMessage(msg)
        handler.clear()
        handler.processMessage(msg) // should be processed again after clear
        assertEquals(1, currentData().size)
    }

    // -------------------------------------------------------------------------
    // Multiple handler instances — identifier isolation
    // -------------------------------------------------------------------------

    @Test
    fun twoHandlers_differentIdentifiers_dontInterfere() {
        val pollsHandler = CollectionDataSyncHandler(
            identifier      = "polls",
            serializer      = PresentationEntry.serializer(),
            localEndpointId = { LOCAL_ID },
            send            = { _, _ -> },
            broadcast       = { },
            isSameItem      = { a, b -> a.id == b.id }
        )

        val p = presentation(id = "p1")
        val msg = buildSyncMessage(
            from       = PEER_A,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = p,
            ttl        = 5
        )

        // presentations handler should consume it
        assertTrue(handler.processMessage(msg))
        // polls handler should not
        assertFalse(pollsHandler.processMessage(msg))

        assertEquals(1, handler.data.value.size)
        assertEquals(0, pollsHandler.data.value.size)
    }

    // -------------------------------------------------------------------------
    // Multi-node integration — convergence
    // -------------------------------------------------------------------------

    @Test
    fun multiNode_addOnOneNode_propagatesToAllViaFlood() {
        // Three nodes: A, B, C — fully connected
        val sentA = mutableListOf<Pair<String, Message>>()
        val sentB = mutableListOf<Pair<String, Message>>()
        val sentC = mutableListOf<Pair<String, Message>>()
        val bcA   = mutableListOf<Message>()
        val bcB   = mutableListOf<Message>()
        val bcC   = mutableListOf<Message>()

        val handlerA = makeHandler(PEER_A, sentA, bcA)
        val handlerB = makeHandler(PEER_B, sentB, bcB)
        val handlerC = makeHandler(PEER_C, sentC, bcC)

        // Simulate B adding a presentation and broadcasting
        val p = presentation(id = "new-presentation")
        handlerB.addItem(p)

        // Route B's broadcast to A and C
        val broadcastMsg = bcB.last()
        handlerA.processMessage(broadcastMsg)
        handlerC.processMessage(broadcastMsg)

        assertTrue(handlerA.data.value.any { it.id == "new-presentation" })
        assertTrue(handlerC.data.value.any { it.id == "new-presentation" })
    }

    @Test
    fun multiNode_syncOnConnect_newNodeReceivesExistingItems() {
        // A has items, B connects and receives sync
        val sentA = mutableListOf<Pair<String, Message>>()
        val handlerA = makeHandler(PEER_A, sentA, mutableListOf())
        val handlerB = makeHandler(PEER_B)

        handlerA.addItem(presentation(id = "p1"))
        handlerA.addItem(presentation(id = "p2"))

        // A sends sync to B on connect
        handlerA.onPeerConnected(PEER_B)
        val syncMsg = sentA.last { it.first == PEER_B }.second

        // B processes the sync
        handlerB.processMessage(syncMsg)

        assertEquals(2, handlerB.data.value.size)
        assertTrue(handlerB.data.value.any { it.id == "p1" })
        assertTrue(handlerB.data.value.any { it.id == "p2" })
    }

    @Test
    fun multiNode_syncAck_mergesBackToOriginalSender() {
        val sentA = mutableListOf<Pair<String, Message>>()
        val sentB = mutableListOf<Pair<String, Message>>()

        val handlerA = makeHandler(PEER_A, sentA, mutableListOf())
        val handlerB = makeHandler(PEER_B, sentB, mutableListOf())

        // B has a presentation A doesn't know about
        handlerB.addItem(presentation(id = "b-only"))

        // A connects to B — sends DATA_SYNC
        handlerA.onPeerConnected(PEER_B)
        val syncFromA = sentA.last { it.first == PEER_B }.second

        // B processes sync and replies with DATA_SYNC_ACK containing its items
        handlerB.processMessage(syncFromA)
        val ackFromB = sentB.last { it.first == PEER_A }.second

        // A processes the ack
        handlerA.processMessage(ackFromB)

        assertTrue(handlerA.data.value.any { it.id == "b-only" })
    }

    @Test
    fun multiNode_removeOnOneNode_propagatesToOthersViaFlood() {
        val bcA = mutableListOf<Message>()
        val handlerA = makeHandler(PEER_A, mutableListOf(), bcA)
        val handlerB = makeHandler(PEER_B)

        val p = presentation(id = "to-remove")
        handlerA.addItem(p)
        handlerB.processMessage(bcA.last()) // B gets the add
        bcA.clear()

        // A removes — broadcasts remove
        handlerA.removeItem(p)
        handlerB.processMessage(bcA.last())

        assertTrue(handlerB.data.value.none { it.id == "to-remove" })
    }

    @Test
    fun multiNode_updateOnOneNode_propagatesToOthersViaFlood() {
        val bcA = mutableListOf<Message>()
        val handlerA = makeHandler(PEER_A, mutableListOf(), bcA)
        val handlerB = makeHandler(PEER_B)

        val p = presentation(id = "to-update", name = "Original")
        handlerA.addItem(p)
        handlerB.processMessage(bcA.last()) // B gets the add
        bcA.clear()

        handlerA.updateItem(p.copy(name = "Updated"))
        handlerB.processMessage(bcA.last())

        assertEquals("Updated", handlerB.data.value.first { it.id == "to-update" }.name)
    }

    @Test
    fun multiNode_floodDoesNotLoop_deduplicationPreventsInfiniteRebroadcast() {
        // Simulate message arriving at A twice (as would happen in a naive flood)
        val bcA = mutableListOf<Message>()
        val handlerA = makeHandler(PEER_A, mutableListOf(), bcA)

        val fixedId = UUID.randomUUID().toString()
        val msg = buildSyncMessage(
            from       = PEER_B,
            type       = MessageType.DATA_UPDATE_ADD,
            singleItem = presentation(id = "p1"),
            ttl        = 5,
            id         = fixedId
        )

        handlerA.processMessage(msg)
        handlerA.processMessage(msg) // simulates loop

        // Should only have broadcast once
        assertEquals(1, bcA.filter { it.type == MessageType.DATA_UPDATE_ADD }.size)
    }

    @Test
    fun multiNode_convergence_fiveNodes_allSeeAllPresentations() {
        val nodeIds = listOf("n1", "n2", "n3", "n4", "n5")
        val broadcasts = nodeIds.associateWith { mutableListOf<Message>() }
        val sends = nodeIds.associateWith { mutableListOf<Pair<String, Message>>() }

        val handlers = nodeIds.associateWith { id ->
            makeHandler(id, sends[id]!!, broadcasts[id]!!)
        }

        fun routeBroadcast(fromId: String) {
            val msgs = broadcasts[fromId]!!.toList()
            broadcasts[fromId]!!.clear()
            for (msg in msgs) {
                nodeIds.filter { it != fromId }.forEach { toId ->
                    handlers[toId]!!.processMessage(msg)
                }
            }
        }

        // n1 adds 3 presentations and floods
        handlers["n1"]!!.addItem(presentation(id = "p1"))
        routeBroadcast("n1")
        handlers["n1"]!!.addItem(presentation(id = "p2"))
        routeBroadcast("n1")
        handlers["n1"]!!.addItem(presentation(id = "p3"))
        routeBroadcast("n1")

        // Verify all nodes have all 3 presentations
        nodeIds.forEach { id ->
            val data = handlers[id]!!.data.value
            assertEquals("Node $id should have 3 presentations", 3, data.size)
            assertTrue(data.any { it.id == "p1" })
            assertTrue(data.any { it.id == "p2" })
            assertTrue(data.any { it.id == "p3" })
        }
    }
}