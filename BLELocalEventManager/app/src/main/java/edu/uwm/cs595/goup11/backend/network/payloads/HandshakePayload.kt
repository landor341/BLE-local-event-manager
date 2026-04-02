package edu.uwm.cs595.goup11.backend.network.payloads

import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy
import kotlinx.serialization.Serializable

@Serializable
data class HandshakePayload(
    val role: TopologyStrategy.Role,
    val connectedPeerCount: Int,
    val maxPeerCount: Int
)
