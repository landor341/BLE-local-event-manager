package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.Peer
import edu.uwm.cs595.goup11.backend.network.payloads.HandshakePayload
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class HubAndSpokeTopology (
    override val maxPeerCount: Int = 7, // 5 leaves + 2 router links
    private val maxLeaves: Int = 5,
    private val maxRouterLinks: Int = 2,
    private val keepaliveIntervalMs: Long = 5_000,
    private val keepaliveTimeoutMs: Long = 15_000
) : TopologyStrategy {

    override var localRole: TopologyStrategy.Role = TopologyStrategy.Role.LEAF
        private set

    private val peers = ConcurrentHashMap<String, TopologyPeer>()
    private var keepaliveJob: Job? = null
    private var context: TopologyContext? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun start(context: TopologyContext) {
        this.context = context
        startKeepalive(context)
    }

    override fun stop() {
        keepaliveJob?.cancel()
        peers.clear()
        context = null
    }

    // -------------------------------------------------------------------------
    // Keepalive loop
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
            if (!topoPeer.handshakeComplete) return@forEach

            val timeSinceLastPong = now - topoPeer.lastPongAt

            if (timeSinceLastPong > keepaliveTimeoutMs) {
                // Peer is dead — treat as disconnected
                logger.warn { "Peer ${topoPeer.peer.endpointId} timed out, removing" }
                onPeerDisconnected(context, topoPeer.peer.endpointId)
            } else {
                // Send a ping
                context.sendMessage(
                    topoPeer.peer.endpointId,
                    Message(
                        to = topoPeer.peer.endpointId,
                        from = context.localId,
                        type = MessageType.PING,
                        ttl = 1
                    )
                )
            }
        }

        // Re-evaluate advertising after each keepalive tick
        if (shouldAdvertise(context)) context.startAdvertising()
        else context.stopAdvertising()
    }

    // -------------------------------------------------------------------------
    // Connection events
    // -------------------------------------------------------------------------

    override fun onPeerConnected(context: TopologyContext, peer: Peer) {
        // Register peer as unknown until handshake completes
        peers[peer.endpointId] = TopologyPeer(peer = peer)

        // Immediately send our handshake
        context.sendMessage(
            peer.endpointId,
            Message(
                to = peer.endpointId,
                from = context.localId,
                type = MessageType.HANDSHAKE,
                ttl = 1,
                data = Json.encodeToString(
                    HandshakePayload(
                        role = localRole,
                        connectedPeerCount = peers.size,
                        maxPeerCount = maxPeerCount
                    )
                )
            )
        )
    }

    override fun onPeerDisconnected(context: TopologyContext, endpointId: String) {
        val topoPeer = peers.remove(endpointId) ?: return

        when (topoPeer.role) {
            TopologyStrategy.Role.ROUTER -> {
                if (localRole == TopologyStrategy.Role.LEAF) {
                    // Lost our router — scan for a new one
                    logger.info { "Lost router $endpointId, need to find a new one" }
                    // TODO: trigger re-scan via context
                }
            }
            TopologyStrategy.Role.LEAF -> {
                // A leaf left, free up a slot — nothing special needed
            }
            else -> Unit
        }

        // Re-evaluate advertising
        if (shouldAdvertise(context)) context.startAdvertising()
        else context.stopAdvertising()
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    override fun onMessage(context: TopologyContext, message: Message): Boolean {
        return when (message.type) {
            MessageType.HANDSHAKE -> {
                handleHandshake(context, message)
                true
            }
            MessageType.PING -> {
                context.sendMessage(
                    message.from,
                    Message(
                        to = message.from,
                        from = context.localId,
                        type = MessageType.PONG,
                        replyTo = message.id,
                        ttl = 1
                    )
                )
                true
            }
            MessageType.PONG -> {
                peers[message.from]?.lastPongAt = System.currentTimeMillis()
                true
            }
            MessageType.DIRECTORY_SNAPSHOT -> {
                handleAdvertisement(context, message)
                true
            }
            else -> false // let Client handle it
        }
    }

    private fun handleHandshake(context: TopologyContext, message: Message) {
        val payload = Json.decodeFromString<HandshakePayload>(message.payload ?: return)
        val topoPeer = peers[message.from] ?: return

        topoPeer.role = payload.role
        topoPeer.connectedPeerCount = payload.connectedPeerCount
        topoPeer.maxPeerCount = payload.maxPeerCount
        topoPeer.handshakeComplete = true
        topoPeer.lastPongAt = System.currentTimeMillis()

        // Role negotiation
        if (payload.role == TopologyStrategy.Role.ROUTER) {
            val routerLinks = peers.values.count { it.role == TopologyStrategy.Role.ROUTER }
            if (routerLinks > maxRouterLinks) {
                // Too many router links — disconnect from this one
                // TODO: context.disconnect(message.from)
                return
            }
        }

        // If the router we just connected to is full on leaves, consider
        // volunteering as a router ourselves
        if (payload.role == TopologyStrategy.Role.ROUTER
            && payload.connectedPeerCount >= payload.maxPeerCount
            && localRole == TopologyStrategy.Role.LEAF
        ) {
            promoteToRouter(context)
        }

        // Flush any queued messages now that handshake is done
        topoPeer.pendingMessages.forEach { queued ->
            context.sendMessage(topoPeer.peer.endpointId, queued)
        }
        topoPeer.pendingMessages.clear()

        // Update advertising state
        if (shouldAdvertise(context)) context.startAdvertising()
        else context.stopAdvertising()
    }

    private fun handleAdvertisement(context: TopologyContext, message: Message) {
        // TODO: merge into network map for link-state routing
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    override fun resolveNextHop(context: TopologyContext, message: Message): List<String> {
        // Direct delivery if peer is connected to us
        val direct = peers[message.to]
        if (direct != null && direct.handshakeComplete) {
            return listOf(direct.peer.endpointId)
        }

        return when (localRole) {
            TopologyStrategy.Role.ROUTER -> {
                // Forward to peer routers to find the destination
                peers.values
                    .filter { it.role == TopologyStrategy.Role.ROUTER && it.handshakeComplete }
                    .map { it.peer.endpointId }
            }
            TopologyStrategy.Role.LEAF -> {
                // Always send up to our router
                peers.values
                    .filter { it.role == TopologyStrategy.Role.ROUTER && it.handshakeComplete }
                    .map { it.peer.endpointId }
                    .take(1)
            }
            else -> emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    override fun shouldAdvertise(context: TopologyContext): Boolean {
        return when (localRole) {
            TopologyStrategy.Role.ROUTER -> {
                val leafCount = peers.values.count { it.role == TopologyStrategy.Role.LEAF }
                leafCount < maxLeaves
            }
            TopologyStrategy.Role.LEAF -> false
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Role management
    // -------------------------------------------------------------------------

    private fun promoteToRouter(context: TopologyContext) {
        localRole = TopologyStrategy.Role.ROUTER
        context.notifyRoleChanged(localRole)
        context.startAdvertising()
        logger.info { "Promoted to ROUTER role" }
    }

    private val logger = KotlinLogging.logger {}
}