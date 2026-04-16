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
        // Load history first to populate initial inbox state
        meshGateway.getChatHistory().forEach { message ->
            if (!message.isBroadcast) {
                // Determine the other person's ID (either sender or recipient)
                val peerId = if (message.isMine) message.recipientId else message.sender
                if (peerId != null) {
                    peerNames.update { it + (peerId to (if (message.isMine) "User ${peerId.take(4)}" else message.senderName)) }
                    lastMessages.update { it + (peerId to message.text) }
                    lastTimestamps.update { it + (peerId to formatTime(message.timestampMs)) }
                }
            }
        }

        meshGateway.chat
            .onEach { message ->
                if (!message.isBroadcast) {
                    val peerId = if (message.isMine) message.recipientId else message.sender
                    if (peerId != null) {
                        peerNames.update { it + (peerId to (if (message.isMine) "User ${peerId.take(4)}" else message.senderName)) }
                        lastMessages.update { it + (peerId to message.text) }
                        lastTimestamps.update { it + (peerId to formatTime(message.timestampMs)) }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    val chatPeers: StateFlow<List<ChatPeer>> = combine(
        peerNames,
        meshGateway.state,
        lastMessages,
        lastTimestamps
    ) { names, state, messages, times ->
        // Convert the maps into a sorted list of unique peers
        names.keys.map { pid ->
            ChatPeer(
                user = User(
                    id = pid,
                    username = names[pid] ?: "User ${pid.take(4)}"
                ),
                lastMessage = messages[pid] ?: "",
                timestamp = times[pid] ?: ""
            )
        }.sortedByDescending { it.timestamp }
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
