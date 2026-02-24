package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Peer
import java.security.InvalidParameterException

data class TopologyPeer(
    val peer: Peer,
    var name: String,
    var role: TopologyStrategy.Role = TopologyStrategy.Role.PEER,
    var eventName: String,
    var topologyType: String,
    var connectedPeerCount: Int = 0,
    var maxPeerCount: Int = 0,
    var handshakeComplete: Boolean = false,
    var lastPongAt: Long = System.currentTimeMillis(),  // for keepalive tracking
    val pendingMessages: ArrayDeque<Message> = ArrayDeque() // queued until handshake done
) {

    fun encodeToEndpointId(): String {
        return "EVE:$eventName|TOP:$topologyType|TYP:$role|N:$name"
    }

    companion object {
        fun decodeToTopologyPeer(endpointName: String): TopologyPeer {
            val regex = Regex("""EVT:([^|]+)\|TOP:([^|]+)\|TYP:([^|]+)\|N:(.+)""")
            val match = regex.find(endpointName)
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

                return TopologyPeer(
                    peer=Peer(endpointName, endpointName),
                    name=name,
                    role = when(type) {
                        "r" -> TopologyStrategy.Role.ROUTER
                        "l" -> TopologyStrategy.Role.LEAF
                        "a" -> TopologyStrategy.Role.PEER
                        "p" -> TopologyStrategy.Role.PEER
                        else -> TopologyStrategy.Role.PEER
                    },
                    eventName=eventName,
                    topologyType = topology
                    )
            } else {
                throw InvalidParameterException("Expected input to match regex: EVT:([^|]+)\\|TOP:([^|]+)\\|TYP:([^|]+)\\|N:(.+)")
            }

        }

        private fun parseFieldPipeColon(raw: String, key: String): String? {
            //TODO: This should probably be in its own Object so all methods and classes can use it
            val token = "$key:"
            val start = raw.indexOf(token)
            if (start == -1) return null

            val after = start + token.length
            val end = raw.indexOf('|', after).let { if (it == -1) raw.length else it }

            val value = raw.substring(after, end).trim()
            return value.takeIf { it.isNotEmpty() }
        }

        fun createTopologyPeer() {

        }
    }
}