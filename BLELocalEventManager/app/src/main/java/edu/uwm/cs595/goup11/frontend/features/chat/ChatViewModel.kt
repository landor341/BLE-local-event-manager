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
        // Load session history from the gateway and filter for relevance
        val history = mesh.getChatHistory().filter { msg ->
            when {
                // If it's a direct message chat, match the peerId
                msg.sender == peerId -> true
                msg.isMine && msg.sender == peerId -> true 
                
                // If it's an event-wide chat, include all session messages
                peerId == "router" || peerId == "ALL" -> true
                
                else -> false
            }
        }
        _messages.value = history

        viewModelScope.launch {
            mesh.chat.collect { msg ->
                val isRelevant = when {
                    msg.sender == peerId -> true
                    msg.isMine && msg.sender == peerId -> true
                    peerId == "router" || peerId == "ALL" -> true
                    else -> false
                }

                if (isRelevant) {
                    // Check if message is already in history to avoid double-loading on init
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
