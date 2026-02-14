package edu.uwm.cs595.goup11.backend.network

data class Message(
    val to: String,
    val from: String,
    val type: MessageType,
    val data: ByteArray,
    val ttl: Int
)
