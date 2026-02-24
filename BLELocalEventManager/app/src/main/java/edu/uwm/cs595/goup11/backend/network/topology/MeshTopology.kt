package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Peer

class MeshTopology(override val maxPeerCount: Int) : TopologyStrategy {
    /*
     * Basic Strategy
     * - User who created will always advertise (owner)
     * - When a user attempts to join, it will also find 2 other advertisers and join them
     * - When we hit our max peer count, we stop advertising
     * - If a leaves our node, automatically connect to 1 other node that is advertising
     *
     * TODO: This can create islands if we are not careful, will need to figure out how to fix that
     *  - Potential fix: If we can reach the owner we are safe, otherwise we created an island,
     *  - attempt to fix by asking owner for 1 node to connect to
     */

    override suspend fun onJoined(
        client: Client,
        router: Peer
    ) {
        TODO("Not yet implemented")
    }

    override fun onPeerConnected(
        client: Client,
        peer: Peer
    ) {
        TODO("Not yet implemented")
    }

    override fun onPeerDisconnected(
        client: Client,
        endpointId: String
    ) {
        TODO("Not yet implemented")
    }

    override fun resolveNextHop(
        client: Client,
        message: Message
    ): List<String> {
        TODO("Not yet implemented")
    }

    override fun shouldAdvertise(client: Client): Boolean {
        TODO("Not yet implemented")
    }
}