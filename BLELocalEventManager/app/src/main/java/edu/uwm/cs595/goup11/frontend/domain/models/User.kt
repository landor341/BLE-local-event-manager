// User.kt
// Represents a user in the mesh network.

package edu.uwm.cs595.goup11.frontend.domain.models

import edu.uwm.cs595.goup11.backend.network.UserRole

data class User(
    val id: String = java.util.UUID.randomUUID().toString(),
    val username: String = "User",
    val interests: List<String> = emptyList(),
    val profileImageUri: String? = null,
    val role: UserRole = UserRole.ATTENDEE
)