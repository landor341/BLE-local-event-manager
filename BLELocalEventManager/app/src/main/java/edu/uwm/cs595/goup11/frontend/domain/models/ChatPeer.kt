package edu.uwm.cs595.goup11.frontend.domain.models

data class ChatPeer(
    val id: String,
    val displayName: String,
    val lastMessage: String = "",
    val timestamp: String = "",
    val isOnline: Boolean = true,
    val rssi: Int = -50
)