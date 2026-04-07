package edu.uwm.cs595.goup11.frontend.features.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.DiscoveredEventSummary
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExploreViewModel(
    private val mesh: MeshGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExploreUiState>(ExploreUiState.Loading)
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private val _events = MutableStateFlow<List<DiscoveredEventSummary>>(emptyList())
    val events: StateFlow<List<DiscoveredEventSummary>> = _events.asStateFlow()

    private val _meshState = MutableStateFlow<MeshUiState>(MeshUiState.Idle)
    val meshState: StateFlow<MeshUiState> = _meshState.asStateFlow()

    private var started = false

    private fun getSessionId(state: MeshUiState): String? {
        return when (state) {
            is MeshUiState.InEvent -> state.sessionId
            is MeshUiState.Hosting -> state.sessionId
            else -> null
        }
    }

    fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            mesh.start()
        }

        viewModelScope.launch {
            mesh.state.collect { state ->
                _meshState.value = state
                if (state is MeshUiState.Error) {
                    _uiState.value = ExploreUiState.Error
                }
                
                // If we are hosting or already in an event, ensure it's in the list
                getSessionId(state)?.let { sessionId ->
                    _events.update { current ->
                        if (current.any { it.sessionId == sessionId }) {
                            current
                        } else {
                            current + DiscoveredEventSummary(
                                sessionId = sessionId,
                                title = sessionId,
                                venue = "Hosted by you"
                            )
                        }
                    }
                    refreshUiState()
                }
            }
        }

        viewModelScope.launch {
            mesh.discoveredEvents.collect { ev ->
                _events.update { current ->
                    if (current.any { it.sessionId == ev.sessionId }) current else current + ev
                }
                refreshUiState()
            }
        }

        startScanning()
    }

    fun startScanning() {
        viewModelScope.launch {
            _events.value = emptyList()
            _uiState.value = ExploreUiState.Loading
            
            // Re-add self if already in a session when starting a fresh scan
            getSessionId(mesh.state.value)?.let { sessionId ->
                _events.value = listOf(
                    DiscoveredEventSummary(
                        sessionId = sessionId,
                        title = sessionId,
                        venue = "Hosted by you"
                    )
                )
            }

            mesh.startScanning()
            refreshUiState()
        }
    }

    fun stopScanning() {
        viewModelScope.launch {
            mesh.stopScanning()
        }
    }

    private fun refreshUiState() {
        _uiState.value = if (_events.value.isEmpty()) {
            ExploreUiState.Empty
        } else {
            ExploreUiState.Success
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScanning()
    }
}