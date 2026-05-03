package edu.uwm.cs595.goup11.frontend.features.eventdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.ConnectionEvent
import edu.uwm.cs595.goup11.frontend.core.mesh.GatewayPeer
import edu.uwm.cs595.goup11.frontend.core.mesh.JoinedEventBundle
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class EventDetailUiState {
    data object Idle : EventDetailUiState()
    data object Joining : EventDetailUiState()
    /** Successfully joined the mesh session but waiting for first peer to appear. */
    data class Connecting(val event: JoinedEventBundle) : EventDetailUiState()
    /** First peer connected — show the welcome moment before transitioning to Joined. */
    data class Success(val event: JoinedEventBundle, val peerName: String) : EventDetailUiState()
    data class Joined(val event: JoinedEventBundle) : EventDetailUiState()
    data class Error(val message: String) : EventDetailUiState()
}

class EventDetailViewModel(
    private val mesh: MeshGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow<EventDetailUiState>(EventDetailUiState.Idle)
    val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

    private val _meshState = MutableStateFlow<MeshUiState>(MeshUiState.Idle)
    val meshState: StateFlow<MeshUiState> = _meshState.asStateFlow()

    /**
     * Granular connection lifecycle events, buffered from the moment the ViewModel
     * is created. The screen collects this as a StateFlow so events are never lost
     * regardless of when the composable enters composition.
     */
    private val _connectionLog = MutableStateFlow<List<ConnectionEvent>>(emptyList())
    val connectionLog: StateFlow<List<ConnectionEvent>> = _connectionLog.asStateFlow()

    /**
     * Whether the local user created this event. Hosts skip the connecting gate
     * and go straight to Joined so they can manage the event while waiting for attendees.
     */
    val isHost: StateFlow<Boolean> = mesh.isHost
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Live list of peers connected to this node, exposed for the connecting screen.
     */
    val connectedPeers: StateFlow<List<GatewayPeer>> = mesh.connectedPeers
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Live list of presentations synced across the mesh.
     * Backed directly by [MeshGateway.presentations] — no local copy needed.
     */
    val presentations: StateFlow<List<Presentation>> = mesh.presentations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private var joinedSessionId: String? = null

    init {
        // Accumulate connection events immediately so nothing is lost before the composable
        // enters composition — events fire during joinEvent() before the screen is visible.
        viewModelScope.launch {
            mesh.connectionEvents.collect { event ->
                _connectionLog.update { current -> (current + event).takeLast(50) }
            }
        }

        // Watch mesh state to reset on leave/error
        viewModelScope.launch {
            mesh.state.collect { state ->
                _meshState.value = state

                when (state) {
                    is MeshUiState.Idle -> {
                        if (_uiState.value !is EventDetailUiState.Joining) {
                            joinedSessionId = null
                            _uiState.value = EventDetailUiState.Idle
                        }
                    }
                    is MeshUiState.Error -> {
                        if (_uiState.value is EventDetailUiState.Joining) {
                            joinedSessionId = null
                            _uiState.value = EventDetailUiState.Error(state.reason)
                        }
                    }
                    else -> Unit
                }
            }
        }

        // Once in Connecting state, watch peers and promote through Success → Joined.
        // Hosts skip this — they go straight to Joined so they can manage the event.
        viewModelScope.launch {
            mesh.connectedPeers.collect { peers ->
                val current = _uiState.value
                if (current is EventDetailUiState.Connecting && peers.isNotEmpty()) {
                    val peerName = peers.first().displayName
                    _uiState.value = EventDetailUiState.Success(current.event, peerName)
                    delay(2_500)
                    // Only advance if we're still in Success (user hasn't cancelled)
                    if (_uiState.value is EventDetailUiState.Success) {
                        _uiState.value = EventDetailUiState.Joined(current.event)
                    }
                }
            }
        }
    }

    fun joinEvent(sessionId: String) {
        if (joinedSessionId == sessionId && _uiState.value is EventDetailUiState.Joined) return

        viewModelScope.launch {
            _uiState.value = EventDetailUiState.Joining

            runCatching {
                mesh.start()
                mesh.joinEvent(sessionId)
            }.onSuccess { bundle ->
                joinedSessionId = sessionId
                // Hosts go straight to Joined — they need the full screen to manage the event
                // while waiting for attendees. Joiners wait in Connecting until a peer appears.
                _uiState.value = if (mesh.isHost.value || mesh.connectedPeers.value.isNotEmpty()) {
                    EventDetailUiState.Joined(bundle)
                } else {
                    EventDetailUiState.Connecting(bundle)
                }
            }.onFailure { e ->
                joinedSessionId = null
                _uiState.value = EventDetailUiState.Error(
                    e.message ?: "Failed to join event."
                )
            }
        }
    }

    fun leaveEvent() {
        viewModelScope.launch {
            runCatching {
                mesh.leaveEvent()
            }.onFailure { e ->
                _uiState.value = EventDetailUiState.Error(
                    e.message ?: "Failed to leave event."
                )
                return@launch
            }

            joinedSessionId = null
            _uiState.value = EventDetailUiState.Idle
        }
    }
}