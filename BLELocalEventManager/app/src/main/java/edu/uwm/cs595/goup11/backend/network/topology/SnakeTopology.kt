import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.Peer
import edu.uwm.cs595.goup11.backend.network.topology.TopologyContext
import edu.uwm.cs595.goup11.backend.network.topology.TopologyPeer
import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class SnakeTopology(
    override val maxPeerCount: Int = 2,
    private val discoveryIntervalMs: Long = 5_000,
    private val keepaliveIntervalMs: Long = 5_000,
    private val keepaliveTimeoutMs: Long = 15_000
) : TopologyStrategy {

    override val localRole: TopologyStrategy.Role = TopologyStrategy.Role.PEER // Everyone is equal in a snake

    private val peers = ConcurrentHashMap<String, TopologyPeer>()
    private var keepaliveJob: Job? = null
    private var discoveryJob: Job? = null
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
            while(true) {
                delay(keepaliveIntervalMs)
                tickKeepalive(context)
            }
        }
    }

    private fun tickKeepalive(context: TopologyContext) {
        val now = System.currentTimeMillis()

        peers.values.forEach { topoPeer ->
            val timeSinceLastPong = now - topoPeer.lastPongAt

            if(timeSinceLastPong > keepaliveTimeoutMs) {
                logger.warn { "Peer ${topoPeer.peer.endpointId} timed out" }
                onPeerDisconnected(context, topoPeer.peer.endpointId)
            } else {
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
    }

    // -------------------------------------------------------------------------
    // Health check — the core of the snake logic
    // -------------------------------------------------------------------------

    private fun evaluateHealth(context: TopologyContext) {
        val currentCount = peers.size

        if(currentCount < maxPeerCount) {
            logger.info { "Below max peers ($currentCount/$maxPeerCount), starting discovery" }
            startDiscovery(context)
        } else {
            logger.info { "At max peers, stopping discovery" }
            stopDiscovery(context)
        }
    }

    private fun startDiscovery(context: TopologyContext) {
        // Don't start a second loop if one is already running
        if(discoveryJob?.isActive == true) return

        discoveryJob = context.launchJob {
            while(peers.size < maxPeerCount) {
                context.startAdvertising()
                context.startScan()

                delay(discoveryIntervalMs)

                // If we still haven't found anyone, stop and retry next interval
                context.stopScan()
            }

            // Reached max peers — clean up
            context.stopScan()
            context.stopAdvertising()
            logger.info { "Snake slot filled, discovery stopped" }
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

    override fun onPeerConnected(context: TopologyContext, peer: Peer) {
        val info = TopologyPeer.decodeToTopologyPeer(peer.name)

        peers[peer.endpointId] = info

        logger.info { "Snake peer connected: ${peer.endpointId} (${peers.size}/$maxPeerCount)" }

        evaluateHealth(context)
    }

    override fun onPeerDisconnected(context: TopologyContext, endpointId: String) {
        peers.remove(endpointId)

        logger.info { "Snake peer disconnected: $endpointId (${peers.size}/$maxPeerCount)" }

        // Link is broken — start looking for a new neighbor
        evaluateHealth(context)
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    override fun onMessage(context: TopologyContext, message: Message): Boolean {
        return when(message.type) {
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
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    override fun resolveNextHop(context: TopologyContext, message: Message): List<String> {
        // Direct delivery
        if(peers.containsKey(message.to)) {
            return listOf(message.to)
        }

        // Forward to all neighbors except whoever sent it (to avoid bouncing)
        return peers.keys
            .filter { it != message.from }
            .also {
                if(it.isEmpty()) logger.warn { "No route found for message to ${message.to}" }
            }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    override fun shouldAdvertise(context: TopologyContext): Boolean {
        return peers.size < maxPeerCount
    }
}