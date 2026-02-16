// ExploreUiState.kt
// Represents UI state variations for Explore screen.

package edu.uwm.cs595.goup11.frontend.features.explore

sealed class ExploreUiState {
    object Loading : ExploreUiState()
    object Empty : ExploreUiState()
    object Error : ExploreUiState()
    object Success : ExploreUiState()
}
