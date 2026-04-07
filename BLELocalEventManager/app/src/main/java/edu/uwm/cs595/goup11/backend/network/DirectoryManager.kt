package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.payloads.DirectoryPeerAddedPayload
import kotlinx.coroutines.CoroutineScope

class DirectoryManager(
    /** Returns the local endpoint ID. Used as a method since id is valid after network join*/
    private val localEndpointId: () -> String,

    /** Callback that this class uses to send a message to a specific endpoint */
    private val send: (toEndpoint: String, message: Message) -> Unit,

    private val scope: CoroutineScope,

    /** Determines how often to send the DIRECTORY_VERIFY message */
    private val verifyIntervalMs: Long = 10_000L,
) {

    /**
     * Local peer directory,
     * Peer = endpointId, Value = PeerEntry
     */
    private val directory = mutableMapOf<String, PeerEntry>()

    /**
     * Monotonically increasing local clock that is used for conflict resolution during merges
     */
    private var lamportClock: Long = 0L

    /**
     * Tracks when each peer was last verified. Used to determine which one to track next
     */
    private val lastVerifiedAt = mutableMapOf<String, Long>()

    /**
     * Starts the periodic entry loop
     */
    fun start() {
        TODO()
    }

    /**
     * Cancels the periodic verify job and clears all directory entries
     */
    fun stop() {
        TODO()
    }

    // Network event hooks
    fun onPeerConnected(endpointId: String, advertisedName: AdvertisedName) {
        TODO()
    }

    fun onPeerDisconnected(endpointId: String) {
        TODO()
    }

    // Message handlers

    /**
     * Used to handle an inbound message. Returns true if the message is consumed, false if not
     */
    fun onMessage(message: Message): Boolean {
        TODO()
    }

    /**
     * Handles the [MessageType.DIRECTORY_SYNC] message type
     */
    private fun handleSync(message: Message) {
        TODO()
    }

    /**
     * Handles the [MessageType.DIRECTORY_SYNC_ACK] message type
     */
    private fun handleSyncAck(message: Message) {
        TODO()
    }

    /**
     * Handles the [MessageType.DIRECTORY_PEER_ADDED] message type
     */
    private fun handlePeerAdded(message: Message) {
        TODO()
    }

    /**
     * Handles the [MessageType.DIRECTORY_PEER_DISCONNECTED]
     */
    private fun handlePeerDisconnected(message: Message) {
        TODO()
    }

    /**
     * Handles the [MessageType.DIRECTORY_VERIFY] message type
     */
    private fun handleVerify() {
        TODO()
    }

    /**
     * Handles the [MessageType.DIRECTORY_VERIFY_ACK] message type
     */
    private fun handleVerifyAck() {
        TODO()
    }

    // Directory operations
    /**
     * Merges the list of incoming PeerEntries into the local directory
     */
    private fun mergeEntries(incoming: List<PeerEntry>): List<PeerEntry> {
        TODO()
    }

    /**
     * Computes the SHA-256 hash of the current directory
     */
    private fun computeDirectoryHash(): String {
        TODO()
    }

    /**
     * Selects the peer least recently verified to send a message to
     */
    private fun selectVerifyTarget(): String? {
        TODO()
    }

    /**
     * Returns all currently active peers excluding this endpoint
     */
    private fun activeNeighbors(): List<String> {
        TODO()
    }

    /**
     * Increments the current clock
     */
    private fun tickClock(): Long {
        TODO()
    }

    // Public methods

    fun allPeers(): List<PeerEntry> {
        TODO()
    }

    fun activePeers(): List<PeerEntry> {
        TODO()
    }

}