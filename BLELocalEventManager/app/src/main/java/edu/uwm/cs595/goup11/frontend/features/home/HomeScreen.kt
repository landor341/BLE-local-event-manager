package edu.uwm.cs595.goup11.frontend.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.core.mesh.GatewayPeer
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation

@Composable
fun HomeScreen(
    onExploreClick: () -> Unit,
    onProfileClick: () -> Unit,
    onHostClick: () -> Unit,
    onEventClick: () -> Unit = {},
    onPresentationsClick: () -> Unit = {},
    onPeersClick: () -> Unit = {},
    mesh: MeshGateway? = null
) {
    val uiState: MeshUiState = if (mesh == null) {
        MeshUiState.Idle
    } else {
        val state by mesh.state.collectAsState()
        state
    }

    val presentations: List<Presentation> = if (mesh == null) {
        emptyList()
    } else {
        val p by mesh.presentations.collectAsState()
        p
    }

    val peers: List<GatewayPeer> = if (mesh == null) {
        emptyList()
    } else {
        val p by mesh.connectedPeers.collectAsState()
        p
    }

    val isInEvent = uiState is MeshUiState.InEvent || uiState is MeshUiState.Hosting

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            HomeHeader(onProfileClick = onProfileClick)

            HeroSection(
                state = uiState,
                onExploreClick = onExploreClick,
                onHostClick = onHostClick,
                onEventClick = onEventClick
            )

            if (isInEvent) {
                InEventActionsSection(
                    state = uiState,
                    presentations = presentations,
                    peers = peers,
                    onProfileClick = onProfileClick,
                    onPresentationsClick = onPresentationsClick,
                    onPeersClick = onPeersClick
                )
            } else {
                PrimaryActionsSection(
                    state = uiState,
                    onExploreClick = onExploreClick,
                    onHostClick = onHostClick,
                    onProfileClick = onProfileClick
                )

                SecondaryActionsSection(
                    isInEvent = false,
                    presentations = presentations,
                    peers = peers,
                    onPresentationsClick = onPresentationsClick,
                    onPeersClick = onPeersClick
                )
            }

            StatusSection(state = uiState)
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun HomeHeader(onProfileClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = " ",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                .clickable { onProfileClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Profile",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── Hero ──────────────────────────────────────────────────────────────────────

@Composable
private fun HeroSection(
    state: MeshUiState,
    onExploreClick: () -> Unit,
    onHostClick: () -> Unit,
    onEventClick: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        )
    )

    val isInEvent = state is MeshUiState.InEvent || state is MeshUiState.Hosting

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            StateBadge(state = state)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = heroTitle(state),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = statusDescription(state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.84f)
                )
            }

            if (isInEvent) {
                // In event — show single "Go to Event" button
                Button(
                    onClick = onEventClick,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Go to Event")
                }
            } else {
                // Not in event — show Explore + Host
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onExploreClick,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Explore")
                    }

                    FilledTonalButton(
                        onClick = onHostClick,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Host")
                    }
                }
            }
        }
    }
}

// ── Badge ─────────────────────────────────────────────────────────────────────

@Composable
private fun StateBadge(state: MeshUiState) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onPrimary)
        )
        Text(
            text = statusTitle(state),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

// ── Primary actions (not in event) ───────────────────────────────────────────

@Composable
private fun PrimaryActionsSection(
    state: MeshUiState,
    onExploreClick: () -> Unit,
    onHostClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Get started")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Explore",
                subtitle = "Find nearby events",
                icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                onClick = onExploreClick,
                enabled = true
            )
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Host",
                subtitle = "Create an event",
                icon = Icons.Default.Podcasts,
                onClick = onHostClick,
                enabled = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Profile",
                subtitle = "Manage your info",
                icon = Icons.Default.Person,
                onClick = onProfileClick,
                enabled = true
            )
            StatusSummaryTile(
                modifier = Modifier.weight(1f),
                state = stateSummary(state)
            )
        }
    }
}

// ── In-event actions (replaces primary + secondary when in event) ─────────────

@Composable
private fun InEventActionsSection(
    state: MeshUiState,
    presentations: List<Presentation>,
    peers: List<GatewayPeer>,
    onProfileClick: () -> Unit,
    onPresentationsClick: () -> Unit,
    onPeersClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Event")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Presentations",
                subtitle = if (presentations.isEmpty()) "None yet"
                else "${presentations.size} scheduled",
                icon = Icons.Default.CalendarMonth,
                onClick = onPresentationsClick,
                enabled = true
            )
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Peers",
                subtitle = if (peers.isEmpty()) "No peers connected."
                else "${peers.size} connected",
                icon = Icons.Default.Groups,
                onClick = onPeersClick,
                enabled = peers.isNotEmpty()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Profile",
                subtitle = "Manage your info",
                icon = Icons.Default.Person,
                onClick = onProfileClick,
                enabled = true
            )
            StatusSummaryTile(
                modifier = Modifier.weight(1f),
                state = stateSummary(state)
            )
        }
    }
}

