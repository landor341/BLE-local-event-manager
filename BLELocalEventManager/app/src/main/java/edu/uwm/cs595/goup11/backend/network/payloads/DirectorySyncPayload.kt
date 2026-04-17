package edu.uwm.cs595.goup11.backend.network.payloads

import edu.uwm.cs595.goup11.backend.network.PeerEntry
import kotlinx.serialization.Serializable

@Serializable
data class DirectorySyncPayload(
    val peers: List<PeerEntry>
)
