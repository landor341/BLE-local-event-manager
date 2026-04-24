package edu.uwm.cs595.goup11.backend.network.handlers

import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageHandler
import edu.uwm.cs595.goup11.backend.network.MessageType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.UUID

/**
 * A generic [MessageHandler] that syncs a collection of items of type [T] across
 * all nodes on the network using DATA_SYNC, DATA_SYNC_ACK, DATA_UPDATE_ADD,
 * DATA_UPDATE_MOD, and DATA_UPDATE_REMOVE message types.
 *
 * Multiple instances can be registered on a Client — each instance responds only
 * to messages whose [identifier] matches its own, so different data types (e.g.
 * presentations, polls, announcements) can coexist on the same pipeline without
 * conflict.
 *
 * Usage:
 * ```kotlin
 * val presentations = CollectionDataSyncHandler(
 *     identifier      = "presentations",
 *     serializer      = PresentationEntry.serializer(),
 *     localEndpointId = { client.endpointId ?: "" },
 *     send            = { to, msg -> client.sendMessage(msg) },
 *     broadcast       = { msg -> client.sendMessage(msg) },
 *     isSameItem      = { a, b -> a.id == b.id }
 * )
 * client.addMessageHandler(presentations)
 * ```
 *
 * @param identifier      Unique string scoping messages to this handler instance.
 *                        Must match across all nodes for the same data type.
 * @param serializer      ProtoBuf [KSerializer] for [T]. Use `T.serializer()` for
 *                        @Serializable classes.
 * @param localEndpointId Lambda returning the local encoded endpoint ID. Called
 *                        lazily — safe to pass before the client has joined.
 * @param send            Sends a [Message] to a specific endpoint ID.
 * @param broadcast       Sends a [Message] to all peers via topology flood.
 * @param isSameItem      Returns true if two items represent the same entity.
 *                        Used for deduplication and updates. Defaults to equals().
 *                        Override to compare by a stable ID field.
 */
