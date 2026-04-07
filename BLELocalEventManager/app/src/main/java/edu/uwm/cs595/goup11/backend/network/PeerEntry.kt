package edu.uwm.cs595.goup11.backend.network

data class PeerEntry(
    val endpointId: String,
    val displayName: String,
    val joinTimestamp: Long,
    val lamportClock: Long,
    val status: PeerStatus,
)

enum class PeerStatus {
    ACTIVE,
    DISCONNECTED
}
