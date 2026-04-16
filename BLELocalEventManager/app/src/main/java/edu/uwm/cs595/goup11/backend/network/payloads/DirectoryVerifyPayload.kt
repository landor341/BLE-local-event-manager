package edu.uwm.cs595.goup11.backend.network.payloads

import edu.uwm.cs595.goup11.backend.network.PeerEntry
import kotlinx.serialization.Serializable


@Serializable
data class DirectoryVerifyPayload(
    val hash: String
)

@Serializable
data class DirectoryVerifyAckPayload(
    val status: VerifyStatus,
    val peers: List<PeerEntry> = emptyList()
)

@Serializable
enum class VerifyStatus {OK, MISMATCH}
