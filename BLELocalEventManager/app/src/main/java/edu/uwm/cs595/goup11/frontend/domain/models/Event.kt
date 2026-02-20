// Event.kt
// Represents an event discovered through the mesh network.

package edu.uwm.cs595.goup11.frontend.domain.models


data class Event(
    val id: String,
    val name: String,
    val description: String,
    val hostName: String,
    val participantCount: Int,
    val time: String,
    val location: String
)
