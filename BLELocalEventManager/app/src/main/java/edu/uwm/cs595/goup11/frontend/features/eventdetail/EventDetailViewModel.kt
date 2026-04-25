package edu.uwm.cs595.goup11.frontend.features.eventdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.JoinedEventBundle
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class EventDetailUiState {
    data object Idle : EventDetailUiState()
    data object Joining : EventDetailUiState()
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
                _uiState.value = EventDetailUiState.Joined(bundle)
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