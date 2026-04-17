package edu.uwm.cs595.goup11.frontend.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.ChatMessage
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val mesh: MeshGateway,
    private val initialPeerId: String
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var activePeerId: String = initialPeerId
    private var chatCollectionJob: Job? = null

    init {
        bindConversation(initialPeerId)
    }

    fun bindConversation(peerId: String) {
        activePeerId = peerId
        _messages.value = filteredHistory(peerId)

        if (chatCollectionJob == null) {
            chatCollectionJob = viewModelScope.launch {
                mesh.chat.collect { incoming ->
                    if (isRelevantToConversation(incoming, activePeerId)) {
                        appendIfMissing(incoming)
                    }
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return

        val currentState = mesh.state.value
        if (currentState !is MeshUiState.InEvent &&
            @Suppress("DEPRECATION")
            (currentState !is MeshUiState.Hosting)
        ) {
            return
        }

        val targetPeerId = activePeerId

        viewModelScope.launch {
            if (isEventChat(targetPeerId)) {
                mesh.sendChat(text)
            } else {
                mesh.sendDirectMessage(targetPeerId, text)
            }
        }
    }

    private fun filteredHistory(peerId: String): List<ChatMessage> {
        return mesh.getChatHistory()
            .filter { isRelevantToConversation(it, peerId) }
            .distinctBy { messageIdentity(it) }
            .sortedBy { it.timestampMs }
    }

    private fun isRelevantToConversation(message: ChatMessage, peerId: String): Boolean {
        return if (isEventChat(peerId)) {
            message.isBroadcast
        } else {
            !message.isBroadcast && (
                    message.sender == peerId || message.recipientId == peerId
                    )
        }
    }

    private fun isEventChat(peerId: String): Boolean {
        return peerId == "router" || peerId == "ALL"
    }

    private fun appendIfMissing(message: ChatMessage) {
        val key = messageIdentity(message)
        if (_messages.value.none { messageIdentity(it) == key }) {
            _messages.value = (_messages.value + message).sortedBy { it.timestampMs }
        }
    }

    private fun messageIdentity(message: ChatMessage): String {
        return listOf(
            message.sessionId,
            message.sender,
            message.recipientId.orEmpty(),
            message.timestampMs.toString(),
            message.text,
            message.isBroadcast.toString(),
            message.isMine.toString()
        ).joinToString("|")
    }
}