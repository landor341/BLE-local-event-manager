package edu.uwm.cs595.goup11.frontend.features.createpresentation


import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.frontend.core.mesh.ItineraryItem
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID

data class CreatePresentationDraft(
    val title: String = "",
    val description: String = "",
    val startTime: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    val endTime: java.time.LocalDateTime = java.time.LocalDateTime.now().plusHours(1),
    val location: String = "",
    val speaker: String = ""
)

sealed class CreatePresentationUiState {
    data object Editing : CreatePresentationUiState()
    data object Submitting : CreatePresentationUiState()
    data object Success : CreatePresentationUiState()
    data class Error(val message: String) : CreatePresentationUiState()
}

class CreatePresentationViewModel(
    private val mesh: MeshGateway
) : ViewModel() {

    private val _draft = MutableStateFlow(CreatePresentationDraft())
    val draft: StateFlow<CreatePresentationDraft> = _draft.asStateFlow()

    private val _uiState = MutableStateFlow<CreatePresentationUiState>(CreatePresentationUiState.Editing)
    val uiState: StateFlow<CreatePresentationUiState> = _uiState.asStateFlow()

    fun updateTitle(value: String) {
        _draft.value = _draft.value.copy(title = value)
        if (_uiState.value is CreatePresentationUiState.Error) {
            _uiState.value = CreatePresentationUiState.Editing
        }
    }

    fun updateDescription(value: String) {
        _draft.value = _draft.value.copy(description = value)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updateStartTime(h: Int, m: Int) {
        _draft.value = _draft.value.copy(
            startTime = _draft.value.startTime.withHour(h).withMinute(m)
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updateEndTime(h: Int, m: Int) {
        _draft.value = _draft.value.copy(
            endTime = _draft.value.endTime.withHour(h).withMinute(m)
        )
    }

    fun updateLocation(value: String) {
        _draft.value = _draft.value.copy(location = value)
    }

    fun updateSpeakerName(value: String) {
        _draft.value = _draft.value.copy(speaker = value)
    }

    fun addPresentation() {
        val draftValue = _draft.value
        val title = draftValue.title.trim()

        if (title.isBlank()) {
            _uiState.value = CreatePresentationUiState.Error("Enter an presentation name to continue.")
            return
        }

        viewModelScope.launch {
            _uiState.value = CreatePresentationUiState.Submitting

            runCatching {
                val newItem = ItineraryItem(
                    id = UUID.randomUUID().toString(),
                    title = draftValue.title,
                    time = draftValue.startTime.toDisplayTime(),
                    location = draftValue.location,
                    speaker = draftValue.speaker
                )
                mesh.addItineraryItem(newItem)
            }.onSuccess {
                _uiState.value = CreatePresentationUiState.Success
            }.onFailure { e ->
                _uiState.value = CreatePresentationUiState.Error(
                    e.message ?: "Could not add this presentation."
                )
            }
        }
    }

    fun reset() {
        _uiState.value = CreatePresentationUiState.Editing
        _draft.value = CreatePresentationDraft()
    }
}
