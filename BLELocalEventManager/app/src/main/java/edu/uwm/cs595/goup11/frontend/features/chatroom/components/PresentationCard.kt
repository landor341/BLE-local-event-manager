package edu.uwm.cs595.goup11.frontend.features.chatroom.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentationItem(
    presentation: Presentation,
    onJoinClick: () -> Unit
) {
    Card(
        onClick = onJoinClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // left side
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = presentation.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Speaker: ${presentation.speaker}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            // right side
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "Enter Chat Room",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}