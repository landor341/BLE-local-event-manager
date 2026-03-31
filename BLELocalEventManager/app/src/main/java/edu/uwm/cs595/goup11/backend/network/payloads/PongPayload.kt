package edu.uwm.cs595.goup11.backend.network.payloads

data class PongPayload(
    val from: String,
    val epoch: Long,
    val hops: Int
)
