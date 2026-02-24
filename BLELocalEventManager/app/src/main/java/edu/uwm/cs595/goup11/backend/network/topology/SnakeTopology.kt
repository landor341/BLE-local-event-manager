package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Peer

class SnakeTopology: TopologyStrategy {
    override val maxPeerCount = 2

    override suspend fun onJoined(client: Client, router: Peer) {
        // The router we joined through is our "left" neighbor.
        // Scan for one more peer to be our "right" neighbor.
    }

    override fun resolveNextHop(client: Client, message: Message): List<String> {
        // If target is in our peer list, send direct.
        // Otherwise forward to whichever neighbor is "toward" the target.
        // Since you don't have global topology knowledge, flood to both neighbors
        // and rely on TTL/visited-set to prevent loops.
        return client.attachedPeers
            .filter { it.endpointId != message.from } // don't send back
            .map { it.endpointId }
    }

    override fun onPeerDisconnected(client: Client, endpointId: String) {
        // Snake is broken — attempt to re-scan and reconnect to heal the chain
    }

    override fun shouldAdvertise(client: Client) = client.attachedPeers.size < maxPeerCount
    override fun onPeerConnected(client: Client, peer: Peer) = Unit
}