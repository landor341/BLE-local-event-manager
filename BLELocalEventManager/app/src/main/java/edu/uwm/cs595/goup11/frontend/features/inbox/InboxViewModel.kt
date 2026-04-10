package edu.uwm.cs595.goup11.frontend.features.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.domain.models.ChatPeer
import edu.uwm.cs595.goup11.frontend.domain.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InboxViewModel(private val meshGateway: MeshGateway) : ViewModel() {

    private val lastMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    private val lastTimestamps = MutableStateFlow<Map<String, String>>(emptyMap())
    private val peerNames = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        meshGateway.chat
            .onEach { message ->
                peerNames.update { it + (message.sessionId to message.sender) }
                lastMessages.update { it + (message.sessionId to message.text) }
                lastTimestamps.update { it + (message.sessionId to formatTime(message.timestampMs)) }
            }
            .launchIn(viewModelScope)
    }

    val chatPeers: StateFlow<List<ChatPeer>> = combine(
        peerNames,
        meshGateway.state,
        lastMessages,
        lastTimestamps
    ) { names, state, messages, times ->
        when (state) {
            is MeshUiState.InEvent -> {
                val sid = state.sessionId
                listOf(
                    ChatPeer(
                        user = User(
                            id = sid,
                            username = names[sid] ?: "User ${sid.take(4)}"
                        ),
                        lastMessage = messages[sid] ?: "No messages yet",
                        timestamp = times[sid] ?: ""
                    )
                )
            }

            is MeshUiState.Hosting -> {
                val sid = state.sessionId
                listOf(
                    ChatPeer(
                        user = User(
                            id = sid,
                            username = names[sid] ?: "User ${sid.take(4)}"
                        ),
                        lastMessage = messages[sid] ?: "No messages yet",
                        timestamp = times[sid] ?: ""
                    )
                )
            }

            else -> emptyList()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}