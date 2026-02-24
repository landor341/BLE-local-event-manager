package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Peer
interface TopologyStrategy {

    enum class Role { ROUTER, LEAF, PEER }

    val localRole: Role

    val maxPeerCount: Int

    /**
     * Called once when the strategy is attached to a client.
     * Start any background coroutines (keepalive, topology broadcast) here.
     */
    fun start(context: TopologyContext)

    /**
     * Cleanup — cancel coroutines, clear state
     */
    fun stop()

    /**
     * Called immediately after a peer connects. Should trigger a handshake.
     */
    fun onPeerConnected(context: TopologyContext, peer: Peer)

    /**
     * Called when a peer disconnects (either detected by Network or by missed keepalives)
     */
    fun onPeerDisconnected(context: TopologyContext, endpointId: String)

    /**
     * Called when a topology-related message arrives (handshake, ping, pong, advertisement).
     * Returns true if the message was consumed, false if Client should handle it.
     */
    fun onMessage(context: TopologyContext, message: Message): Boolean

    /**
     * Resolve next hops for an outbound message.
     */
    fun resolveNextHop(context: TopologyContext, message: Message): List<String>

    /**
     * Whether this node should currently be advertising.
     */
    fun shouldAdvertise(context: TopologyContext): Boolean
}