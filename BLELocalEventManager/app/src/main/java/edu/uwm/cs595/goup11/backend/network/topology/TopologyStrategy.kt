package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Peer

interface TopologyStrategy {
    /**
     * Called after joining a network. The strategy decides what additional
     * connections to make (e.g. scan and connect to more peers).
     */
    suspend fun onJoined(client: Client, router: Peer)

    /**
     * Called when a new peer connects. The strategy may accept, reject,
     * or re-route based on topology rules.
     */
    fun onPeerConnected(client: Client, peer: Peer)

    /**
     * Called when a peer disconnects. The strategy handles recovery
     * (e.g. healing a broken snake link, finding a new router, etc.)
     */
    fun onPeerDisconnected(client: Client, endpointId: String)

    /**
     * Given an outbound message, return the endpointId(s) it should
     * actually be sent to. This is your layer-3 routing decision.
     *
     * - Snake: forward to next hop
     * - Hub-and-spoke: send to router, or broadcast to leaves
     * - Full mesh: send directly or flood to known peers
     */
    fun resolveNextHop(client: Client, message: Message): List<String>

    /**
     * Whether this node should advertise itself (i.e. accept incoming connections).
     * Routers in hub-and-spoke always return true. Leaves return false.
     */
    fun shouldAdvertise(client: Client): Boolean

    /**
     * Max peers this node should maintain connections to.
     */
    val maxPeerCount: Int
}