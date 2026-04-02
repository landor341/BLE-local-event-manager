package edu.uwm.cs595.goup11.frontend.core.mesh

import edu.uwm.cs595.goup11.backend.network.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredEventSummary(
    val sessionId:    String,
    val title:        String = sessionId,
    val topologyCode: String = "snk",
    val hostName:     String = "",
    val venue:        String = "Nearby"
)

data class ItineraryItem(
    val id:       String,
    val title:    String,
    val time:     String,
    val location: String,
    val speaker:  String? = null
)

data class JoinedEventBundle(
    val sessionId:   String,
    val title:       String,
    val venue:       String,
    val description: String,
    val itinerary:   List<ItineraryItem>
)

/**
 * A peer currently connected to this node, as seen by the gateway layer.
 * Populated from [NetworkEvent.EndpointConnected] events.
 */
data class GatewayPeer(
    val endpointId:  String,
    val displayName: String,
    val encodedName: String
)

data class ChatMessage(
    val sessionId:   String,
    val sender:      String,
    val senderName:  String = sender,
    /** Role of the sender — frontend-only, not carried in the network Message. */
    val senderRole:  UserRole = UserRole.ATTENDEE,
    val text:        String,
    val timestampMs: Long,
    val isMine:      Boolean
)

sealed class MeshUiState {
    data object Idle        : MeshUiState()
    data object Scanning    : MeshUiState()
    data object Advertising : MeshUiState()
    data class  InEvent(val sessionId: String) : MeshUiState()
    data class  Error(val reason: String)      : MeshUiState()

    // ── Deprecated variants — kept so RealMeshGateway compiles without changes ─
    @Deprecated("Use Scanning") data class Joining(val sessionId: String) : MeshUiState()
    @Deprecated("Use InEvent")  data class Hosting(val sessionId: String) : MeshUiState()
}

/**
 * Topology options exposed to the UI layer.
 * Maps to backend topology strategy codes without leaking backend types.
 */
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
    val myId:  String
    val state: StateFlow<MeshUiState>

    val discoveredEvents: Flow<DiscoveredEventSummary>
    val chat:             Flow<ChatMessage>
    val logs:             Flow<String>

    /** Live list of peers currently connected to this node. */
    val connectedPeers: StateFlow<List<GatewayPeer>>

    /**
     * Update the display name used when hosting or joining events.
     * Must be called before [hostEvent] or [joinEvent].
     */
    fun setDisplayName(name: String)

    suspend fun start()

    suspend fun startScanning()
    suspend fun stopScanning()

    /** Host a new event. Uses SnakeTopology by default. */
    suspend fun hostEvent(eventName: String)

    /** Host a new event with an explicit topology choice. */
    suspend fun hostEvent(eventName: String, topology: TopologyChoice)

    suspend fun joinEvent(sessionId: String): JoinedEventBundle
    suspend fun leaveEvent()

    /** Broadcast a message to all connected peers (topology floods). */
    suspend fun sendChat(text: String)

    /** Send a message directly to a specific peer by their encoded name. */
    suspend fun sendDirectMessage(toEncodedName: String, text: String)
}