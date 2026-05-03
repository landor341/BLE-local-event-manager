package edu.uwm.cs595.goup11.backend.network

import android.util.Log
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryPeerPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectorySyncPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryVerifyAckPayload
import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryVerifyPayload
import edu.uwm.cs595.goup11.backend.network.payloads.VerifyStatus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mutex = Mutex()

    private fun logDebug(msg: String) = Log.d("DirectoryManager", msg)
    private fun logWarn(msg: String) = Log.w("DirectoryManager", msg)

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
    suspend fun start() {
        mutex.withLock {
            registerSelf()
        }
        startVerifyLoop()
    }

    /** Adds the local peer to the directory as the first ACTIVE entry. Must be called under [mutex]. */
    private fun registerSelf() {
        val localId = localEndpointId()
        directory[localId] = PeerEntry(
            endpointId = localId,
            displayName = localId,
            joinTimestamp = System.currentTimeMillis(),
            lamportClock = tickClock(),
            status = PeerStatus.ACTIVE
        )
        notifyDirectoryChanged()
        logDebug("DirectoryManager started for $localId")
    }

    /** Launches the background coroutine that periodically sends DIRECTORY_VERIFY. */
    private fun startVerifyLoop() {
        verifyJob = scope.launch {
            while (isActive) {
                delay(verifyIntervalMs)
                mutex.withLock { runVerifyRound() }
            }
        }
    }

    /**
     * Cancels the verify loop and clears all directory state.
     */
    suspend fun stop() {
        verifyJob?.cancel()
        verifyJob = null
        mutex.withLock {
            directory.clear()
            directNeighbors.clear()
            lastVerifiedAt.clear()
            lamportClock = 0L
            notifyDirectoryChanged()
        }
        logDebug("DirectoryManager stopped")
    }

    // -------------------------------------------------------------------------
    // Network event hooks (called by Client)
    // -------------------------------------------------------------------------

    /**
     * Called by Client when EndpointConnected fires.
     * Adds the peer as a direct neighbor and sends our full directory via DIRECTORY_SYNC.
     */
    suspend fun onPeerConnected(endpointId: String, advertisedName: AdvertisedName) {
        mutex.withLock {
            directNeighbors.add(endpointId)
            logDebug("onPeerConnected: $endpointId (${advertisedName.displayName})")
        }
        sendSync(endpointId)
        logDebug("peer connected: $endpointId (${advertisedName.displayName})")
    }

    /**
     * Called by Client when EndpointDisconnected fires.
     * Removes the peer from the directory and broadcasts DIRECTORY_PEER_DISCONNECTED to neighbors.
     */
    suspend fun onPeerDisconnected(endpointId: String) {
        val removed = mutex.withLock {
            directNeighbors.remove(endpointId)
            val existing = directory[endpointId] ?: return
            // Guard against double-fire
            if (existing.status == PeerStatus.DISCONNECTED) return
            directory.remove(endpointId)
            notifyDirectoryChanged()
            existing
        }
        broadcastToNeighbors(MessageType.DIRECTORY_PEER_DISCONNECTED, removed, exclude = endpointId)
        logDebug("peer disconnected: $endpointId")
    }

    // -------------------------------------------------------------------------
    // Message handler
    // -------------------------------------------------------------------------

    /**
     * Routes an inbound directory message to the appropriate handler.
     * Returns true if consumed, false if not a DIRECTORY_* message type.
     */
    suspend fun onMessage(message: Message): Boolean {
        mutex.withLock {
            // Sync Lamport clock with sender on every directory message received
            lamportClock =
                maxOf(lamportClock, message.data?.let { extractClock(it, message.type) } ?: 0L) + 1
        }

        return when (message.type) {
            MessageType.DIRECTORY_SYNC -> { handleSync(message); true }
            MessageType.DIRECTORY_SYNC_ACK -> { handleSyncAck(message); true }
            MessageType.DIRECTORY_PEER_ADDED -> { handlePeerAdded(message); true }
            MessageType.DIRECTORY_PEER_DISCONNECTED -> { handlePeerDisconnected(message); true }
            MessageType.DIRECTORY_VERIFY -> { handleVerify(message); true }
            MessageType.DIRECTORY_VERIFY_ACK -> { handleVerifyAck(message); true }
            else -> false
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
    private suspend fun handleSync(message: Message) {
        val payload = message.decodePayload<DirectorySyncPayload>()

        try { localEndpointId() } catch (e: IllegalStateException) {
            logWarn("ignoring DIRECTORY_SYNC before local identity is established")
            return
        }

        val (activeEntry, snapshot) = mutex.withLock {
            val payloadMaxClock = payload.peers.maxOfOrNull { it.lamportClock } ?: 0L
            // Also factor in any existing tombstone clock so the ACTIVE entry we broadcast
            // is guaranteed to beat it on every node that receives the DIRECTORY_PEER_ADDED.
            val existingClock = directory[message.from]?.lamportClock ?: 0L
            lamportClock = maxOf(lamportClock, payloadMaxClock, existingClock)

            val existingEntry = directory[message.from]
            val resolvedDisplayName = existingEntry?.displayName
                ?: AdvertisedName.decode(message.from)?.displayName
                ?: message.from
            val entry = PeerEntry(
                endpointId = message.from,
                displayName = resolvedDisplayName,
                joinTimestamp = existingEntry?.joinTimestamp ?: System.currentTimeMillis(),
                lamportClock = tickClock(),
                status = PeerStatus.ACTIVE
            )
            directory[message.from] = entry
            mergeEntries(payload.peers)
            notifyDirectoryChanged()
            entry to directory.values.filter { it.status == PeerStatus.ACTIVE }
        }

        send(
            message.from, buildMessage(
                to = message.from,
                type = MessageType.DIRECTORY_SYNC_ACK,
                payload = DirectorySyncPayload(snapshot),
                replyTo = message.id
            ) ?: return
        )
        broadcastToNeighbors(MessageType.DIRECTORY_PEER_ADDED, activeEntry, exclude = message.from)
        logDebug("handled DIRECTORY_SYNC from ${message.from}, replied with ${snapshot.size} entries")
    }

    /**
     * Handles DIRECTORY_SYNC_ACK.
     * Syncs clock, force-marks sender ACTIVE, merges, then broadcasts the diff.
     */
    private suspend fun handleSyncAck(message: Message) {
        val payload = message.decodePayload<DirectorySyncPayload>()

        try { localEndpointId() } catch (e: IllegalStateException) {
            logWarn("ignoring DIRECTORY_SYNC_ACK before local identity is established")
            return
        }

        val diff = mutex.withLock {
            val payloadMaxClock = payload.peers.maxOfOrNull { it.lamportClock } ?: 0L
            lamportClock = maxOf(lamportClock, payloadMaxClock)

            val existingEntry = directory[message.from]
            val resolvedDisplayName = existingEntry?.displayName
                ?: AdvertisedName.decode(message.from)?.displayName
                ?: message.from
            directory[message.from] = PeerEntry(
                endpointId = message.from,
                displayName = resolvedDisplayName,
                joinTimestamp = existingEntry?.joinTimestamp ?: System.currentTimeMillis(),
                lamportClock = tickClock(),
                status = PeerStatus.ACTIVE
            )
            val d = mergeEntries(payload.peers)
            notifyDirectoryChanged()
            d
        }

        diff.forEach { entry ->
            broadcastToNeighbors(MessageType.DIRECTORY_PEER_ADDED, entry, exclude = message.from)
        }
        logDebug("handled DIRECTORY_SYNC_ACK from ${message.from}, ${diff.size} new/updated entries broadcast")
    }

    /**
     * Handles DIRECTORY_PEER_ADDED.
     * Merges the single entry and propagates onward if it changed our state.
     */
    private suspend fun handlePeerAdded(message: Message) {
        val payload = message.decodePayload<DirectoryPeerPayload>()
        val diff = mutex.withLock { mergeEntries(listOf(payload.peer)) }

        if (diff.isNotEmpty()) {
            notifyDirectoryChanged()
            broadcastToNeighbors(MessageType.DIRECTORY_PEER_ADDED, payload.peer, exclude = message.from)
        }
        logDebug("handled DIRECTORY_PEER_ADDED for ${payload.peer.endpointId} from ${message.from}")
    }

    /**
     * Handles DIRECTORY_PEER_DISCONNECTED.
     * Removes the peer from the directory and propagates if our state changed.
     */
    private suspend fun handlePeerDisconnected(message: Message) {
        val payload = message.decodePayload<DirectoryPeerPayload>()
        val removed = mutex.withLock {
            if (directory.containsKey(payload.peer.endpointId)) {
                directory.remove(payload.peer.endpointId)
                notifyDirectoryChanged()
                true
            } else false
        }
        if (removed) {
            broadcastToNeighbors(MessageType.DIRECTORY_PEER_DISCONNECTED, payload.peer, exclude = message.from)
        } else {
            logDebug("ignoring DIRECTORY_PEER_DISCONNECTED for ${payload.peer.endpointId} — already removed")
        }
    }

    /**
     * Handles DIRECTORY_VERIFY.
     * Compares hashes and replies OK or MISMATCH + full directory.
     */
    private suspend fun handleVerify(message: Message) {
        val payload = message.decodePayload<DirectoryVerifyPayload>()
        val (ourHash, snapshot) = mutex.withLock {
            computeDirectoryHash() to directory.values.toList()
        }

        val ackPayload = if (payload.hash == ourHash) {
            logDebug("DIRECTORY_VERIFY from ${message.from}: hashes match")
            DirectoryVerifyAckPayload(status = VerifyStatus.OK)
        } else {
            logDebug("DIRECTORY_VERIFY from ${message.from}: hash mismatch, sending full directory")
            DirectoryVerifyAckPayload(status = VerifyStatus.MISMATCH, peers = snapshot)
        }

        send(
            message.from, buildMessage(
                to = message.from,
                type = MessageType.DIRECTORY_VERIFY_ACK,
                payload = ackPayload,
                replyTo = message.id
            ) ?: return
        )
    }

    /**
     * Handles DIRECTORY_VERIFY_ACK.
     * On OK, records the verify timestamp.
     * On MISMATCH, merges the received directory and broadcasts the diff.
     */
    private suspend fun handleVerifyAck(message: Message) {
        val payload = message.decodePayload<DirectoryVerifyAckPayload>()

        when (payload.status) {
            VerifyStatus.OK -> {
                mutex.withLock { lastVerifiedAt[message.from] = System.currentTimeMillis() }
                logDebug("DIRECTORY_VERIFY_ACK from ${message.from}: OK")
            }
            VerifyStatus.MISMATCH -> {
                val (added, removedEntries) = mutex.withLock {
                    val localId = try { localEndpointId() } catch (_: IllegalStateException) { null }
                    val incomingIds = payload.peers.map { it.endpointId }.toSet()

                    // Remove entries we have that the sender doesn't — they've been removed
                    // from the network and the PEER_DISCONNECTED message was dropped.
                    val stale = directory.entries
                        .filter { (id, _) -> id != localId && id !in incomingIds }
                        .map { it.value }
                    stale.forEach { directory.remove(it.endpointId) }

                    val d = mergeEntries(payload.peers)
                    lastVerifiedAt[message.from] = System.currentTimeMillis()
                    if (stale.isNotEmpty() || d.isNotEmpty()) notifyDirectoryChanged()
                    d to stale
                }
                if (added.isNotEmpty() || removedEntries.isNotEmpty()) {
                    added.forEach { entry ->
                        broadcastToNeighbors(MessageType.DIRECTORY_PEER_ADDED, entry, exclude = message.from)
                    }
                    removedEntries.forEach { entry ->
                        broadcastToNeighbors(MessageType.DIRECTORY_PEER_DISCONNECTED, entry, exclude = message.from)
                    }
                }
                logDebug("DIRECTORY_VERIFY_ACK from ${message.from}: MISMATCH, added ${added.size}, removed ${removedEntries.size}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private — directory operations
    // -------------------------------------------------------------------------

    /**
     * Merges incoming PeerEntries into the local directory using Lamport clock
     * conflict resolution. Returns entries that actually changed.
     * Only ACTIVE entries are expected — DISCONNECTED peers are removed, not stored.
     */
    private fun mergeEntries(incoming: List<PeerEntry>): List<PeerEntry> {
        val localId = try {
            localEndpointId()
        } catch (_: IllegalStateException) {
            logWarn("mergeEntries called before identity established — skipping")
            return emptyList()
        }
        val changed = mutableListOf<PeerEntry>()

        for (entry in incoming) {
            if (entry.endpointId == localId) continue
            if (entry.status != PeerStatus.ACTIVE) continue

            val existing = directory[entry.endpointId]

            val winner = when {
                existing == null -> entry
                entry.lamportClock > existing.lamportClock -> entry
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
                "${entry.endpointId}:${entry.status.name}"
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
        val msg = buildMessage(
            to = target,
            type = MessageType.DIRECTORY_VERIFY,
            payload = DirectoryVerifyPayload(hash = computeDirectoryHash())
        ) ?: return
        send(target, msg)
        logDebug("sent DIRECTORY_VERIFY to $target")
    }

    /** Sends our full directory to a specific endpoint via DIRECTORY_SYNC. */
    private fun sendSync(toEndpointId: String) {
        val activeEntries = directory.values.filter { it.status == PeerStatus.ACTIVE }
        val msg = buildMessage(
            to = toEndpointId,
            type = MessageType.DIRECTORY_SYNC,
            payload = DirectorySyncPayload(activeEntries)
        ) ?: return
        send(toEndpointId, msg)
    }

    /** Broadcasts a single PeerEntry to all direct neighbors, optionally excluding one. */
    private fun broadcastToNeighbors(type: MessageType, entry: PeerEntry, exclude: String?) {
        val payload = DirectoryPeerPayload(peer = entry)
        directNeighbors
            .filter { it != exclude }
            .forEach { neighborId ->
                val msg = buildMessage(to = neighborId, type = type, payload = payload) ?: return@forEach
                send(neighborId, msg)
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
            logWarn("failed to extract clock from $type message: ${e.message}")
            0L
        }
    }

    /** Builds an outbound Message with a serialized protobuf payload, or null if identity not yet set. */
    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T : Any> buildMessage(
        to: String,
        type: MessageType,
        payload: T,
        replyTo: String? = null
    ): Message? {
        val from = try { localEndpointId() } catch (_: IllegalStateException) {
            logWarn("buildMessage($type) called before identity established — dropping")
            return null
        }
        return Message(
            to = to,
            from = from,
            type = type,
            data = ProtoBuf.encodeToByteArray(payload),
            ttl = 1,
            replyTo = replyTo,
            id = UUID.randomUUID().toString()
        )
    }

    // -------------------------------------------------------------------------
    // Private — StateFlow emission
    // -------------------------------------------------------------------------

    /**
     * Emits a fresh snapshot to both StateFlows whenever the directory changes.
     * Must be called after every mutation to the [directory] map.
     */
    private fun notifyDirectoryChanged() {
        val snapshot = directory.values.toList()
        logDebug("directory changed: ${snapshot.map { "${it.displayName}=${it.status}" }}")
        _allPeers.value = snapshot
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