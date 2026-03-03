package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class SnakeTopology(
    override val maxPeerCount: Int = 2,
    private val discoveryIntervalMs: Long = 5_000,
    private val keepaliveIntervalMs: Long = 5_000,
    private val keepaliveTimeoutMs:  Long = 15_000
) : TopologyStrategy {

    override val topologyCode: String = "snk"

    // Everyone is equal in a snake — no routers or leaves
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
        evaluateHealth(context) // kick off discovery immediately if needed
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
    }

    // -------------------------------------------------------------------------
    // Health — the core of the snake logic
    // -------------------------------------------------------------------------

    private fun evaluateHealth(context: TopologyContext) {
        if (peers.size < maxPeerCount) {
            logger.info { "Below max peers (${peers.size}/$maxPeerCount) — starting discovery" }
            startDiscovery(context)
        } else {
            logger.info { "At max peers — stopping discovery" }
            stopDiscovery(context)
        }
    }

    private fun startDiscovery(context: TopologyContext) {
        if (discoveryJob?.isActive == true) return

        discoveryJob = context.launchJob {
            while (peers.size < maxPeerCount) {
                // Advertise with our current encoded name so others can find us
                context.startAdvertising(context.encodedName())
                context.startScan()

                delay(discoveryIntervalMs)

                context.stopScan()
            }

            // Reached max peers — stop advertising and scanning
            context.stopScan()
            context.stopAdvertising()
            logger.info { "Snake slots filled — discovery stopped" }
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
        // Only accept if we have an open slot
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

        logger.info { "Snake peer connected: $endpointId (${peers.size}/$maxPeerCount)" }

        evaluateHealth(context)
    }

    override fun onPeerDisconnected(context: TopologyContext, endpointId: String) {
        peers.remove(endpointId)

        logger.info { "Snake peer disconnected: $endpointId (${peers.size}/$maxPeerCount)" }

        // Chain is broken — look for a replacement neighbor
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
        // Direct delivery if destination is a neighbor
        if (peers.containsKey(message.to)) {
            return listOf(message.to)
        }

        // Flood to all neighbors except whoever sent the message
        // TTL on the Message prevents infinite loops
        return peers.keys
            .filter { it != message.from }
            .also { if (it.isEmpty()) logger.warn { "No route to ${message.to}" } }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    override fun shouldAdvertise(context: TopologyContext): Boolean {
        return peers.size < maxPeerCount
    }
}
