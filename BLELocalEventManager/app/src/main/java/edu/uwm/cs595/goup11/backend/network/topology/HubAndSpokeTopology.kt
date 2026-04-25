package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class HubAndSpokeTopology(
    private val maxLeaves: Int = 5,
    private val maxRouterLinks: Int = 2,
    private val keepaliveIntervalMs: Long = 5_000,
    private val keepaliveTimeoutMs: Long = 15_000,
    initialRole: TopologyStrategy.Role = TopologyStrategy.Role.LEAF
) : TopologyStrategy {

    override val topologyCode: String = "hub"

    override val maxPeerCount: Int get() = maxLeaves + maxRouterLinks

    override var localRole: TopologyStrategy.Role = initialRole
        private set

    private val peers = ConcurrentHashMap<String, TopologyPeer>()
    private var keepaliveJob: Job? = null
    private val logger = KotlinLogging.logger {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun start(context: TopologyContext) {
        startKeepalive(context)

        // Routers advertise immediately on start
        if (localRole == TopologyStrategy.Role.ROUTER) {
            context.startAdvertising(context.encodedName())
        }
    }

    override fun stop() {
        keepaliveJob?.cancel()
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

        // Re-evaluate advertising after each tick
        reevaluateAdvertising(context)
    }

    // -------------------------------------------------------------------------
    // Connection events
    // -------------------------------------------------------------------------

    override suspend fun shouldAcceptConnection(
        context: TopologyContext,
        endpointId: String,
        advertisedName: AdvertisedName
    ): Boolean {
        return when (advertisedName.role) {
            TopologyStrategy.Role.ROUTER -> {
                // Accept router-to-router links up to our router link limit
                val currentRouterLinks =
                    peers.values.count { it.role == TopologyStrategy.Role.ROUTER }
                currentRouterLinks < maxRouterLinks
            }

            TopologyStrategy.Role.LEAF, TopologyStrategy.Role.PEER -> {
                // Only routers accept leaf connections, and only up to maxLeaves
                localRole == TopologyStrategy.Role.ROUTER &&
                        peers.values.count { it.role != TopologyStrategy.Role.ROUTER } < maxLeaves
            }
        }
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

        logger.info { "Hub peer connected: $endpointId as ${advertisedName.role} (${peers.size}/$maxPeerCount)" }

        // If the router we just connected to is already full on leaves,
        // consider volunteering to become a router ourselves
        if (advertisedName.role == TopologyStrategy.Role.ROUTER
            && localRole == TopologyStrategy.Role.LEAF
        ) {
            val routerLeafCount = peers.values.count { it.role != TopologyStrategy.Role.ROUTER }
            if (routerLeafCount >= maxLeaves) {
                promoteToRouter(context)
            }
        }

        reevaluateAdvertising(context)
    }

    override fun onPeerDisconnected(context: TopologyContext, endpointId: String) {
        val topoPeer = peers.remove(endpointId) ?: return

        logger.info { "Hub peer disconnected: $endpointId (was ${topoPeer.role})" }

        when (topoPeer.role) {
            TopologyStrategy.Role.ROUTER -> {
                if (localRole == TopologyStrategy.Role.LEAF) {
                    // Lost our router — need to find a new one
                    logger.info { "Lost router $endpointId — scanning for replacement" }
                    context.startScan()
                }
            }

            else -> Unit // Leaf left — just free up the slot
        }

        reevaluateAdvertising(context)
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

        return when (localRole) {
            TopologyStrategy.Role.ROUTER -> {
                // We don't have the destination directly — forward to peer routers
                peers.values
                    .filter { it.role == TopologyStrategy.Role.ROUTER }
                    .map { it.hardwareId }
                    .also { if (it.isEmpty()) logger.warn { "No router links to forward ${message.to}" } }
            }

            TopologyStrategy.Role.LEAF, TopologyStrategy.Role.PEER -> {
                // Leaves always send up to their router
                peers.values
                    .filter { it.role == TopologyStrategy.Role.ROUTER }
                    .map { it.hardwareId }
                    .take(1)
                    .also { if (it.isEmpty()) logger.warn { "No router available to forward ${message.to}" } }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    override fun shouldAdvertise(context: TopologyContext): Boolean {
        return when (localRole) {
            TopologyStrategy.Role.ROUTER -> {
                val leafCount = peers.values.count { it.role != TopologyStrategy.Role.ROUTER }
                leafCount < maxLeaves
            }
            // Leaves never advertise — they join, they don't host
            TopologyStrategy.Role.LEAF, TopologyStrategy.Role.PEER -> false
        }
    }

    override suspend fun disconnectFromAllNodes(context: TopologyContext) {
        // Disconnect from all peers
        peers.keys.forEach { endpointId ->
            context.disconnect(endpointId)
        }
    }
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun reevaluateAdvertising(context: TopologyContext) {
        if (shouldAdvertise(context)) context.startAdvertising(context.encodedName())
        else context.stopAdvertising()
    }

    private fun promoteToRouter(context: TopologyContext) {
        localRole = TopologyStrategy.Role.ROUTER
        context.notifyRoleChanged(localRole)
        context.startAdvertising(context.encodedName())
        logger.info { "Promoted to ROUTER role" }
    }

    override fun retrieveAllConnectedClients(): List<TopologyPeer> {
        return peers.values.toList()
    }
}