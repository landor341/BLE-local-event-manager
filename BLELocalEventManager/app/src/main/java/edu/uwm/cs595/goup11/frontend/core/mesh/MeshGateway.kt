package edu.uwm.cs595.goup11.frontend.core.mesh

import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredEventSummary(
    val sessionId: String,
    val title: String = sessionId,
    val topologyCode: String = "snk",
    val hostName: String = "",
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

/**
 * A peer currently connected to this node, as seen by the gateway layer.
 */
data class GatewayPeer(
    val endpointId: String,
    val displayName: String,
    val encodedName: String
)

data class ChatMessage(
    val sessionId: String,
    val sender: String,
    val senderName: String = sender,
    val senderRole: UserRole = UserRole.ATTENDEE,
    val text: String,
    val timestampMs: Long,
    val isMine: Boolean,
    val isBroadcast: Boolean = false,
    val recipientId: String? = null
)

sealed class MeshUiState {
    data object Idle : MeshUiState()
    data object Scanning : MeshUiState()
    data object Advertising : MeshUiState()
    data class InEvent(val sessionId: String) : MeshUiState()
    data class Error(val reason: String) : MeshUiState()

    /** Joined and advertising/discovering, but no peers connected yet. */
    data class WaitingForPeers(val sessionId: String) : MeshUiState()

    @Deprecated("Use Scanning")
    data class Joining(val sessionId: String) : MeshUiState()
    @Deprecated("Use InEvent")
    data class Hosting(val sessionId: String) : MeshUiState()
}

/** Granular connection lifecycle events emitted during mesh negotiation. */
sealed class ConnectionEvent {
    data class DeviceFound(val deviceName: String) : ConnectionEvent()
    data class Connecting(val deviceName: String) : ConnectionEvent()
    data class Connected(val deviceName: String) : ConnectionEvent()
    data class DeviceLost(val deviceName: String) : ConnectionEvent()
    data class Rejected(val deviceName: String) : ConnectionEvent()
}

enum class TopologyChoice(val code: String) {
    SNAKE("snk"),
    MESH("msh"),
    HUB_AND_SPOKE("hub")
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
    val logs: Flow<String>

    /** Live list of peers currently connected to this node. */
    val connectedPeers: StateFlow<List<GatewayPeer>>

    /**
     * Granular connection lifecycle events — device found, connecting, connected, etc.
     * Use this to drive a live status UI during the mesh negotiation phase.
     */
    val connectionEvents: Flow<ConnectionEvent>

    /** True if this node created (hosted) the current event. False if it joined. */
    val isHost: StateFlow<Boolean>

    /**
     * Live list of presentations synced across the mesh.
     * Maps backend [PresentationEntry] to the frontend [Presentation] UI model.
     */
    val presentations: StateFlow<List<Presentation>>

    fun setDisplayName(name: String)

    fun getChatHistory(): List<ChatMessage>

    suspend fun start()

    suspend fun startScanning()
    suspend fun stopScanning()

    suspend fun hostEvent(eventName: String)
    suspend fun hostEvent(eventName: String, topology: TopologyChoice)

    suspend fun joinEvent(sessionId: String): JoinedEventBundle
    suspend fun leaveEvent()

    suspend fun sendChat(text: String)
    suspend fun sendDirectMessage(toEncodedName: String, text: String)

    suspend fun addItineraryItem(item: ItineraryItem)

    /** Add a presentation to the synced collection. Broadcasts to all peers. */
    fun addPresentation(presentation: Presentation)

    /** Remove a presentation from the synced collection. Broadcasts to all peers. */
    fun removePresentation(presentation: Presentation)

    /** Update an existing presentation. Broadcasts to all peers. */
    fun updatePresentation(presentation: Presentation)
}