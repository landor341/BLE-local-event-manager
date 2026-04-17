package edu.uwm.cs595.goup11.backend.network.payloads

import edu.uwm.cs595.goup11.backend.network.PeerEntry

data class DirectoryPeerAddedPayload(
    val peers: List<PeerEntry>
)
