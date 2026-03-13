package edu.uwm.cs595.goup11.frontend.domain.models

//
data class ChatPeer(
    val user: User,
    val lastMessage: String = "",
    val timestamp: String = "",
    val isOnline: Boolean = true,
    val rssi: Int = -50
)