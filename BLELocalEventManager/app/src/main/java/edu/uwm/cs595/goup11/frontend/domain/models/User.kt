// User.kt
// Represents a user in the mesh network.

package edu.uwm.cs595.goup11.frontend.domain.models

data class User(
    val id: String,
    val username: String,
    val interests: List<String>
)