package edu.uwm.cs595.goup11.backend.network.payloads

import kotlinx.serialization.Serializable

@Serializable
data class PingPayload(
    val from: String,
    val epoch: Long,
    val hops: Int
)