package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val keepaliveIntervalMs: Long = 5_000,
    private val keepaliveTimeoutMs: Long = 15_000
) : TopologyStrategy {

    override val topologyCode: String = "msh"

    // Everyone is equal in a full mesh — no routers or leaves
    override val localRole: TopologyStrategy.Role = TopologyStrategy.Role.PEER

    private val peers = ConcurrentHashMap<String, TopologyPeer>()
    private var keepaliveJob: Job? = null
    private var discoveryJob: Job? = null
    private val logger = KotlinLogging.logger {}

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

        peers.values.forEach { topoPeer ->
            val timeSinceLastPong = now - topoPeer.lastPongAt

            if (timeSinceLastPong > keepaliveTimeoutMs) {
                logger.warn { "Peer ${topoPeer.hardwareId} timed out — removing" }
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

        // Re-evaluate whether we need more connections after each tick
        evaluateHealth(context)
    }

    // -------------------------------------------------------------------------
    // Health — drives advertising and discovery
    // -------------------------------------------------------------------------

    private fun evaluateHealth(context: TopologyContext) {
        when {
            peers.size >= maxPeerCount -> {
                // Saturated — stop being discoverable, stop looking
                logger.info { "Mesh saturated (${peers.size}/$maxPeerCount) — stopping discovery" }
                stopDiscovery(context)
            }

            peers.size < targetPeerCount -> {
                // Below soft target — actively look for more peers
                logger.info { "Below target peers (${peers.size}/$targetPeerCount) — starting discovery" }
                startDiscovery(context)
            }

            else -> {
                // Between target and max — stop scanning but keep advertising.
                // Cancel the discovery job so it can be restarted if we drop
                // back below target (isActive guard in startDiscovery would
                // otherwise prevent it from restarting).
                discoveryJob?.cancel()
                discoveryJob = null
                context.stopScan()
                context.startAdvertising(context.encodedName())
            }
        }
    }

    private fun startDiscovery(context: TopologyContext) {
        // Always re-advertise and re-scan — e.g. after eviction the scan may have
        // been stopped internally but the collect job is still alive.
        context.startAdvertising(context.encodedName())
        context.startScan()

        // Only launch a new collect job if one is not already running.
        if (discoveryJob?.isActive == true) return

        discoveryJob = context.launchJob {
            context.events
                .filterIsInstance<edu.uwm.cs595.goup11.backend.network.NetworkEvent.EndpointDiscovered>()
                .collect { ev ->
                    if (peers.size >= maxPeerCount) {
                        context.stopScan()
                        context.stopAdvertising()
                        discoveryJob?.cancel()
                        return@collect
                    }

                    val advertisedName =
                        edu.uwm.cs595.goup11.backend.network.AdvertisedName.decode(ev.encodedName)
                            ?: return@collect

                    if (advertisedName.topologyCode != topologyCode) return@collect

                    // Tie-breaking: lower encodedName initiates to avoid simultaneous connect
                    if (context.encodedName() > ev.encodedName) return@collect

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
        // Hard limit — never exceed maxPeerCount
        return peers.size < maxPeerCount
    }

    override fun onPeerConnected(
        context: TopologyContext,
        endpointId: String,
        advertisedName: AdvertisedName
    ) {
        peers[endpointId] = TopologyPeer(
            hardwareId = endpointId,
            advertisedName = advertisedName
        )

        logger.info { "Mesh peer connected: $endpointId (${peers.size}/$maxPeerCount)" }

        evaluateHealth(context)
    }

    override fun onPeerDisconnected(context: TopologyContext, endpointId: String) {
        peers.remove(endpointId)

        logger.info { "Mesh peer disconnected: $endpointId (${peers.size}/$maxPeerCount)" }

        // Immediately try to fill the gap
        evaluateHealth(context)
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    override fun onMessage(context: TopologyContext, message: Message): Boolean {
        val senderPeer = peers[message.from]
            ?: peers.values.find { it.advertisedName.encode() == message.from }

        return when (message.type) {
            MessageType.PING -> {
                val peer = senderPeer ?: run {
                    logger.warn { "PING from unknown sender '${message.from}' — cannot reply" }
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

            MessageType.PONG -> {
                senderPeer?.lastPongAt = System.currentTimeMillis()
                true
            }

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
            .also { if (it.isEmpty()) logger.warn { "No route to ${message.to}" } }
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