package edu.uwm.cs595.goup11.backend.network

import android.util.Log
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryPeerPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectorySyncPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryVerifyAckPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryVerifyPayload
import edu.uwm.cs595.goup11.backend.network.payloads.VerifyStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.security.MessageDigest
import java.util.UUID

class DirectoryManager(
    /** Returns the local endpoint ID. Used as a method since id is valid after network join */
    private val localEndpointId: () -> String,

    /** Callback that this class uses to send a message to a specific endpoint */
    private val send: (toEndpoint: String, message: Message) -> Unit,

    private val scope: CoroutineScope,

    /** Determines how often to send the DIRECTORY_VERIFY message */
    private val verifyIntervalMs: Long = 10_000L,

    private val initialClock: Long = 0L
) {
    private val logger = KotlinLogging.logger {}

    private fun log(msg: String)  = Log.w("CN", msg)
    private fun logError(msg: String) = Log.e("CN", msg)

    // -------------------------------------------------------------------------
    // Internal directory state
    // -------------------------------------------------------------------------

    /** The backing directory map. Key = endpointId, Value = PeerEntry. */
    private val directory = mutableMapOf<String, PeerEntry>()

    /** Monotonically increasing local clock used for conflict resolution during merges. */
    private var lamportClock: Long = initialClock

    /**
     * Peers we are directly connected to at the transport layer.
     * Sends only go to members of this set.
     */
    private val directNeighbors = mutableSetOf<String>()

    /**
     * Tracks when each peer was last verified via DIRECTORY_VERIFY.
     * Used to select the least-recently-verified peer for the next round.
     */
    private val lastVerifiedAt = mutableMapOf<String, Long>()

    private var verifyJob: Job? = null

    // -------------------------------------------------------------------------
    // Reactive state — observed by the frontend
    // -------------------------------------------------------------------------

    /**
     * Emits the full directory including DISCONNECTED tombstones on every change.
     */
    private val _allPeers = MutableStateFlow<List<PeerEntry>>(emptyList())
    val allPeers: StateFlow<List<PeerEntry>> = _allPeers.asStateFlow()

    /**
     * Emits only ACTIVE peers on every change.
     */
    private val _activePeers = MutableStateFlow<List<PeerEntry>>(emptyList())
    val activePeers: StateFlow<List<PeerEntry>> = _activePeers.asStateFlow()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Registers this node in the directory and starts the periodic verify loop.
     */
    fun start() {
        registerSelf()
        startVerifyLoop()
    }

    /** Adds the local peer to the directory as the first ACTIVE entry. */
    fun registerSelf() {
        val localId = localEndpointId()
        directory[localId] = PeerEntry(
            endpointId    = localId,
            displayName   = localId,
            joinTimestamp = System.currentTimeMillis(),
            lamportClock  = tickClock(),
            status        = PeerStatus.ACTIVE
        )
        notifyDirectoryChanged()
        logger.info { "DirectoryManager started for $localId" }
    }

    /** Launches the background coroutine that periodically sends DIRECTORY_VERIFY. */
    fun startVerifyLoop() {
        verifyJob = scope.launch {
            while (isActive) {
                delay(verifyIntervalMs)
                runVerifyRound()
            }
        }
    }

    /**
     * Cancels the verify loop and clears all directory state.
     */
    fun stop() {
        verifyJob?.cancel()
        verifyJob = null
        directory.clear()
        directNeighbors.clear()
        lastVerifiedAt.clear()
        lamportClock = 0L
        notifyDirectoryChanged()
        logger.info { "DirectoryManager Stopped" }
    }

    // -------------------------------------------------------------------------
    // Network event hooks (called by Client)
    // -------------------------------------------------------------------------

    /**
     * Called by Client when EndpointConnected fires.
     * Adds the peer as a direct neighbor and sends our full directory via DIRECTORY_SYNC.
     */
    fun onPeerConnected(endpointId: String, advertisedName: AdvertisedName) {
        directNeighbors.add(endpointId)
        log("onPeerConnected: $endpointId, (${advertisedName.displayName})")
        // Add a placeholder for the user
        val existing = directory[endpointId]
        if (existing == null || existing.status == PeerStatus.DISCONNECTED) {
            directory[endpointId] = PeerEntry(
                endpointId    = endpointId,
                displayName   = advertisedName.displayName,
                joinTimestamp = System.currentTimeMillis(),
                lamportClock  = 0L,
                status        = PeerStatus.ACTIVE
            )
            notifyDirectoryChanged()
        }

        sendSync(endpointId)
        logger.info { "Peer connected: $endpointId (${advertisedName.displayName})" }
    }

    /**
     * Called by Client when EndpointDisconnected fires.
     * Tombstones the peer and broadcasts DIRECTORY_PEER_DISCONNECTED to all remaining neighbors.
     */
    fun onPeerDisconnected(endpointId: String) {
        directNeighbors.remove(endpointId)

        val existing = directory[endpointId] ?: return

        val tombstone = existing.copy(
            status       = PeerStatus.DISCONNECTED,
            lamportClock = tickClock()
        )
        directory[endpointId] = tombstone
        notifyDirectoryChanged()

        broadcastToNeighbors(MessageType.DIRECTORY_PEER_DISCONNECTED, tombstone, exclude = null)

        logger.info { "Peer disconnected $endpointId" }
    }

    // -------------------------------------------------------------------------
    // Message handler
    // -------------------------------------------------------------------------

    /**
     * Routes an inbound directory message to the appropriate handler.
     * Returns true if consumed, false if not a DIRECTORY_* message type.
     */
    fun onMessage(message: Message): Boolean {
        // Sync Lamport clock with sender on every directory message received
        lamportClock = maxOf(lamportClock, message.data?.let { extractClock(it, message.type) } ?: 0L) + 1

        return when (message.type) {
            MessageType.DIRECTORY_SYNC              -> { handleSync(message);             true }
            MessageType.DIRECTORY_SYNC_ACK          -> { handleSyncAck(message);          true }
            MessageType.DIRECTORY_PEER_ADDED        -> { handlePeerAdded(message);        true }
            MessageType.DIRECTORY_PEER_DISCONNECTED -> { handlePeerDisconnected(message); true }
            MessageType.DIRECTORY_VERIFY            -> { handleVerify(message);           true }
            MessageType.DIRECTORY_VERIFY_ACK        -> { handleVerifyAck(message);        true }
            else                                    -> false
        }
    }

    // -------------------------------------------------------------------------
    // Private — message handlers
    // -------------------------------------------------------------------------

    /**
     * Handles DIRECTORY_SYNC.
     * Force-marks sender as ACTIVE with a high clock, merges their directory,
     * replies with DIRECTORY_SYNC_ACK, then broadcasts the sender's ACTIVE entry
     * to all other neighbors so they learn about the (re)join immediately.
     */
    private fun handleSync(message: Message) {
        val payload = message.decodePayload<DirectorySyncPayload>()

        // Sync clock to payload max before creating the ACTIVE entry so it beats everything
        val payloadMaxClock = payload.peers.maxOfOrNull { it.lamportClock } ?: 0L
        lamportClock = maxOf(lamportClock, payloadMaxClock)

        // Force-mark sender as definitively ACTIVE
        val existingEntry = directory[message.from]
        val activeEntry = PeerEntry(
            endpointId    = message.from,
            displayName   = existingEntry?.displayName ?: message.from,
            joinTimestamp = existingEntry?.joinTimestamp ?: System.currentTimeMillis(),
            lamportClock  = tickClock(),
            status        = PeerStatus.ACTIVE
        )
        directory[message.from] = activeEntry

        mergeEntries(payload.peers)
        notifyDirectoryChanged()

        // Reply with full directory including the high-clock ACTIVE entry for sender
        send(message.from, buildMessage(
            to      = message.from,
            type    = MessageType.DIRECTORY_SYNC_ACK,
            payload = DirectorySyncPayload(directory.values.toList()),
            replyTo = message.id
        ))

        // Broadcast the ACTIVE entry so all neighbors override any stale tombstone
        broadcastToNeighbors(
            type    = MessageType.DIRECTORY_PEER_ADDED,
            entry   = activeEntry,
            exclude = message.from
        )

        logger.debug { "Handled DIRECTORY_SYNC from ${message.from}, replied with ${directory.size} entries" }
    }

    /**
     * Handles DIRECTORY_SYNC_ACK.
     * Syncs clock, force-marks sender ACTIVE, merges, then broadcasts the diff.
     */
    private fun handleSyncAck(message: Message) {
        val payload = message.decodePayload<DirectorySyncPayload>()

        val payloadMaxClock = payload.peers.maxOfOrNull { it.lamportClock } ?: 0L
        lamportClock = maxOf(lamportClock, payloadMaxClock)

        val existingEntry = directory[message.from]
        directory[message.from] = PeerEntry(
            endpointId    = message.from,
            displayName   = existingEntry?.displayName ?: message.from,
            joinTimestamp = existingEntry?.joinTimestamp ?: System.currentTimeMillis(),
            lamportClock  = tickClock(),
            status        = PeerStatus.ACTIVE
        )

        val diff = mergeEntries(payload.peers)
        notifyDirectoryChanged()

        diff.forEach { entry ->
            broadcastToNeighbors(
                type    = MessageType.DIRECTORY_PEER_ADDED,
                entry   = entry,
                exclude = message.from
            )
        }

        logger.debug { "Handled DIRECTORY_SYNC_ACK from ${message.from}, ${diff.size} new/updated entries broadcast" }
    }

    /**
     * Handles DIRECTORY_PEER_ADDED.
     * Merges the single entry and propagates onward if it changed our state.
     */
    private fun handlePeerAdded(message: Message) {
        val payload = message.decodePayload<DirectoryPeerPayload>()
        val diff = mergeEntries(listOf(payload.peer))

        if (diff.isNotEmpty()) {
            notifyDirectoryChanged()
            broadcastToNeighbors(
                type    = MessageType.DIRECTORY_PEER_ADDED,
                entry   = payload.peer,
                exclude = message.from
            )
        }

        logger.debug { "Handled DIRECTORY_PEER_ADDED for ${payload.peer.endpointId} from ${message.from}" }
    }

    /**
     * Handles DIRECTORY_PEER_DISCONNECTED.
     * Applies the tombstone via Lamport clock resolution and propagates if our state changed.
     */
    private fun handlePeerDisconnected(message: Message) {
        val payload  = message.decodePayload<DirectoryPeerPayload>()
        val existing = directory[payload.peer.endpointId]

        logger.debug {
            "PEER_DISCONNECTED for ${payload.peer.endpointId}: " +
                    "incoming clock=${payload.peer.lamportClock}, " +
                    "existing clock=${existing?.lamportClock}, " +
                    "existing status=${existing?.status}"
        }

        val diff = mergeEntries(listOf(payload.peer))

        if (diff.isNotEmpty()) {
            notifyDirectoryChanged()
            broadcastToNeighbors(
                type    = MessageType.DIRECTORY_PEER_DISCONNECTED,
                entry   = payload.peer,
                exclude = message.from
            )
        } else {
            logger.debug { "Discarding stale DIRECTORY_PEER_DISCONNECTED for ${payload.peer.endpointId} — clock too low" }
        }
    }

    /**
     * Handles DIRECTORY_VERIFY.
     * Compares hashes and replies OK or MISMATCH + full directory.
     */
    private fun handleVerify(message: Message) {
        val payload = message.decodePayload<DirectoryVerifyPayload>()
        val ourHash = computeDirectoryHash()

        val ackPayload = if (payload.hash == ourHash) {
            logger.debug { "DIRECTORY_VERIFY from ${message.from}: hashes match" }
            DirectoryVerifyAckPayload(status = VerifyStatus.OK)
        } else {
            logger.debug { "DIRECTORY_VERIFY from ${message.from}: hash mismatch, sending full directory" }
            DirectoryVerifyAckPayload(
                status = VerifyStatus.MISMATCH,
                peers  = directory.values.toList()
            )
        }

        send(message.from, buildMessage(
            to      = message.from,
            type    = MessageType.DIRECTORY_VERIFY_ACK,
            payload = ackPayload,
            replyTo = message.id
        ))
    }

    /**
     * Handles DIRECTORY_VERIFY_ACK.
     * On OK, records the verify timestamp.
     * On MISMATCH, merges the received directory and broadcasts the diff.
     */
    private fun handleVerifyAck(message: Message) {
        val payload = message.decodePayload<DirectoryVerifyAckPayload>()

        when (payload.status) {
            VerifyStatus.OK -> {
                lastVerifiedAt[message.from] = System.currentTimeMillis()
                logger.debug { "DIRECTORY_VERIFY_ACK from ${message.from}: OK" }
            }
            VerifyStatus.MISMATCH -> {
                val diff = mergeEntries(payload.peers)
                lastVerifiedAt[message.from] = System.currentTimeMillis()

                if (diff.isNotEmpty()) {
                    notifyDirectoryChanged()
                    diff.forEach { entry ->
                        val type = if (entry.status == PeerStatus.ACTIVE)
                            MessageType.DIRECTORY_PEER_ADDED
                        else
                            MessageType.DIRECTORY_PEER_DISCONNECTED
                        broadcastToNeighbors(type = type, entry = entry, exclude = message.from)
                    }
                }

                logger.debug { "DIRECTORY_VERIFY_ACK from ${message.from}: MISMATCH, merged ${diff.size} entries and broadcast diff" }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private — directory operations
    // -------------------------------------------------------------------------

    /**
     * Merges incoming PeerEntries into the local directory using Lamport clock
     * conflict resolution. Returns entries that actually changed.
     */
    private fun mergeEntries(incoming: List<PeerEntry>): List<PeerEntry> {
        val changed = mutableListOf<PeerEntry>()
        val localId = localEndpointId()

        for (entry in incoming) {
            if (entry.endpointId == localId) continue

            val existing = directory[entry.endpointId]

            val winner = when {
                existing == null -> entry
                entry.lamportClock > existing.lamportClock -> entry
                entry.lamportClock < existing.lamportClock  -> existing
                entry.status == PeerStatus.ACTIVE && existing.status == PeerStatus.DISCONNECTED -> entry
                else -> existing
            }

            if (winner !== existing) {
                directory[entry.endpointId] = winner
                changed.add(winner)
            }
        }

        return changed
    }

    /**
     * Computes a stable SHA-256 hash of the current directory.
     * Entries are sorted lexicographically by endpointId before hashing so
     * identical directories always produce the same hash regardless of insertion order.
     */
    private fun computeDirectoryHash(): String {
        val sorted = directory.values
            .sortedBy { it.endpointId }
            .joinToString(separator = "|") { entry ->
                "${entry.endpointId}:${entry.lamportClock}:${entry.status.name}"
            }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(sorted.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Selects the peer least recently verified via DIRECTORY_VERIFY.
     * Returns null if there are no direct neighbors to verify against.
     */
    private fun selectVerifyTarget(): String? =
        directNeighbors.minByOrNull { lastVerifiedAt[it] ?: 0L }

    /** Increments and returns the local Lamport clock. */
    private fun tickClock(): Long = ++lamportClock

    /** Sends DIRECTORY_VERIFY to the least-recently-verified neighbor. */
    private fun runVerifyRound() {
        val target = selectVerifyTarget() ?: return
        send(target, buildMessage(
            to      = target,
            type    = MessageType.DIRECTORY_VERIFY,
            payload = DirectoryVerifyPayload(hash = computeDirectoryHash())
        ))
        logger.debug { "Sent DIRECTORY_VERIFY to $target" }
    }

    /** Sends our full directory to a specific endpoint via DIRECTORY_SYNC. */
    private fun sendSync(toEndpointId: String) {
        send(toEndpointId, buildMessage(
            to      = toEndpointId,
            type    = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(directory.values.toList())
        ))
    }

    /** Broadcasts a single PeerEntry to all direct neighbors, optionally excluding one. */
    private fun broadcastToNeighbors(type: MessageType, entry: PeerEntry, exclude: String?) {
        val payload = DirectoryPeerPayload(peer = entry)
        directNeighbors
            .filter { it != exclude }
            .forEach { neighborId ->
                send(neighborId, buildMessage(
                    to      = neighborId,
                    type    = type,
                    payload = payload
                ))
            }
    }

    /**
     * Extracts the highest Lamport clock value from an inbound message payload.
     * Used to sync the local clock before processing.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun extractClock(data: ByteArray, type: MessageType): Long {
        return try {
            when (type) {
                MessageType.DIRECTORY_SYNC,
                MessageType.DIRECTORY_SYNC_ACK -> {
                    val payload = ProtoBuf.decodeFromByteArray<DirectorySyncPayload>(data)
                    payload.peers.maxOfOrNull { it.lamportClock } ?: 0L
                }
                MessageType.DIRECTORY_VERIFY_ACK -> {
                    val payload = ProtoBuf.decodeFromByteArray<DirectoryVerifyAckPayload>(data)
                    payload.peers.maxOfOrNull { it.lamportClock } ?: 0L
                }
                MessageType.DIRECTORY_PEER_ADDED,
                MessageType.DIRECTORY_PEER_DISCONNECTED -> {
                    val payload = ProtoBuf.decodeFromByteArray<DirectoryPeerPayload>(data)
                    payload.peer.lamportClock
                }
                else -> 0L
            }
        } catch (e: Exception) {
            logger.warn { "Failed to extract clock from $type message: ${e.message}" }
            0L
        }
    }

    /** Builds an outbound Message with a serialized protobuf payload. */
    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T : Any> buildMessage(
        to:      String,
        type:    MessageType,
        payload: T,
        replyTo: String? = null
    ): Message = Message(
        to      = to,
        from    = localEndpointId(),
        type    = type,
        data    = ProtoBuf.encodeToByteArray(payload),
        ttl     = 1,
        replyTo = replyTo,
        id      = UUID.randomUUID().toString()
    )

    // -------------------------------------------------------------------------
    // Private — StateFlow emission
    // -------------------------------------------------------------------------

    /**
     * Emits a fresh snapshot to both StateFlows whenever the directory changes.
     * Must be called after every mutation to the [directory] map.
     */
    private fun notifyDirectoryChanged() {
        val snapshot       = directory.values.toList()
        log("directory changed: ${snapshot.map { "${it.displayName}=${it.status}" }}")
        _allPeers.value    = snapshot
        _activePeers.value = snapshot.filter { it.status == PeerStatus.ACTIVE }
    }

    // -------------------------------------------------------------------------
    // Public — snapshot accessors (for non-reactive / test callers)
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of the full directory including DISCONNECTED tombstones.
     * Prefer collecting [allPeers] for reactive UI.
     */
    fun allPeersSnapshot(): List<PeerEntry> = directory.values.toList()

    /**
     * Returns only ACTIVE peers.
     * Prefer collecting [activePeers] for reactive UI.
     */
    fun activePeersSnapshot(): List<PeerEntry> =
        directory.values.filter { it.status == PeerStatus.ACTIVE }
}