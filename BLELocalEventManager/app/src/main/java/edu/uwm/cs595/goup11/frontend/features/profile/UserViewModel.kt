package edu.uwm.cs595.goup11.frontend.features.profile

import androidx.lifecycle.ViewModel
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.domain.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
class UserViewModel(): ViewModel(){
    private val _user = MutableStateFlow(User())
    val user: StateFlow<User> = _user.asStateFlow()

    fun updateName(newName: String) {
        if (newName.isBlank()) return
        _user.update { it.copy(username = newName) }
    }

    fun addInterest(interest: String) {
        if (interest.isBlank()) return

        _user.update { current ->
            if (current.interests.contains(interest)) current
            else current.copy(interests = current.interests + interest)
        }
    }

    fun removeInterest(interest: String) {
        _user.update { it.copy(interests = it.interests - interest) }
    }

    fun updateProfileImage(uri: String) {
        _user.update { it.copy(profileImageUri = uri) }
    }

    fun updateRole(newRole: UserRole) {
        _user.update { it.copy(role = newRole) }
    }

}
