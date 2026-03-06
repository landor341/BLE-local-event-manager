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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uwm.cs595.goup11.R
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme
import edu.uwm.cs595.goup11.frontend.domain.models.Event

/**
 * ==========================================================
 * EventDetailScreen — Sprint 3 Navigation + UI
 * ==========================================================
 *
 * MINIMAL CHANGE GOAL (Sprint 3):
 * - This screen must be navigable from Explore using ONLY a sessionId string.
 * - Do NOT require an Event object from navigation (that breaks mesh integration).
 *
 * CURRENT STATE:
 * - UI is mostly built already (image, title, description, join/leave buttons).
 * - For now we still display mock details by mapping sessionId -> mock Event (if present).
 *
 * NEXT (mesh integration):
 * - Replace mock lookup with MeshGateway.joinEvent(sessionId) result.
 * - Hook chat button to the real chat screen once created.
 *
 * TEAM RULES:
 * - Do NOT import backend.network.* in this file.
 * - Do NOT create Client/Network here.
 *
 * PRIMARY MAINTAINER: Frontend Integration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    isJoined: Boolean = true,
    onOpenChat: (String) -> Unit = {},
    onJoin: () -> Unit = {},
    onLeave: () -> Unit = {},
) {
    // Sprint 3 temporary: show mock event details if the id matches; otherwise show placeholders.
    // Later this should come from: MeshGateway.joinEvent(sessionId)
    val event: Event = remember(sessionId) {
        EventMockData.events().firstOrNull { it.id == sessionId } ?: Event(
            id = sessionId,
            name = sessionId,
            hostName = "Unknown Host",
            time = "Unknown time",
            location = "Unknown location",
            description = "Event details will be provided after joining the mesh session.",
            participantCount = 0
        )
    }

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
            // Map photo (placeholder)
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
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(8.dp))

            Text(text = "Host: ${event.hostName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Time: ${event.time}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            Text(
                text = "Location: ${event.location}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Text(
                text = "${event.participantCount} joined",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Description",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Presentation List",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "No presentations available yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(Modifier.height(40.dp))

            // Sprint 3: keep the same join/leave/chat controls.
            // Later: isJoined should come from MeshUiState + join result.
            if (isJoined) {
                Button(
                    onClick = { onOpenChat(event.name)},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open Chat")
                }

                Spacer(Modifier.height(16.dp))

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
            sessionId = EventMockData.events().first().id,
            isJoined = true,
            onOpenChat = {},
            onJoin = {},
            onLeave = {},
            onBack = {}
        )
    }
}