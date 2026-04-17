package edu.uwm.cs595.goup11.frontend.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.ChatMessage
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val mesh: MeshGateway,
    private val peerId: String
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    init {
        val isEventChat = peerId == "router" || peerId == "ALL"

        // Load session history and filter correctly
        val history = mesh.getChatHistory().filter { msg ->
            if (isEventChat) {
                // Event chat: only show broadcasts
                msg.isBroadcast
            } else {

                // Direct chat: only show private messages between me and this specific peer
                !msg.isBroadcast && (msg.sender == peerId || (msg.isMine && msg.recipientId == peerId))
            }
        }
        _messages.value = history

        viewModelScope.launch {
            mesh.chat.collect { msg ->
                val isRelevant = if (isEventChat) {
                    msg.isBroadcast
                } else {
                    !msg.isBroadcast && (msg.sender == peerId || (msg.isMine && msg.recipientId == peerId))
                }

                if (isRelevant) {
                    // Avoid double-loading
                    if (_messages.value.none { it.timestampMs == msg.timestampMs && it.text == msg.text }) {
                        _messages.value = _messages.value + msg
                    }
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return

        val currentState = mesh.state.value
        if (currentState !is MeshUiState.InEvent && 
            @Suppress("DEPRECATION") (currentState !is MeshUiState.Hosting)) {
            return
        }

        viewModelScope.launch {
            if (peerId == "router" || peerId == "ALL") {
                mesh.sendChat(text)
            } else {
                mesh.sendDirectMessage(peerId, text)
            }
        }
    }
}
