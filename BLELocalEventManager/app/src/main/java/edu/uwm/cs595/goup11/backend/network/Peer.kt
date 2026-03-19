package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.TopologyPeer
import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy
import java.security.InvalidParameterException

/**
 * Represents a Peer on the network.
 *
 * [endpointId] is the full endpointId string and should be used as an ID to send to
 * [name] is the user chosen name of the endpoint
 * [topologyType] is the topology type of this network represented as a character
 * [role] is the role of this client. This does not matter except for hub-and-spoke topology
 * [eventName] is the name of this event
 */
@Deprecated("This is no longer needed", replaceWith = ReplaceWith("AdvertisedName()"))
data class Peer(

    /**
     * Full ID of the peer
     */
    val endpointId: String,

    /**
     * User chosen name of peer
     */
    val name: String,

    /**
     * Type of topology that this peer is a part of
     */
    val topologyType: String,

    /**
     * Role of that user in the topology
     */
    val role: TopologyStrategy.Role,

    /**
     * Name of the event that it is connected to
     */
    val eventName: String
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

            return Peer(
                endpointId=str,
                name=name,
                topologyType=topologyType,
                role=clientType,
                eventName=eventName
            )
        }

        fun generatePeer(endpointId: String): Peer {
            val regex = Regex("""EVT:([^|]+)\|TOP:([^|]+)\|TYP:([^|]+)\|N:(.+)""")
            val match = regex.find(endpointId)
            if (match != null) {
                val (eventName, topology, type, name) = match.destructured

                // Checks
                val validTopos: Array<String> = arrayOf("hub", "snk", "msh")
                if(!validTopos.contains(topology)) {
                    throw InvalidParameterException("Invalid topology type. Expected one of" +
                            " $validTopos. Got: $topology")
                }

                val validType: Array<String> = arrayOf("l", "r", "a", "p")
                if(!validType.contains(type)) {
                    throw InvalidParameterException("Invalid peer type. Expected one of" +
                            " $validType. Got: $type")
                }

                return Peer(
                    endpointId=endpointId,
                    name=name,
                    topologyType = topology,
                    eventName=eventName,
                    role = when(type) {
                        "l" -> TopologyStrategy.Role.LEAF
                        "r" -> TopologyStrategy.Role.ROUTER
                        else -> TopologyStrategy.Role.PEER
                    }
                )
            } else {
                throw InvalidParameterException("Expected input to match regex: EVT:([^|]+)\\|TOP:([^|]+)\\|TYP:([^|]+)\\|N:(.+)")
            }
        }
    }
}
