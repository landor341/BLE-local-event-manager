package edu.uwm.cs595.goup11.backend.network

import androidx.annotation.VisibleForTesting
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Local in-memory network emulator.
 *
 * Simulates Google Nearby Connections peer-to-peer behaviour using shared
 * in-process state. Each [LocalNetwork] instance represents one physical device.
 *
 * Key design decisions:
 *  - There is NO concept of a named "network" here — that is an application-layer
 *    concern owned by Client. This class only knows about endpoints and connections.
 *  - [InMemoryNetworkHolder] is the shared global state that all [LocalNetwork]
 *    instances read/write, simulating the shared physical medium (Bluetooth/WiFi).
 *  - Connections are always bidirectional.
 *  - [onConnectionRequest] must be set by Client before any connections are accepted.
 *
 * This class DOES NOT handle any message processing or routing.
 */
class LocalNetwork(
    private val chaos: ChaosConfig = ChaosConfig.NONE,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),

    /**
     * Represents a list of messages to not log on receive. By default, PING, PONG, and HELLO are ignored
     */
    private val messageTypeLogIgnoreList: List<MessageType> = listOf<MessageType>(MessageType.PONG,
        MessageType.PING, MessageType.HELLO
    )
) : Network {

    override val logger: KLogger = KotlinLogging.logger {}

    init {
        logger.warn {
            "LocalNetwork created — if this is a unit test or local debug session this warning can be ignored"
        }
    }

    // -------------------------------------------------------------------------
    // Network interface — reactive state
    // -------------------------------------------------------------------------

    private val _state  = MutableStateFlow<NetworkState>(NetworkState.Idle)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, replay = 1)
    override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    /** This node's current endpoint ID — set by init() */
    private var localEndpointId: String? = null

    private var isScanning    = false
    private var isAdvertising = false

    private val listeners = mutableListOf<(Message) -> Unit>()

    /**
     * Set by Client. Called when a remote endpoint wants to connect to us.
     * Returns true to accept, false to reject.
     * Defaults to reject-all until Client wires it up.
     */
    override var onConnectionRequest: suspend (endpointId: String, encodedName: String) -> Boolean =
        { _, _ -> false }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialise this network instance with the local endpoint's identity.
     * Should be called whenever the local identity changes (e.g. role promotion).
     */
    override fun init(localEndpointId: String, config: Network.Config) {
        this.localEndpointId = localEndpointId
        _state.value = NetworkState.Idle
        logger.debug { "LocalNetwork initialised with endpointId=$localEndpointId" }
    }

    override fun shutdown() {
        stopAdvertising()
        isScanning = false

        // Remove ourselves from the shared node registry
        val id = localEndpointId
        if (id != null) {
            val node = InMemoryNetworkHolder.getNode(id)
            if (node != null) {
                // Notify all connected peers that we have disconnected
                node.connections.toList().forEach { peerId ->
                    val peer = InMemoryNetworkHolder.getNode(peerId)
                    peer?.network?.receiveDisconnect(id)
                }
                InMemoryNetworkHolder.removeNode(id)
            }
        }

        listeners.clear()
        localEndpointId = null
        _state.value = NetworkState.Idle
        logger.debug { "LocalNetwork shut down" }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    override fun startAdvertising(encodedName: String) {
        val id = requireLocalEndpointId()
        isAdvertising = true

        // Register or update our node in the shared holder
        val existing = InMemoryNetworkHolder.getNode(id)
        if (existing != null) {
            existing.isAdvertising  = true
            existing.encodedName    = encodedName
        } else {
            InMemoryNetworkHolder.addNode(
                InMemoryNetworkPeer(
                    endpointId    = id,
                    encodedName   = encodedName,
                    isAdvertising = true,
                    isDiscovering = false,
                    connections   = mutableListOf(),
                    network       = this
                )
            )
        }

        _state.value = NetworkState.Advertising
        logger.debug { "$id started advertising as '$encodedName'" }
    }

    override fun stopAdvertising() {
        val id = localEndpointId ?: return
        isAdvertising = false
        InMemoryNetworkHolder.getNode(id)?.isAdvertising = false

        if (_state.value is NetworkState.Advertising) {
            _state.value = NetworkState.Idle
        }
        logger.debug { "$id stopped advertising" }
    }

    // -------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------

    override suspend fun startDiscovery() {
        if (isScanning) return
        // Discovery is a passive listen — we do not need a local identity to
        // observe what others are advertising. localEndpointId may be null here
        // if joinNetwork() calls us before init() (which is the correct order:
        // discover first, then adopt an identity once we know the topology).
        val id = localEndpointId   // nullable — used only for self-exclusion
        isScanning = true

        // Register ourselves in the holder only if we already have an identity,
        // so the isDiscovering flag is visible to other nodes if needed.
        if (id != null) {
            val existing = InMemoryNetworkHolder.getNode(id)
            if (existing != null) {
                existing.isDiscovering = true
            } else {
                InMemoryNetworkHolder.addNode(
                    InMemoryNetworkPeer(
                        endpointId    = id,
                        encodedName   = id,
                        isAdvertising = false,
                        isDiscovering = true,
                        connections   = mutableListOf(),
                        network       = this
                    )
                )
            }
        }

        _state.value = NetworkState.Discovering

        // Emit currently advertising nodes immediately, excluding ourselves
        // if we have an identity (avoids self-discovery after init is called).
        InMemoryNetworkHolder.allNodes()
            .filter { it.isAdvertising && it.endpointId != id }
            .forEach { node ->
                logger.debug { "${id ?: "unidentified"} discovered ${node.endpointId}" }
                _events.tryEmit(
                    NetworkEvent.EndpointDiscovered(node.endpointId, node.encodedName)
                )
            }

        // Keep polling so newly advertising nodes are also discovered.
        // Re-read localEndpointId on each tick — it may have been set by
        // init() while we were already scanning.
        while (isScanning) {
            delay(750)
            val currentId = localEndpointId
            InMemoryNetworkHolder.allNodes()
                .filter { it.isAdvertising && it.endpointId != currentId }
                .forEach { node ->
                    _events.tryEmit(
                        NetworkEvent.EndpointDiscovered(node.endpointId, node.encodedName)
                    )
                }
        }
    }

    override suspend fun stopDiscovery() {
        val id = localEndpointId
        isScanning = false
        if (id != null) {
            InMemoryNetworkHolder.getNode(id)?.isDiscovering = false
        }

        if (_state.value is NetworkState.Discovering) {
            _state.value = NetworkState.Idle
        }
        logger.debug { "${id ?: "unidentified"} stopped discovery" }
    }

    // -------------------------------------------------------------------------
    // Connections
    // -------------------------------------------------------------------------

    /**
     * Initiate a connection request to [endpointId].
     *
     * Calls [onConnectionRequest] on the remote side. If accepted, wires up the
     * bidirectional connection and fires [NetworkEvent.EndpointConnected] on both sides.
     * If rejected, fires [NetworkEvent.ConnectionRejected] on this side only.
     */
    override suspend fun connect(endpointId: String) {
        val localId  = requireLocalEndpointId()
        val remote   = InMemoryNetworkHolder.getNode(endpointId)
            ?: throw IllegalStateException("Endpoint $endpointId not found in network")

        // Apply chaos config — randomly fail connection attempts
        if (chaos.connectionFailureRate > 0.0 && Math.random() < chaos.connectionFailureRate) {
            logger.debug { "CHAOS: connection from $localId to $endpointId failed" }
            _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
            return
        }

        // Ask the remote side whether it accepts
        val accepted = remote.network.onConnectionRequest(localId, localId)
        //                                                           ^ we pass our own endpointId
        //                                                             as the encoded name here since
        //                                                             in LocalNetwork the endpointId
        //                                                             IS the encoded name

        if (!accepted) {
            logger.debug { "$endpointId rejected connection from $localId" }
            _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
            return
        }

        // Wire up bidirectional connection in the shared holder
        val localNode = InMemoryNetworkHolder.getOrCreateNode(localId, this)

        if (localNode.connections.contains(endpointId)) return

        localNode.connections.add(endpointId)
        remote.connections.add(localId)

        // Notify both sides
        _events.tryEmit(NetworkEvent.EndpointConnected(endpointId, remote.encodedName))
        remote.network._events.tryEmit(NetworkEvent.EndpointConnected(localId, localId))

        logger.debug { "$localId connected to $endpointId" }
    }

    override fun disconnect(endpointId: String) {
        val localId = localEndpointId ?: return

        val localNode  = InMemoryNetworkHolder.getNode(localId)  ?: return
        val remoteNode = InMemoryNetworkHolder.getNode(endpointId) ?: return

        localNode.connections.remove(endpointId)
        remoteNode.connections.remove(localId)

        // Notify both sides
        _events.tryEmit(NetworkEvent.EndpointDisconnected(endpointId))
        remoteNode.network._events.tryEmit(NetworkEvent.EndpointDisconnected(localId))

        logger.debug { "$localId disconnected from $endpointId" }
    }

    /**
     * Called internally when a remote peer disconnects us (not via our own disconnect() call).
     */
    internal fun receiveDisconnect(fromEndpointId: String) {
        val localId = localEndpointId ?: return
        InMemoryNetworkHolder.getNode(localId)?.connections?.remove(fromEndpointId)
        _events.tryEmit(NetworkEvent.EndpointDisconnected(fromEndpointId))
        logger.debug { "$localId received disconnect from $fromEndpointId" }
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    override fun sendMessage(endpointId: String, message: Message) {
        val localId   = requireLocalEndpointId()
        val localNode = InMemoryNetworkHolder.getNode(localId)
            ?: throw IllegalStateException("$localId is not registered in the network")

        if (!localNode.connections.contains(endpointId)) {
            throw IllegalStateException("$localId is not connected to $endpointId")
        }

        val remote = InMemoryNetworkHolder.getNode(endpointId)
            ?: throw IllegalStateException("Endpoint $endpointId not found")

        // Apply chaos config — randomly drop messages
        if (chaos.messageDropRate > 0.0 && Math.random() < chaos.messageDropRate) {
            logger.debug { "CHAOS: dropped message ${message.id} from $localId to $endpointId" }
            return
        }

        // Apply latency if configured
        if (chaos.minLatencyMs > 0) {
            scope.launch {
                val latency = (chaos.minLatencyMs..chaos.maxLatencyMs).random()
                delay(latency)
                remote.network.receiveMessage(message)
            }
        } else {
            remote.network.receiveMessage(message)
        }
        if(!messageTypeLogIgnoreList.contains(message.type)) {
            logger.debug { "$localId sent ${message.type} to $endpointId (id=${message.id})" }
        }
    }

    /**
     * Called internally when a message arrives at this node.
     */
    internal fun receiveMessage(message: Message) {
        // Ignore logs for certian message types
        if(!messageTypeLogIgnoreList.contains(message.type)) {
            logger.debug { "$localEndpointId received ${message.type} from ${message.from}" }
        }
        notifyListeners(message)
        _events.tryEmit(NetworkEvent.MessageReceived(message))
    }

    override fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    override fun notifyListeners(message: Message) {
        listeners.forEach { it.invoke(message) }
    }

    // -------------------------------------------------------------------------
    // Debug helpers
    // -------------------------------------------------------------------------

    @VisibleForTesting
    fun prettyPrintConnections(): String {
        val id = localEndpointId ?: return "Not initialised"
        val node = InMemoryNetworkHolder.getNode(id) ?: return "Not registered"
        val sb = StringBuilder()
        sb.appendLine("=== $id ===")
        sb.appendLine("Advertising : ${node.isAdvertising}")
        sb.appendLine("Discovering : ${node.isDiscovering}")
        sb.appendLine("Connections :")
        node.connections.forEach { sb.appendLine("  └── $it") }
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    private fun requireLocalEndpointId(): String =
        localEndpointId ?: error("LocalNetwork has not been initialised — call init() first")

    // =========================================================================
    // Shared in-memory state
    // =========================================================================

    /**
     * Represents one physical device in the simulated network.
     */
    data class InMemoryNetworkPeer(
        val endpointId:    String,
        var encodedName:   String,
        var isAdvertising: Boolean,
        var isDiscovering: Boolean,
        val connections:   MutableList<String>,
        val network:       LocalNetwork
    )

    /**
     * Global registry of all simulated devices.
     * Shared across all [LocalNetwork] instances in the same process,
     * simulating the shared physical medium.
     */
    object InMemoryNetworkHolder {
        private val nodes = mutableListOf<InMemoryNetworkPeer>()

        fun allNodes():                    List<InMemoryNetworkPeer> = nodes.toList()
        fun getNode(endpointId: String):   InMemoryNetworkPeer?      = nodes.find { it.endpointId == endpointId }
        fun addNode(peer: InMemoryNetworkPeer)                       { if (getNode(peer.endpointId) == null) nodes.add(peer) }
        fun removeNode(endpointId: String)                           { nodes.removeIf { it.endpointId == endpointId } }

        fun getOrCreateNode(endpointId: String, network: LocalNetwork): InMemoryNetworkPeer {
            return getNode(endpointId) ?: InMemoryNetworkPeer(
                endpointId    = endpointId,
                encodedName   = endpointId,
                isAdvertising = false,
                isDiscovering = false,
                connections   = mutableListOf(),
                network       = network
            ).also { addNode(it) }
        }

        /** Clears all state — call in @Before/@After in tests */
        fun purge() {
            nodes.clear()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Convenience purge for tests */
        fun purge() {
            logger.debug { "Purging InMemoryNetworkHolder" }
            InMemoryNetworkHolder.purge()
        }


        fun displayNetworkGraph() {
            val nodes = InMemoryNetworkHolder.allNodes()

            // Collect unique undirected edges — skip B→A if A→B already recorded
            val seen  = mutableSetOf<Pair<String, String>>()
            val edges = mutableListOf<Pair<String, String>>()
            for (node in nodes) {
                for (conn in node.connections) {
                    val key = if (node.endpointId < conn)
                        node.endpointId to conn else conn to node.endpointId
                    if (seen.add(key)) edges.add(node.endpointId to conn)
                }
            }

            // Extract short display name from encoded N: field, or fall back to full id
            fun shortName(id: String): String =
                Regex("N:([^|]+)").find(id)?.groupValues?.get(1) ?: id

            val sb    = StringBuilder()
            val width = 56

            sb.appendLine("┌─ Network Graph (${nodes.size} nodes, ${edges.size} edges) ${"─".repeat(width)}".take(width + 2) + "┐")

            // ── Nodes ──
            sb.appendLine("│  Nodes")
            if (nodes.isEmpty()) {
                sb.appendLine("│    (none)")
            } else {
                for (node in nodes) {
                    val flags = buildString {
                        append(if (node.isAdvertising)          "A" else " ")
                        append(if (node.isDiscovering)          "D" else " ")
                        append(if (node.connections.isNotEmpty()) "C" else " ")
                    }
                    sb.appendLine("│    [$flags] ${node.endpointId}")
                }
            }

            // ── Edges ──
            sb.appendLine("│  Edges")
            if (edges.isEmpty()) {
                sb.appendLine("│    (none)")
            } else {
                val maxLen = edges.maxOf { shortName(it.first).length }
                for ((a, b) in edges) {
                    val left  = shortName(a).padEnd(maxLen)
                    val right = shortName(b)
                    sb.appendLine("│    $left  ↔  $right")
                }
            }

            sb.append("└${"─".repeat(width + 1)}┘")

            println(sb)
            logger.debug { "\n$sb" }
        }
    }
}

// =============================================================================
// Chaos configuration
// =============================================================================

/**
 * Controls how the [LocalNetwork] emulator misbehaves during tests.
 * Use [NONE] for deterministic unit tests, [MILD] or [SEVERE] for resilience tests.
 */
data class ChaosConfig(
    /** 0.0 = never drop, 1.0 = always drop */
    val messageDropRate:          Double = 0.0,

    /** Probability a connection attempt fails outright */
    val connectionFailureRate:    Double = 0.0,

    /** Simulated min/max latency added to message delivery */
    val minLatencyMs:             Long   = 0,
    val maxLatencyMs:             Long   = 0
) {
    companion object {
        val NONE   = ChaosConfig()
        val MILD   = ChaosConfig(messageDropRate = 0.05, connectionFailureRate = 0.05, minLatencyMs = 10, maxLatencyMs = 50)
        val SEVERE = ChaosConfig(messageDropRate = 0.30, connectionFailureRate = 0.20, minLatencyMs = 50, maxLatencyMs = 300)
    }
}