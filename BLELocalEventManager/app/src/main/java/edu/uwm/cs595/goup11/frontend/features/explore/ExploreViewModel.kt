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