package edu.uwm.cs595.goup11.backend.network.payloads

data class TelemetryPayload(
    val from: String,
    val epoch: Long,
    val hops: Int
)
