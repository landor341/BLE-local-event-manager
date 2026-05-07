package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import java.util.concurrent.ConcurrentHashMap

/**
 * Full mesh topology strategy.
 *
 * Basic strategy:
 *  - Every node tries to maintain [targetPeerCount] connections (soft target)
 *  - No node can exceed [maxPeerCount] connections (hard limit)
 *  - When below [targetPeerCount], the node advertises and scans to find more peers
 *  - When at [maxPeerCount], the node stops advertising
 *  - When a peer leaves, the node immediately begins looking for a replacement
 *  - Messages are sent directly if the destination is a known peer,
 *    otherwise flooded to all non-sender neighbors (TTL prevents loops)
 *
 * Known limitation: if the network partitions into two isolated groups
 * with no physical bridge between them, messages between partitions will
 * silently fail. This is inherent to a physical proximity network and
 * cannot be solved purely in software.
 */
class MeshTopology(
    /** Hard upper limit on peer connections */
    override val maxPeerCount: Int = 6,

    /** Soft target — node actively seeks peers until it reaches this count */
    private val targetPeerCount: Int = 3,

    private val discoveryIntervalMs: Long = 5_000,
    private val keepaliveIntervalMs: Long = 10_000,
    private val keepaliveTimeoutMs: Long = 45_000
) : TopologyStrategy {

    override val topologyCode: String = "msh"
    override val localRole: TopologyStrategy.Role = TopologyStrategy.Role.PEER

    private val peers = ConcurrentHashMap<String, TopologyPeer>()

    /** Prevents simultaneous-accept race exceeding maxPeerCount. */
    private val pendingConnections = java.util.concurrent.atomic.AtomicInteger(0)

    private var keepaliveJob: Job? = null
    private var discoveryJob: Job? = null

    private fun logDebug(msg: String) = Log.d("MeshTopology", msg)
    private fun logWarn(msg: String) = Log.w("MeshTopology", msg)

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun start(context: TopologyContext) {
        startKeepalive(context)
        evaluateHealth(context)
    }

    override fun stop() {
        keepaliveJob?.cancel()
        discoveryJob?.cancel()
        peers.clear()
        pendingConnections.set(0)
    }

    // -------------------------------------------------------------------------
    // Keepalive
    // -------------------------------------------------------------------------

    private fun startKeepalive(context: TopologyContext) {
        keepaliveJob = context.launchJob {
            while (true) {
                delay(keepaliveIntervalMs)
                tickKeepalive(context)
            }
        }
    }

    private fun tickKeepalive(context: TopologyContext) {
        val now = System.currentTimeMillis()

        peers.values.toList().forEach { topoPeer ->
            val timeSinceLastPong = now - topoPeer.lastPongAt

            if (timeSinceLastPong > keepaliveTimeoutMs) {
                // Guard: only remove if this is still the same peer instance.
                if (peers[topoPeer.hardwareId] !== topoPeer) return@forEach
                logWarn("Peer ${topoPeer.hardwareId} timed out — removing")
                onPeerDisconnected(context, topoPeer.hardwareId)
            } else {
                context.sendMessage(
                    topoPeer,
                    Message(
                        to = topoPeer.hardwareId,
                        from = context.endpointId,
                        type = MessageType.PING,
                        ttl = 1
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Health — drives advertising and discovery
    // -------------------------------------------------------------------------

    private fun evaluateHealth(context: TopologyContext) {
        when {
            peers.size >= maxPeerCount -> {
                logDebug("Mesh saturated (${peers.size}/$maxPeerCount) — stopping scan, keeping advertising")
                // Keep advertising so new nodes can discover the mesh exists,
                // but stop scanning since we can't accept anyone anyway.
                discoveryJob?.cancel()
                discoveryJob = null
                context.stopScan()
                context.startAdvertising(context.encodedName())
            }

            peers.size < targetPeerCount -> {
                logDebug("Below target peers (${peers.size}/$targetPeerCount) — starting discovery")
                startDiscovery(context)
            }

            else -> {
                // Between target and max — stop scanning but keep advertising
                discoveryJob?.cancel()
                discoveryJob = null
                context.stopScan()
                context.startAdvertising(context.encodedName())
            }
        }
    }

    private fun startDiscovery(context: TopologyContext) {
        context.startAdvertising(context.encodedName())
        context.startScan()

        if (discoveryJob?.isActive == true) return

        discoveryJob = context.launchJob {
            context.events
                .filterIsInstance<edu.uwm.cs595.goup11.backend.network.NetworkEvent.EndpointDiscovered>()
                .collect { ev ->
                    if (peers.size >= maxPeerCount) {
                        // Slots full — stop scanning but keep advertising
                        context.stopScan()
                        discoveryJob?.cancel()
                        return@collect
                    }

                    val advertisedName =
                        edu.uwm.cs595.goup11.backend.network.AdvertisedName.decode(ev.encodedName)
                            ?: return@collect

                    if (advertisedName.topologyCode != topologyCode) return@collect

                    // Tie-breaking: lower encodedName initiates to avoid simultaneous connect
                    if (context.encodedName() > ev.encodedName) return@collect

                    // Random backoff reduces simultaneous-connect collisions
                    delay((100L..600L).random())

                    context.connect(ev.endpointId)
                }
        }
    }

    private fun stopDiscovery(context: TopologyContext) {
        discoveryJob?.cancel()
        discoveryJob = null
        context.stopScan()
        context.stopAdvertising()
    }

    // -------------------------------------------------------------------------
    // Connection events
    // -------------------------------------------------------------------------

    override suspend fun shouldAcceptConnection(
        context: TopologyContext,
        endpointId: String,
        advertisedName: AdvertisedName
    ): Boolean {
        val total = peers.size + pendingConnections.get()
        if (total >= maxPeerCount) {
            logDebug("Rejecting $endpointId — slots full ($total/$maxPeerCount)")
            return false
        }
        pendingConnections.incrementAndGet()
        return true
    }

    override fun onPeerConnected(
        context: TopologyContext,
        endpointId: String,
        advertisedName: AdvertisedName
    ) {
        pendingConnections.decrementAndGet()
        peers[endpointId] = TopologyPeer(
            hardwareId = endpointId,
            advertisedName = advertisedName
        )

        logDebug("Mesh peer connected: $endpointId (${peers.size}/$maxPeerCount)")
        edu.uwm.cs595.goup11.backend.network.TelemetryManager.updateDirectPeers(
            peers.values.map { it.advertisedName.displayName }
        )
        evaluateHealth(context)
    }

    override fun onPeerDisconnected(context: TopologyContext, endpointId: String) {
        peers.remove(endpointId)
        logDebug("Mesh peer disconnected: $endpointId (${peers.size}/$maxPeerCount)")
        edu.uwm.cs595.goup11.backend.network.TelemetryManager.updateDirectPeers(
            peers.values.map { it.advertisedName.displayName }
        )
        evaluateHealth(context)
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    override fun onMessage(context: TopologyContext, message: Message): Boolean {
        val senderPeer = peers[message.from]
            ?: peers.values.find { it.advertisedName.encode() == message.from }

        // Any message from a peer proves it's alive — reset its keepalive timer.
        senderPeer?.lastPongAt = System.currentTimeMillis()

        return when (message.type) {
            MessageType.PING -> {
                val peer = senderPeer ?: run {
                    logWarn("PING from unknown sender '${message.from}' — cannot reply")
                    return true
                }
                context.sendMessage(
                    peer,
                    Message(
                        to = peer.hardwareId,
                        from = context.endpointId,
                        type = MessageType.PONG,
                        replyTo = message.id,
                        ttl = 1
                    )
                )
                true
            }

            MessageType.PONG -> true // lastPongAt already updated above

            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    override fun resolveNextHop(context: TopologyContext, message: Message): List<String> {
        // Find dest peer by encoded name, falling back to treating message.to as a hardware ID
        val destPeer = peers.values.find { it.advertisedName.encode() == message.to }
            ?: peers[message.to]

        if (destPeer != null) {
            return listOf(destPeer.hardwareId)
        }

        // Flood to all peers except the sender
        val senderPeer = peers.values.find { it.advertisedName.encode() == message.from }
            ?: peers[message.from]
        return peers.values
            .filter { it != senderPeer }
            .map { it.hardwareId }
            .also { if (it.isEmpty()) logWarn("No route to ${message.to}") }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    override fun shouldAdvertise(context: TopologyContext): Boolean {
        // Advertise whenever we have room for more peers
        return peers.size < maxPeerCount
    }

    override suspend fun disconnectFromAllNodes(context: TopologyContext) {
        // Disconnect from all peers
        peers.keys.forEach { endpointId ->
            context.disconnect(endpointId)
        }
    }

    override fun retrieveAllConnectedClients(): List<TopologyPeer> {
        return peers.values.toList()
    }
}