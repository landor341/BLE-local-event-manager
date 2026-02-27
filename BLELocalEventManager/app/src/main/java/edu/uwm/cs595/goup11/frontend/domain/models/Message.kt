// Message.kt
// Represents a chat message sent over the mesh network.

package edu.uwm.cs595.goup11.frontend.domain.models



data class Message(
    val id: String,
    val name: String,
    val text: String,
    val isFromMe: Boolean
)