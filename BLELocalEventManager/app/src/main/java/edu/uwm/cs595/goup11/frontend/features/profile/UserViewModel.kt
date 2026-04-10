package edu.uwm.cs595.goup11.frontend.features.profile

import androidx.lifecycle.ViewModel
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.domain.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class UserViewModel : ViewModel() {

    private val _user = MutableStateFlow(User())
    val user: StateFlow<User> = _user.asStateFlow()

    fun updateName(newName: String) {
        _user.update { it.copy(username = newName) }
    }

    fun addInterest(interest: String) {
        val trimmed = interest.trim()
        if (trimmed.isBlank()) return

        _user.update { current ->
            if (current.interests.any { it.equals(trimmed, ignoreCase = true) }) {
                current
            } else {
                current.copy(interests = current.interests + trimmed)
            }
        }
    }

    fun removeInterest(interest: String) {
        _user.update { current ->
            current.copy(
                interests = current.interests.filterNot { it == interest }
            )
        }
    }

    fun updateProfileImage(uri: String) {
        _user.update { it.copy(profileImageUri = uri) }
    }

    fun updateRole(newRole: UserRole) {
        _user.update { it.copy(role = newRole) }
    }

    fun setUser(
        username: String = _user.value.username,
        interests: List<String> = _user.value.interests,
        profileImageUri: String? = _user.value.profileImageUri,
        role: UserRole = _user.value.role
    ) {
        _user.update {
            it.copy(
                username = username,
                interests = interests.distinct(),
                profileImageUri = profileImageUri,
                role = role
            )
        }
    }

    fun clearUser() {
        _user.value = User()
    }
}