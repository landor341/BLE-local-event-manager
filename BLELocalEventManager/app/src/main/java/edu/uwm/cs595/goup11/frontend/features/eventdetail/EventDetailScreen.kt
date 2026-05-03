package edu.uwm.cs595.goup11.frontend.features.eventdetail

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.core.mesh.ConnectionEvent
import edu.uwm.cs595.goup11.frontend.core.mesh.GatewayPeer
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import edu.uwm.cs595.goup11.frontend.features.presentation.toDisplayTime

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    sessionId: String,
    viewModel: EventDetailViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    onViewConnectedUsers: () -> Unit = {},
    onViewPresentation: (String) -> Unit = {},
    onLeaveSuccess: () -> Unit = {},
    onCreatePresentation: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val meshState by viewModel.meshState.collectAsState()
    val presentations by viewModel.presentations.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val isHost by viewModel.isHost.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.joinEvent(sessionId)
    }

    LaunchedEffect(meshState) {
        if (meshState is MeshUiState.Idle) {
            onLeaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            EventDetailUiState.Idle,
            EventDetailUiState.Joining -> {
                JoiningContent(modifier = Modifier.padding(innerPadding))
            }

            is EventDetailUiState.Connecting -> {
                ConnectingContent(
                    peers = connectedPeers,
                    connectionLog = viewModel.connectionLog.collectAsState().value,
                    onCancel = { viewModel.leaveEvent() },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            is EventDetailUiState.Success -> {
                SuccessContent(
                    eventName = state.event.title,
                    peerName = state.peerName,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            is EventDetailUiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.joinEvent(sessionId) },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            is EventDetailUiState.Joined -> {
                val canOpenEventActions =
                    meshState is MeshUiState.InEvent || meshState is MeshUiState.Hosting

                JoinedEventContent(
                    event = state.event,
                    presentations = presentations,
                    onOpenChat = { if (canOpenEventActions) onOpenChat(state.event.sessionId) },
                    onViewConnectedUsers = { if (canOpenEventActions) onViewConnectedUsers() },
                    onViewPresentation = onViewPresentation,
                    onCreatePresentation = onCreatePresentation,
                    onLeave = { viewModel.leaveEvent() },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun ConnectingContent(
    peers: List<GatewayPeer>,
    connectionLog: List<ConnectionEvent>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier.height(56.dp)
        )

        Text(
            text = if (peers.isEmpty()) "Searching for nearby devices…"
            else "Connected to ${peers.size} device${if (peers.size == 1) "" else "s"}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        if (connectionLog.isNotEmpty()) {
            val latest = connectionLog.last()
            val line = when (latest) {
                is ConnectionEvent.DeviceFound -> "Found ${latest.deviceName}"
                is ConnectionEvent.Connecting -> "Connecting to ${latest.deviceName}…"
                is ConnectionEvent.Connected -> "✓ Connected to ${latest.deviceName}"
                is ConnectionEvent.DeviceLost -> "${latest.deviceName} moved out of range"
                is ConnectionEvent.Rejected -> "Connection rejected, retrying…"
            }
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (latest is ConnectionEvent.Connected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun SuccessContent(
    eventName: String,
    peerName: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha * 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Welcome to",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = eventName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Connected to $peerName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun JoiningContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            text = "Joining event...",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Preparing the local mesh session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Couldn't join event",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun JoinedEventContent(
    event: edu.uwm.cs595.goup11.frontend.core.mesh.JoinedEventBundle,
    presentations: List<Presentation>,
    onOpenChat: () -> Unit,
    onViewConnectedUsers: () -> Unit,
    onViewPresentation: (String) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    onCreatePresentation: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = event.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetaRow(icon = Icons.Default.LocationOn, text = event.venue)
                MetaRow(icon = Icons.Default.Groups, text = "Live mesh session")
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Description",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // ── Presentations ──────────────────────────────────────────────────

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Presentations",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (presentations.isEmpty()) {
                Text(
                    text = "No presentations scheduled yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                presentations.forEach { presentation ->
                    PresentationCard(
                        presentation = presentation,
                        onViewPresentation = onViewPresentation
                    )
                }
            }
        }

        // ── Schedule (itinerary) ───────────────────────────────────────────

        if (event.itinerary.isNotEmpty()) {
            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Schedule",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                event.itinerary.forEach { item ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            MetaRow(icon = Icons.Default.Schedule, text = item.time)
                            MetaRow(icon = Icons.Default.LocationOn, text = item.location)
                            item.speaker?.let {
                                Text(
                                    text = "Speaker: $it",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onOpenChat,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Open Chat") }
        Button(
            onClick = { onCreatePresentation() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Add Presentation") }
        Button(
            onClick = onViewConnectedUsers,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("View Connected Users") }

        Button(
            onClick = onLeave,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Leave Event") }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun PresentationCard(
    presentation: Presentation,
    onViewPresentation: (String) -> Unit
) {
    OutlinedCard(
        onClick = { onViewPresentation(presentation.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = presentation.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            MetaRow(
                icon = Icons.Default.Schedule,
                text = "${presentation.startTime.toDisplayTime()} – ${presentation.endTime.toDisplayTime()}"
            )
            MetaRow(
                icon = Icons.Default.LocationOn,
                text = presentation.location
            )
            Text(
                text = "Speaker: ${presentation.speakerName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}