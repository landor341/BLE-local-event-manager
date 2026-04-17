package edu.uwm.cs595.goup11.frontend.features.connectedusers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.domain.models.User
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ConnectedUsersViewModel(
    private val mesh: MeshGateway
) : ViewModel() {

    val users: StateFlow<List<ConnectedUserUi>> = mesh.connectedPeers
        .map { peers ->
            peers
                .distinctBy { it.peerId }
                .map { peer ->
                    ConnectedUserUi(
                        user = User(
                            id = peer.peerId,
                            username = peer.displayName.ifBlank { "Unknown user" },
                            role = UserRole.ATTENDEE
                        ),
                        status = PeerStatus.CONNECTED
                    )
                }
                .sortedBy { it.user.username.lowercase() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
}