package edu.uwm.cs595.goup11.frontend.features.createevent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateEventDraft(
    val title: String = "",
    val description: String = "",
    val venue: String = "",
    val hostDisplayName: String = ""
)

sealed class CreateEventUiState {
    data object Editing : CreateEventUiState()
    data object Submitting : CreateEventUiState()
    data class Hosting(val sessionId: String) : CreateEventUiState()
    data class Error(val message: String) : CreateEventUiState()
}

class CreateEventViewModel(
    private val mesh: MeshGateway
) : ViewModel() {



    private val _draft = MutableStateFlow(CreateEventDraft())
    val draft: StateFlow<CreateEventDraft> = _draft.asStateFlow()

    private val _uiState = MutableStateFlow<CreateEventUiState>(CreateEventUiState.Editing)
    val uiState: StateFlow<CreateEventUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            mesh.state.collect { state ->
                if (state is MeshUiState.Idle && _uiState.value is CreateEventUiState.Hosting) {
                    reset()
                }
            }
        }
    }

    fun updateTitle(value: String) {
        _draft.value = _draft.value.copy(title = value)
        if (_uiState.value is CreateEventUiState.Error) {
            _uiState.value = CreateEventUiState.Editing
        }
    }

    fun updateDescription(value: String) {
        _draft.value = _draft.value.copy(description = value)
    }

    fun updateVenue(value: String) {
        _draft.value = _draft.value.copy(venue = value)
    }

    fun updateHostDisplayName(value: String) {
        _draft.value = _draft.value.copy(hostDisplayName = value)
    }

    fun hostEvent() {
        val title = _draft.value.title.trim()

        if (title.isBlank()) {
            _uiState.value = CreateEventUiState.Error("Enter an event name to continue.")
            return
        }

        viewModelScope.launch {
            _uiState.value = CreateEventUiState.Submitting

            runCatching {
                mesh.start()
                mesh.hostEvent(title)
            }.onSuccess {
                _uiState.value = CreateEventUiState.Hosting(title)
            }.onFailure { e ->
                _uiState.value = CreateEventUiState.Error(
                    e.message ?: "Could not start hosting this event."
                )
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            try {
                mesh.leaveEvent()
            } catch (e: Exception) {

            } finally {
                _uiState.value = CreateEventUiState.Editing
                _draft.value = CreateEventDraft()
            }
        }
    }
}