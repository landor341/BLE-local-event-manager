package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val keepaliveTimeoutMs:  Long = 15_000
) : TopologyStrategy {

    override val topologyCode: String = "msh"

    // Everyone is equal in a full mesh — no routers or leaves
    override val localRole: TopologyStrategy.Role = TopologyStrategy.Role.PEER

    private val peers = ConcurrentHashMap<String, TopologyPeer>()
    private var keepaliveJob:  Job? = null
    private var discoveryJob:  Job? = null
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
                logger.warn { "Peer ${topoPeer.endpointId} timed out — removing" }
                onPeerDisconnected(context, topoPeer.endpointId)
            } else {
                context.sendMessage(
                    topoPeer.endpointId,
                    Message(
                        to   = topoPeer.endpointId,
                        from = context.endpointId,
                        type = MessageType.PING,
                        ttl  = 1
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
                // Between target and max — we're healthy, no action needed
                // We stay connectable (advertising) so others can reach us,
                // but we don't actively scan
                context.startAdvertising(context.encodedName())
                context.stopScan()
            }
        }
    }

    private fun startDiscovery(context: TopologyContext) {
        if (discoveryJob?.isActive == true) return

        discoveryJob = context.launchJob {
            while (peers.size < targetPeerCount) {
                context.startAdvertising(context.encodedName())
                context.startScan()

                delay(discoveryIntervalMs)

                context.stopScan()
            }

            // Reached target — stop scanning but keep advertising so others
            // can still connect to us until we hit maxPeerCount
            context.stopScan()
            logger.info { "Mesh target reached (${peers.size}/$targetPeerCount) — scanning stopped" }

            // Re-evaluate: if we're now at max, also stop advertising
            if (peers.size >= maxPeerCount) {
                context.stopAdvertising()
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
            endpointId     = endpointId,
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
        return when (message.type) {
            MessageType.PING -> {
                context.sendMessage(
                    message.from,
                    Message(
                        to      = message.from,
                        from    = context.endpointId,
                        type    = MessageType.PONG,
                        replyTo = message.id,
                        ttl     = 1
                    )
                )
                true
            }
            MessageType.PONG -> {
                peers[message.from]?.lastPongAt = System.currentTimeMillis()
                true
            }
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    override fun resolveNextHop(context: TopologyContext, message: Message): List<String> {
        // Direct delivery if destination is a known peer
        if (peers.containsKey(message.to)) {
            return listOf(message.to)
        }

        // Flood to all peers except the sender
        // The TTL field on Message prevents infinite loops as it propagates
        return peers.keys
            .filter { it != message.from }
            .also { if (it.isEmpty()) logger.warn { "No route to ${message.to}" } }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    override fun shouldAdvertise(context: TopologyContext): Boolean {
        // Advertise whenever we have room for more peers
        return peers.size < maxPeerCount
    }
}