@OptIn(ExperimentalSerializationApi::class)
class CollectionDataSyncHandler<T : Any>(
    val identifier: String,
    private val serializer: KSerializer<T>,
    private val localEndpointId: () -> String,
    private val send: (toEndpoint: String, message: Message) -> Unit,
    private val broadcast: (message: Message) -> Unit,
    private val isSameItem: (existing: T, incoming: T) -> Boolean = { a, b -> a == b }
) : MessageHandler {

    private val logger = KotlinLogging.logger {}

    // ── Serializers ───────────────────────────────────────────────────────────

    private val envelopeSerializer = DataEnvelope.serializer(serializer)
    private val identifierOnlySerializer = IdentifierOnly.serializer()

    // ── Reactive state ────────────────────────────────────────────────────────

    private val _data = MutableStateFlow<List<T>>(emptyList())

    /**
     * The current synced collection. Emits on every add, update, or remove.
     * Observe this from the frontend via BackendFacade → MeshGateway.
     */
    val data: StateFlow<List<T>> = _data.asStateFlow()

    // ── Deduplication ─────────────────────────────────────────────────────────

    /** Message IDs we have already processed — prevents re-broadcast loops. */
    private val seenMessageIds = mutableSetOf<String>()

    // ── MessageHandler implementation ─────────────────────────────────────────

    private val handledTypes = setOf(
        MessageType.DATA_SYNC,
        MessageType.DATA_SYNC_ACK,
        MessageType.DATA_UPDATE_ADD,
        MessageType.DATA_UPDATE_MOD,
        MessageType.DATA_UPDATE_REMOVE
    )

    override fun processMessage(message: Message): Boolean {
        if (message.type !in handledTypes) return false

        val data = message.data ?: return false

        // Peek at identifier first — cheap decode to check ownership
        val identifierOnly = try {
            ProtoBuf.decodeFromByteArray(identifierOnlySerializer, data)
        } catch (e: Exception) {
            logger.warn { "[$identifier] Failed to peek identifier from ${message.type}: ${e.message}" }
            return false
        }

        if (identifierOnly.identifier != identifier) return false

        // Deduplicate
        if (!seenMessageIds.add(message.id)) {
            logger.debug { "[$identifier] Dropping duplicate message ${message.id}" }
            android.util.Log.d("DataSyncHandler[$identifier]", "Dropping duplicate message ${message.id}")
            return true
        }

        logger.debug { "[$identifier] Processing ${message.type} from ${message.from} id=${message.id}" }
        android.util.Log.d("DataSyncHandler[$identifier]", "Processing ${message.type} from ${message.from}")

        // Full decode and dispatch
        try {
            val envelope = ProtoBuf.decodeFromByteArray(envelopeSerializer, data)
            when (message.type) {
                MessageType.DATA_SYNC          -> handleSync(message, envelope)
                MessageType.DATA_SYNC_ACK      -> handleSyncAck(envelope)
                MessageType.DATA_UPDATE_ADD    -> handleAdd(message, envelope)
                MessageType.DATA_UPDATE_MOD    -> handleMod(message, envelope)
                MessageType.DATA_UPDATE_REMOVE -> handleRemove(message, envelope)
                else                           -> Unit
            }
        } catch (e: Exception) {
            logger.warn { "[$identifier] Failed to decode message ${message.id}: ${e.message}" }
            android.util.Log.w("DataSyncHandler[$identifier]", "Failed to decode ${message.id}: ${e.message}")
        }

        return true
    }

    // ── Peer lifecycle hooks ──────────────────────────────────────────────────

    /**
     * Call from DefaultBackendFacade when a new peer connects.
     * Sends our full collection via DATA_SYNC so the new peer has current state.
     */
    fun onPeerConnected(toEndpoint: String) {
        val count = _data.value.size
        logger.info { "[$identifier] Sending DATA_SYNC to $toEndpoint ($count items)" }
        android.util.Log.d("DataSyncHandler[$identifier]", "onPeerConnected: sending DATA_SYNC to $toEndpoint ($count items)")

        send(toEndpoint, buildMessage(
            to         = toEndpoint,
            type       = MessageType.DATA_SYNC,
            items      = _data.value,
            singleItem = null
        ))
    }

    // ── Public mutation API ───────────────────────────────────────────────────

    /**
     * Adds [item] to the local collection and broadcasts DATA_UPDATE_ADD.
     * No-op if [isSameItem] matches an existing entry.
     */
    fun addItem(item: T) {
        if (_data.value.any { isSameItem(it, item) }) {
            logger.debug { "[$identifier] addItem: already exists, skipping" }
            android.util.Log.d("DataSyncHandler[$identifier]", "addItem: already exists, skipping")
            return
        }
        _data.value = _data.value + item
        logger.info { "[$identifier] addItem: added, collection size=${_data.value.size}" }
        android.util.Log.d("DataSyncHandler[$identifier]", "addItem: added, size=${_data.value.size}")

        broadcast(buildMessage(
            to         = "ALL",
            type       = MessageType.DATA_UPDATE_ADD,
            items      = null,
            singleItem = item
        ))
    }

    /**
     * Replaces an existing item matching [isSameItem] with [updated],
     * then broadcasts DATA_UPDATE_MOD.
     * No-op if no matching item exists.
     */
    fun updateItem(updated: T) {
        if (_data.value.none { isSameItem(it, updated) }) {
            logger.debug { "[$identifier] updateItem: not found, skipping" }
            android.util.Log.d("DataSyncHandler[$identifier]", "updateItem: not found, skipping")
            return
        }
        _data.value = _data.value.map { if (isSameItem(it, updated)) updated else it }
        logger.info { "[$identifier] updateItem: updated, collection size=${_data.value.size}" }
        android.util.Log.d("DataSyncHandler[$identifier]", "updateItem: updated")

        broadcast(buildMessage(
            to         = "ALL",
            type       = MessageType.DATA_UPDATE_MOD,
            items      = null,
            singleItem = updated
        ))
    }

    /**
     * Removes the item matching [isSameItem] from the local collection and
     * broadcasts DATA_UPDATE_REMOVE.
     * No-op if no matching item exists.
     */
    fun removeItem(item: T) {
        if (_data.value.none { isSameItem(it, item) }) {
            logger.debug { "[$identifier] removeItem: not found, skipping" }
            android.util.Log.d("DataSyncHandler[$identifier]", "removeItem: not found, skipping")
            return
        }
        _data.value = _data.value.filter { !isSameItem(it, item) }
        logger.info { "[$identifier] removeItem: removed, collection size=${_data.value.size}" }
        android.util.Log.d("DataSyncHandler[$identifier]", "removeItem: removed, size=${_data.value.size}")

        broadcast(buildMessage(
            to         = "ALL",
            type       = MessageType.DATA_UPDATE_REMOVE,
            items      = null,
            singleItem = item
        ))
    }

    /**
     * Clears all local state and resets seen message IDs.
     * Call this on session leave — does NOT broadcast.
     */
    fun clear() {
        val prevSize = _data.value.size
        _data.value = emptyList()
        seenMessageIds.clear()
        logger.info { "[$identifier] clear: removed $prevSize items" }
        android.util.Log.d("DataSyncHandler[$identifier]", "clear: removed $prevSize items")
    }

    // ── Private — message handlers ────────────────────────────────────────────

    private fun handleSync(message: Message, envelope: DataEnvelope<T>) {
        val items = envelope.items ?: emptyList()
        val added = mergeItems(items)

        logger.info { "[$identifier] handleSync from ${message.from}: merged ${added.size} new items, total=${_data.value.size}" }
        android.util.Log.d("DataSyncHandler[$identifier]", "handleSync from ${message.from}: +${added.size} items, total=${_data.value.size}")

        send(message.from, buildMessage(
            to         = message.from,
            type       = MessageType.DATA_SYNC_ACK,
            items      = _data.value,
            singleItem = null,
            replyTo    = message.id
        ))
    }

    private fun handleSyncAck(envelope: DataEnvelope<T>) {
        val items = envelope.items ?: emptyList()
        val added = mergeItems(items)
        logger.info { "[$identifier] handleSyncAck: merged ${added.size} new items, total=${_data.value.size}" }
        android.util.Log.d("DataSyncHandler[$identifier]", "handleSyncAck: +${added.size} items, total=${_data.value.size}")
    }

    private fun handleAdd(message: Message, envelope: DataEnvelope<T>) {
        val item = envelope.singleItem ?: run {
            logger.warn { "[$identifier] handleAdd: no singleItem in envelope" }
            android.util.Log.w("DataSyncHandler[$identifier]", "handleAdd: no singleItem in envelope")
            return
        }

        if (_data.value.none { isSameItem(it, item) }) {
            _data.value = _data.value + item
            logger.info { "[$identifier] handleAdd: added item from ${message.from}, total=${_data.value.size}" }
            android.util.Log.d("DataSyncHandler[$identifier]", "handleAdd: added from ${message.from}, total=${_data.value.size}")

            if (message.ttl > 1) {
                logger.debug { "[$identifier] handleAdd: re-broadcasting with ttl=${message.ttl - 1}" }
                broadcast(buildMessage(
                    to         = "ALL",
                    type       = MessageType.DATA_UPDATE_ADD,
                    items      = null,
                    singleItem = item,
                    ttl        = message.ttl - 1
                ))
            }
        } else {
            logger.debug { "[$identifier] handleAdd: already exists, skipping" }
            android.util.Log.d("DataSyncHandler[$identifier]", "handleAdd: already exists, skipping")
        }
    }

    private fun handleMod(message: Message, envelope: DataEnvelope<T>) {
        val updated = envelope.singleItem ?: run {
            logger.warn { "[$identifier] handleMod: no singleItem in envelope" }
            android.util.Log.w("DataSyncHandler[$identifier]", "handleMod: no singleItem in envelope")
            return
        }

        if (_data.value.any { isSameItem(it, updated) }) {
            _data.value = _data.value.map { if (isSameItem(it, updated)) updated else it }
            logger.info { "[$identifier] handleMod: updated item from ${message.from}" }
            android.util.Log.d("DataSyncHandler[$identifier]", "handleMod: updated from ${message.from}")

            if (message.ttl > 1) {
                broadcast(buildMessage(
                    to         = "ALL",
                    type       = MessageType.DATA_UPDATE_MOD,
                    items      = null,
                    singleItem = updated,
                    ttl        = message.ttl - 1
                ))
            }
        } else {
            logger.debug { "[$identifier] handleMod: item not found, skipping" }
            android.util.Log.d("DataSyncHandler[$identifier]", "handleMod: not found, skipping")
        }
    }

    private fun handleRemove(message: Message, envelope: DataEnvelope<T>) {
        val item = envelope.singleItem ?: run {
            logger.warn { "[$identifier] handleRemove: no singleItem in envelope" }
            android.util.Log.w("DataSyncHandler[$identifier]", "handleRemove: no singleItem in envelope")
            return
        }

        if (_data.value.any { isSameItem(it, item) }) {
            _data.value = _data.value.filter { !isSameItem(it, item) }
            logger.info { "[$identifier] handleRemove: removed item from ${message.from}, total=${_data.value.size}" }
            android.util.Log.d("DataSyncHandler[$identifier]", "handleRemove: removed from ${message.from}, total=${_data.value.size}")

            if (message.ttl > 1) {
                broadcast(buildMessage(
                    to         = "ALL",
                    type       = MessageType.DATA_UPDATE_REMOVE,
                    items      = null,
                    singleItem = item,
                    ttl        = message.ttl - 1
                ))
            }
        } else {
            logger.debug { "[$identifier] handleRemove: item not found, skipping" }
            android.util.Log.d("DataSyncHandler[$identifier]", "handleRemove: not found, skipping")
        }
    }

    // ── Private — collection helpers ──────────────────────────────────────────

    private fun mergeItems(incoming: List<T>): List<T> {
        val current = _data.value.toMutableList()
        val added   = mutableListOf<T>()

        for (item in incoming) {
            if (current.none { isSameItem(it, item) }) {
                current.add(item)
                added.add(item)
            }
        }

        if (added.isNotEmpty()) {
            _data.value = current
        }

        return added
    }

    // ── Private — message construction ────────────────────────────────────────

    private fun buildMessage(
        to: String,
        type: MessageType,
        items: List<T>?,
        singleItem: T?,
        replyTo: String? = null,
        ttl: Int = 5
    ): Message {
        val envelope = DataEnvelope(
            identifier = identifier,
            items      = items,
            singleItem = singleItem
        )
        val encoded = ProtoBuf.encodeToByteArray(envelopeSerializer, envelope)
        return Message(
            to      = to,
            from    = localEndpointId(),
            type    = type,
            data    = encoded,
            ttl     = ttl,
            replyTo = replyTo,
            id      = UUID.randomUUID().toString()
        )
    }

    // ── Serializable envelope types ───────────────────────────────────────────

    /**
     * Unified envelope for all DATA_* message types.
     * - DATA_SYNC / DATA_SYNC_ACK:      [items] populated, [singleItem] null.
     * - DATA_UPDATE_ADD/MOD/REMOVE:     [singleItem] populated, [items] null.
     */
    @Serializable
    data class DataEnvelope<T>(
        val identifier: String,
        val items: List<T>? = null,
        val singleItem: T? = null
    )

    /**
     * Minimal decode target used to check [identifier] before full deserialization.
     */
    @Serializable
    private data class IdentifierOnly(val identifier: String)
}