package edu.uwm.cs595.goup11.frontend.features.eventdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.domain.models.Event

/**
 * EventDetailScreen
 *
 * TODO: Implement full event detail UI.
 *
 * This screen should display:
 * - Event name
 * - Host information
 * - Presentation list
 * - Join / Leave event button
 * - Chat access
 * - Mesh status indicators
 *
 * Ownership: (Assign teammate name here)
 */
@Composable
fun EventDetailScreen(
    event: Event,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Event Detail Screen Placeholder\n\nEvent: ${event.name}",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
