package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import java.util.concurrent.ConcurrentHashMap

/**
 * Linear chain ("snake") topology strategy.
 *
 * ## Structure
 * Nodes form a line: A ↔ B ↔ C ↔ D …
 * Each node holds at most [maxPeerCount] (default 2) direct connections —
 * one to its left neighbor, one to its right. End-nodes have only one
 * connection and keep advertising so the chain can grow.
 *
 * ## Ring prevention
 * Without extra bookkeeping a ring can form (A↔B↔C↔A), sealing every node
 * at max-peers and permanently closing the network. We prevent this with a
 * **chain-membership set**:
 *
 *  - Every node maintains a [chainMembers] set of all endpoint IDs it knows
 *    are already part of its chain segment.
 *  - When a peer connects, this node broadcasts a [MessageType.HELLO] to all
 *    current neighbors. The HELLO data payload is a comma-separated list of
 *    [chainMembers] so each neighbor can merge the full known set.
 *  - [shouldAcceptConnection] rejects any requester whose ID is already in
 *    [chainMembers], making it impossible for C to close the ring back to A.
 *
 * ## Routing
 * Direct delivery to a known neighbor; otherwise flood to all neighbors
 * except the sender. The TTL field on [Message] prevents infinite loops.
 */
class SnakeTopology(
    override val maxPeerCount: Int = 2,
    private val discoveryIntervalMs: Long = 5_000,
    private val keepaliveIntervalMs: Long = 5_000,
    private val keepaliveTimeoutMs:  Long = 15_000
) : TopologyStrategy {

    override val topologyCode: String = "snk"
    override val localRole: TopologyStrategy.Role = TopologyStrategy.Role.PEER

    private val peers       = ConcurrentHashMap<String, TopologyPeer>()

    /**
     * All endpoint IDs known to be in this node's chain segment, including
     * this node itself and all transitively known members received via HELLO.
     *
     * Used by [shouldAcceptConnection] to prevent ring formation.
     */
    private val chainMembers = ConcurrentHashMap.newKeySet<String>()

    private var keepaliveJob: Job? = null
    private var discoveryJob: Job? = null
    private val logger = KotlinLogging.logger {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun start(context: TopologyContext) {
        // Cleanup
        keepaliveJob?.cancel()
        keepaliveJob = null
        discoveryJob?.cancel()
        discoveryJob = null

        chainMembers.add(context.endpointId)
        startKeepalive(context)
        evaluateHealth(context)
    }

    override fun stop() {
        keepaliveJob?.cancel()
        discoveryJob?.cancel()
        peers.clear()
        chainMembers.clear()
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
                        to   = topoPeer.hardwareId,
                        from = context.endpointId,
                        type = MessageType.PING,
                        ttl  = 1
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Health — drives advertising and discovery
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

        context.startAdvertising(context.encodedName())
        context.startScan()

        discoveryJob = context.launchJob {
            context.events
                .filterIsInstance<NetworkEvent.EndpointDiscovered>()
                .collect { ev ->
                    if (peers.size >= maxPeerCount) {
                        // Slots filled while we were waiting — shut down
                        context.stopScan()
                        context.stopAdvertising()
                        discoveryJob?.cancel()
                        return@collect
                    }

                    val advertisedName = AdvertisedName.decode(ev.encodedName) ?: return@collect

                    // Only connect to nodes on the same event with the same topology
                    if (advertisedName.topologyCode != topologyCode) return@collect

                    // Ring guard — don't connect to known chain members
                    if (chainMembers.contains(ev.endpointId)) return@collect

                    // Tie-breaking: both sides discover each other simultaneously and
                    // would both call connect(), causing STATUS_ENDPOINT_IO_ERROR.
                    // Compare encodedNames — both sides see the other's encodedName in
                    // ev.encodedName, and their own via context.encodedName(). This gives
                    // a consistent ordering in the same namespace on both devices.
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
        // Hard slot limit
        if (peers.size >= maxPeerCount) {
            logger.info { "Rejecting $endpointId — slots full (${peers.size}/$maxPeerCount)" }
            return false
        }

        // Ring guard — reject if the requester is already part of our chain.
        // This prevents A↔B↔C from looping back to A and sealing the network.
        if (chainMembers.contains(endpointId)) {
            logger.info { "Rejecting $endpointId — already a chain member (ring prevention)" }
            return false
        }

        return true
    }

    override fun onPeerConnected(
        context: TopologyContext,
        endpointId: String,
        advertisedName: AdvertisedName
    ) {
        peers[endpointId] = TopologyPeer(
            hardwareId     = endpointId,
            advertisedName = advertisedName
        )

        // Add the new peer to our chain membership knowledge
        chainMembers.add(endpointId)

        logger.info { "Snake peer connected: $endpointId (${peers.size}/$maxPeerCount)" }

        // Broadcast our full chain membership to all neighbors so everyone
        // can update their ring-guard sets. This propagates knowledge of the
        // new member across the whole chain segment.
        broadcastChainMembers(context)

        evaluateHealth(context)
    }

    override fun onPeerDisconnected(context: TopologyContext, endpointId: String) {
        peers.remove(endpointId)

        // When a peer disconnects the chain is broken into two independent
        // segments. We no longer know which members are reachable, so we
        // reset chainMembers to only what we can directly confirm: ourselves
        // and our remaining directly-connected peers.
        //
        // This intentionally allows the broken segment ends to reconnect to
        // nodes that were previously "known" — because those nodes are now
        // in a completely separate segment and the ring risk is gone.
        rebuildChainMembers(context)

        logger.info { "Snake peer disconnected: $endpointId (${peers.size}/$maxPeerCount)" }

        evaluateHealth(context)
    }

    // -------------------------------------------------------------------------
    // Chain membership helpers
    // -------------------------------------------------------------------------

    /**
     * Rebuild [chainMembers] from scratch using only directly confirmed info:
     * ourselves + our current direct peers.
     *
     * Called after a disconnect so we don't permanently block reconnection
     * to nodes that are now in a separate chain segment.
     */
    private fun rebuildChainMembers(context: TopologyContext) {
        chainMembers.clear()
        chainMembers.add(context.endpointId)
        chainMembers.addAll(peers.keys)
    }

    /**
     * Flood a [MessageType.HELLO] to all current peers carrying our full
     * [chainMembers] set as a comma-separated UTF-8 data payload.
     *
     * Receiving nodes merge this into their own [chainMembers] via [onMessage].
     */
    private fun broadcastChainMembers(context: TopologyContext) {
        if (peers.isEmpty()) return
        val memberList = chainMembers.joinToString(",")
        peers.values.forEach { peer ->
            context.sendMessage(
                peer,
                Message(
                    to   = peer.hardwareId,
                    from = context.endpointId,
                    type = MessageType.HELLO,
                    ttl  = maxPeerCount + 1,   // enough hops to traverse the whole chain
                    data = memberList.toByteArray(Charsets.UTF_8)
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    override fun onMessage(context: TopologyContext, message: Message): Boolean {
        // peers is keyed by hardware endpoint ID (the Nearby-assigned short string).
        // message.from carries the sender's encoded name — look up the peer directly.
        val senderPeer = peers[message.from]  // works when from == hardwareId (forwarded msgs)
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
                        to      = peer.hardwareId,
                        from    = context.endpointId,
                        type    = MessageType.PONG,
                        replyTo = message.id,
                        ttl     = 1
                    )
                )
                true
            }

            MessageType.PONG -> {
                senderPeer?.lastPongAt = System.currentTimeMillis()
                true
            }

            MessageType.HELLO -> {
                val newMembers = message.data
                    ?.toString(Charsets.UTF_8)
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                val addedAny = chainMembers.addAll(newMembers)

                if (addedAny) {
                    val memberList = chainMembers.joinToString(",")
                    peers.values
                        .filter { it != senderPeer }
                        .forEach { peer ->
                            context.sendMessage(
                                peer,
                                Message(
                                    to   = peer.hardwareId,
                                    from = context.endpointId,
                                    type = MessageType.HELLO,
                                    ttl  = (message.ttl - 1).coerceAtLeast(0),
                                    data = memberList.toByteArray(Charsets.UTF_8)
                                )
                            )
                        }
                }
                true
            }

            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    override fun resolveNextHop(context: TopologyContext, message: Message): List<String> {
        // message.to is the peer's encoded name (logical address).
        // Find the peer whose advertisedName matches, then use their hardwareId.
        val destPeer = peers.values.find { it.advertisedName.encode() == message.to }
            ?: peers[message.to]  // fallback: message.to is already a hardware ID

        if (destPeer != null) {
            return listOf(destPeer.hardwareId)
        }

        // No direct route — flood to all peers except the sender
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
        return peers.size < maxPeerCount
    }

    override suspend fun disconnectFromAllNodes(context: TopologyContext) {
        // Disconnect from all peers
        peers.keys.forEach { endpointId ->
            context.disconnect(endpointId)
        }
    }
}