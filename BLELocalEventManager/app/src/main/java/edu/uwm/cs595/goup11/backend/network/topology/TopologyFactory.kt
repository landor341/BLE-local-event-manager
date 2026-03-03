package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName

object TopologyFactory {
    fun create(advertisedName: AdvertisedName): TopologyStrategy {
        return when (advertisedName.topologyCode.lowercase()) {
            "snk" -> SnakeTopology()
            "hub" -> HubAndSpokeTopology(initialRole = TopologyStrategy.Role.LEAF)
            "msh" -> MeshTopology()
            else  -> throw IllegalArgumentException(
                "Unknown topology code: ${advertisedName.topologyCode}"
            )
        }
    }
}