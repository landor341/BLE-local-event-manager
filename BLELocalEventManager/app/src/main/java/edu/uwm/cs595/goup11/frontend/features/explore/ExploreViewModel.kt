package edu.uwm.cs595.goup11.frontend.features.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.DiscoveredEventSummary
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ExploreViewModel — Sprint 3
 *
 * RESPONSIBILITIES:
 * - Start mesh system (mesh.start()) once
 * - Start scanning when requested (mesh.startScanning())
 * - Collect discoveredEvents and expose them to the UI
 *
 * UI RULES:
 * - ExploreScreen must not import backend.network.*
 * - ExploreScreen should render state from this ViewModel only
 */
class ExploreViewModel(
    private val mesh: MeshGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExploreUiState>(ExploreUiState.Loading)
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private val _events = MutableStateFlow<List<DiscoveredEventSummary>>(emptyList())
    val events: StateFlow<List<DiscoveredEventSummary>> = _events.asStateFlow()

    private var started = false

    /**
     * Call once from the screen (LaunchedEffect) to wire everything.
     * Safe to call multiple times; will no-op after first start.
     */
    fun start() {
        if (started) return
        started = true

        viewModelScope.launch { mesh.start() }

        // Collect discovered events forever while the VM is alive.
        viewModelScope.launch {
            mesh.discoveredEvents.collect { ev ->
                _events.update { list ->
                    if (list.any { it.sessionId == ev.sessionId }) list else list + ev
                }
                _uiState.value = if (_events.value.isEmpty()) ExploreUiState.Empty else ExploreUiState.Success
            }
        }

        // Initial state (before anything discovered)
        _uiState.value = ExploreUiState.Empty
    }

    fun startScanning() {
        viewModelScope.launch {
            _uiState.value = ExploreUiState.Loading
            mesh.startScanning()
            // Actual results will come through the collector above
            _uiState.value = if (_events.value.isEmpty()) ExploreUiState.Empty else ExploreUiState.Success
        }
    }

    fun stopScanning() {
        viewModelScope.launch {
            mesh.stopScanning()
        }
    }
}