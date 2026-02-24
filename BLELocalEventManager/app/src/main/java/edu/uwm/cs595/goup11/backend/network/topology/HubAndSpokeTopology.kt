package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Peer

class HubAndSpokeTopology(override val maxPeerCount: Int) : TopologyStrategy {
    /*
    * Basic Strategy
    * - User who created will always advertise (owner)
    * - Vendors are able to join the networks as hubs (i.e. routers)
    * - If we are a vendor, connect to 2 other vendors, and at max 4 spokes (leafs)
    * - Vendors will always advertise
    *
    * TODO: This can fail quickly if num_spokes / num_hub > 6 as each node can connect to AT MOST 6
    *  - other peers. Potential fix: If a router gets (1 - maxPeerCount) peers, tell 1
    *  - other spoke to become a router. Although this will create its own problems if that router
    *  - leaves
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