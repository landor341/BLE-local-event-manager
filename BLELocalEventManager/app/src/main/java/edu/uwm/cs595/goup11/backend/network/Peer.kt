package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy

/**
 * Represents a Peer on the network.
 */
data class Peer(
    val endpointId: String,
    val name: String
) {

    companion object {
        fun generatePeer(eventName: String, topologyType: String, clientType: TopologyStrategy.Role, name: String): Peer {
            val str = "EVT:$eventName|TOP:$topologyType|TYP:${
                when(clientType) {
                    TopologyStrategy.Role.PEER -> "p"
                    TopologyStrategy.Role.LEAF -> "l"
                    TopologyStrategy.Role.ROUTER -> "r"
                }
            }|N:$name"

            return Peer(str, str)
        }
    }
}