// ── Secondary actions (not in event) ─────────────────────────────────────────

@Composable
private fun SecondaryActionsSection(
    isInEvent: Boolean,
    presentations: List<Presentation>,
    peers: List<GatewayPeer>,
    onPresentationsClick: () -> Unit,
    onPeersClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("More")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Presentations",
                subtitle = if (isInEvent && presentations.isNotEmpty())
                    "${presentations.size} scheduled"
                else "Join an event to view",
                icon = Icons.Default.CalendarMonth,
                onClick = onPresentationsClick,
                enabled = isInEvent && presentations.isNotEmpty()
            )
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Peers",
                subtitle = if (isInEvent && peers.isNotEmpty())
                    "${peers.size} connected"
                else "Join an event to view",
                icon = Icons.Default.Groups,
                onClick = onPeersClick,
                enabled = isInEvent && peers.isNotEmpty()
            )
        }
    }
}

// ── Shared tile components ────────────────────────────────────────────────────

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = if (enabled) modifier.clickable { onClick() } else modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (enabled) 0.45f else 0.24f
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.50f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusSummaryTile(
    modifier: Modifier = Modifier,
    state: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = state,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Status section ────────────────────────────────────────────────────────────

@Composable
private fun StatusSection(state: MeshUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Live status")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusRow(label = "State", value = statusTitle(state))

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StatusRow(
                    label = "Session",
                    value = when (state) {
                        is MeshUiState.Joining -> state.sessionId
                        is MeshUiState.InEvent -> state.sessionId
                        is MeshUiState.Hosting -> state.sessionId
                        else -> "None"
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StatusRow(
                    label = "Connection",
                    value = when (state) {
                        MeshUiState.Scanning -> "Searching"
                        is MeshUiState.InEvent -> "Connected"
                        is MeshUiState.Hosting -> "Broadcasting"
                        is MeshUiState.Joining -> "Joining"
                        is MeshUiState.Error -> "Attention needed"
                        is MeshUiState.Idle -> "Ready"
                        is MeshUiState.Advertising -> "Currently advertising"
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

// ── String helpers ────────────────────────────────────────────────────────────

private fun heroTitle(state: MeshUiState): String = when (state) {
    MeshUiState.Idle -> "Find or host local events."
    MeshUiState.Scanning -> "Looking for nearby events."
    is MeshUiState.Joining -> "Joining ${state.sessionId}."
    is MeshUiState.InEvent -> "You're connected."
    is MeshUiState.Hosting -> "Your event is live."
    is MeshUiState.Error -> "Something needs attention."
    is MeshUiState.Advertising -> "Currently advertising."
}

private fun stateSummary(state: MeshUiState): String = when (state) {
    MeshUiState.Idle -> "Ready to explore or host"
    MeshUiState.Scanning -> "Scanning nearby"
    is MeshUiState.Joining -> "Joining event"
    is MeshUiState.InEvent -> "In active event"
    is MeshUiState.Hosting -> "Hosting now"
    is MeshUiState.Error -> "Check connection state"
    is MeshUiState.Advertising -> "Advertising"
}

private fun statusTitle(state: MeshUiState): String = when (state) {
    MeshUiState.Idle -> "Ready"
    MeshUiState.Scanning -> "Scanning"
    is MeshUiState.Joining -> "Joining"
    is MeshUiState.InEvent -> "Connected"
    is MeshUiState.Hosting -> "Hosting"
    is MeshUiState.Error -> "Error"
    is MeshUiState.Advertising -> "Advertising"
}

private fun statusDescription(state: MeshUiState): String = when (state) {
    MeshUiState.Idle ->
        "Start by exploring nearby events or host your own event."

    MeshUiState.Scanning ->
        "Searching for active local sessions."

    is MeshUiState.Joining ->
        "Connecting to ${state.sessionId}."

    is MeshUiState.InEvent ->
        "You're currently connected to ${state.sessionId}. If you just joined the event and don't see any peers, please give the network some time to auto-connect to peers"

    is MeshUiState.Hosting ->
        "You're hosting ${state.sessionId}."

    is MeshUiState.Error ->
        "Something needs attention: ${state.reason}"

    is MeshUiState.Advertising ->
        "Currently advertising"
}