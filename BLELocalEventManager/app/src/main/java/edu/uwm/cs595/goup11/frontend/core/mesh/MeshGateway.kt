package edu.uwm.cs595.goup11.frontend.core.mesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredEventSummary(
    val sessionId: String,
    val title: String = sessionId,
    val venue: String = "Nearby"
)

data class ItineraryItem(
    val id: String,
    val title: String,
    val time: String,
    val location: String,
    val speaker: String? = null
)

data class JoinedEventBundle(
    val sessionId: String,
    val title: String,
    val venue: String,
    val description: String,
    val itinerary: List<ItineraryItem>
)

data class ChatMessage(
    val sessionId: String,
    val sender: String,
    val text: String,
    val timestampMs: Long,
    val isMine: Boolean
)

sealed class MeshUiState {
    data object Idle : MeshUiState()
    data object Scanning : MeshUiState()
    data class Joining(val sessionId: String) : MeshUiState()
    data class InEvent(val sessionId: String) : MeshUiState()
    data class Hosting(val sessionId: String) : MeshUiState()
    data class Error(val reason: String) : MeshUiState()
}

/**
 * UI CONTRACT.
 * Frontend features/ViewModels MUST ONLY depend on this interface.
 * backend/network types MUST NOT leak past the gateway/facade.
 */
interface MeshGateway {
    val myId: String
    val state: StateFlow<MeshUiState>

    val discoveredEvents: Flow<DiscoveredEventSummary>
    val chat: Flow<ChatMessage>

    /** Stream of log messages for developer/debug monitoring. */
    val logs: Flow<String>

    suspend fun start()
    suspend fun startScanning()
    suspend fun stopScanning()

    suspend fun hostEvent(eventName: String)
    suspend fun joinEvent(sessionId: String): JoinedEventBundle
    suspend fun leaveEvent()

    suspend fun sendChat(text: String)
}