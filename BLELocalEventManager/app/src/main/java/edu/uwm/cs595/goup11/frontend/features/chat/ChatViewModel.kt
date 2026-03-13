package edu.uwm.cs595.goup11.frontend.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.ChatMessage
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
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
        // Collect incoming chat stream
        viewModelScope.launch {
            mesh.chat.collect { msg ->
                if (msg.sender == peerId || (msg.isMine && msg.sessionId == peerId)) {
                    _messages.value = _messages.value + msg
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            mesh.sendChat(text)
        }
    }
}