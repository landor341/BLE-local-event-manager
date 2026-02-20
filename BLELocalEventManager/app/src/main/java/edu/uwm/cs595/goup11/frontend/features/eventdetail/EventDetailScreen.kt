package edu.uwm.cs595.goup11.frontend.features.eventdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uwm.cs595.goup11.frontend.domain.models.Event
import edu.uwm.cs595.goup11.R
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    event: Event,
    onBack: () -> Unit,
    isJoined: Boolean = true,
    onOpenChat: () -> Unit ={},
    onJoin: () -> Unit ={},
    onLeave: () -> Unit ={},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // Map photo
            Image(
                painter = painterResource(id = R.drawable.map_sample),
                contentDescription = "Event Map",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = event.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary // change color
            )

            Spacer(Modifier.height(8.dp))

            // Some event detail
            Text(text = "Host: ${event.hostName}", style = MaterialTheme.typography.bodyLarge)
            Text(text = "Time: ${event.time}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Text(text = "Location: ${event.location}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            Text(
                text = "${event.participantCount} joined",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(thickness = 0.5.dp) // a line to separate
            Spacer(Modifier.height(24.dp))

            // Description
            Text(text = "Description", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp
            )

            Spacer(Modifier.height(24.dp))

            // Presentation List
            Text(text = "Presentation List", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("No presentations available yet.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            Spacer(Modifier.height(40.dp))

            // Button (join or leave event/ chat access button if join)
            if (isJoined) {
                // a button to open chat
                Button(
                    onClick = onOpenChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open Chat")
                }
                Spacer(Modifier.height(16.dp))

                // a button to leave event
                Button(
                    onClick = onLeave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Leave Event")
                }

            } else {

                Button(
                    onClick = onJoin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Join Event")
                }

            }

        }
    }
}


@Preview(showBackground = true)
@Composable
fun EventDetailPreview() {
    BLELocalEventManagerTheme {
        EventDetailScreen(
            event = EventMockData.events().first(),
            isJoined = true,
            onOpenChat= {},
            onJoin = {},
            onLeave = {},
            onBack = {}
        )
    }
}
