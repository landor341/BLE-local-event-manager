package edu.uwm.cs595.goup11.frontend.features.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.core.mesh.DiscoveredEventSummary
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState

@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onEventClick: (String) -> Unit,
    viewModel: ExploreViewModel,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.start()
    }

    val uiState by viewModel.uiState.collectAsState()
    val meshState by viewModel.meshState.collectAsState()
    val discoveredEvents by viewModel.events.collectAsState()

    val filtered = remember(query, discoveredEvents) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            discoveredEvents
        } else {
            discoveredEvents.filter {
                it.sessionId.lowercase().contains(q) ||
                        it.title.lowercase().contains(q) ||
                        it.venue.lowercase().contains(q)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ExploreHeader(onBack = onBack)

            ExploreHero(state = meshState)

            SearchSection(
                query = query,
                onQueryChange = { query = it },
                resultCount = filtered.size
            )

            when {
                uiState is ExploreUiState.Loading && filtered.isEmpty() -> {
                    LoadingState()
                }

                uiState is ExploreUiState.Error -> {
                    ErrorState()
                }

                filtered.isEmpty() -> {
                    EmptyExploreState()
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = filtered,
                            key = { it.sessionId }
                        ) { event ->
                            DiscoveredEventCard(
                                event = event,
                                onClick = { onEventClick(event.sessionId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreHeader(
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Explore",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Discover nearby events in real time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExploreHero(
    state: MeshUiState
) {
    val gradient = Brush.verticalGradient(
        colors = when (state) {
            MeshUiState.Scanning -> listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
            )

            is MeshUiState.InEvent, is MeshUiState.Hosting -> listOf(
                Color(0xFF246B45),
                Color(0xFF2E8B57)
            )

            is MeshUiState.Error -> listOf(
                MaterialTheme.colorScheme.error,
                MaterialTheme.colorScheme.error.copy(alpha = 0.82f)
            )

            else -> listOf(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
            )
        }
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusPill(state = state)

            Text(
                text = heroTitle(state),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = heroContentColor(state)
            )

            Text(
                text = heroSubtitle(state),
                style = MaterialTheme.typography.bodyLarge,
                color = heroContentColor(state).copy(alpha = 0.86f)
            )
        }
    }
}

@Composable
private fun StatusPill(
    state: MeshUiState
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(heroContentColor(state).copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(heroContentColor(state))
        )

        Text(
            text = statusTitle(state),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = heroContentColor(state)
        )
    }
}

@Composable
private fun SearchSection(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search"
                )
            },
            placeholder = {
                Text("Search by name, venue, or session id")
            },
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (resultCount == 1) "1 event nearby" else "$resultCount events nearby",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = { }) {
                Text("Live scan")
            }
        }
    }
}

@Composable
private fun DiscoveredEventCard(
    event: DiscoveredEventSummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Tap to view and join",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            EventMetaRow(
                icon = Icons.Default.LocationOn,
                label = event.venue
            )

            EventMetaRow(
                icon = Icons.Default.Groups,
                label = "Session ID: ${event.sessionId}"
            )
        }
    }
}

@Composable
private fun EventMetaRow(
    icon: ImageVector,
    label: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            text = "Scanning for nearby events...",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Make sure another device or emulator is hosting a session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "We couldn’t complete the scan right now. Try again in a moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun EmptyExploreState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "No events found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Try hosting an event on another device or emulator first, then come back here to scan again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun statusTitle(state: MeshUiState): String =
    when (state) {
        MeshUiState.Idle -> "Ready"
        MeshUiState.Scanning -> "Scanning"
        is MeshUiState.Joining -> "Joining"
        is MeshUiState.InEvent -> "Connected"
        is MeshUiState.Hosting -> "Hosting"
        is MeshUiState.Error -> "Error"
        else -> {
            "Error"
        }
    }

private fun heroTitle(state: MeshUiState): String =
    when (state) {
        MeshUiState.Idle -> "Ready to discover nearby events."
        MeshUiState.Scanning -> "Searching for live nearby events."
        is MeshUiState.Joining -> "Joining ${state.sessionId}."
        is MeshUiState.InEvent -> "Connected to ${state.sessionId}."
        is MeshUiState.Hosting -> "You’re hosting ${state.sessionId}."
        is MeshUiState.Error -> "Scan needs attention."
        else -> {
            "Error"
        }
    }

private fun heroSubtitle(state: MeshUiState): String =
    when (state) {
        MeshUiState.Idle ->
            "Your device is ready to scan and discover local event sessions."

        MeshUiState.Scanning ->
            "We’re actively scanning for nearby sessions available to join."

        is MeshUiState.Joining ->
            "Connecting to the selected event and preparing the session."

        is MeshUiState.InEvent ->
            "You’re already connected. You can return to that session anytime."

        is MeshUiState.Hosting ->
            "Nearby participants can now discover your event."

        is MeshUiState.Error ->
            "Something interrupted scanning. Please try again."

        is MeshUiState.Advertising ->
            ""
    }

@Composable
private fun heroContentColor(state: MeshUiState): Color =
    when (state) {
        MeshUiState.Idle -> MaterialTheme.colorScheme.onSecondaryContainer
        MeshUiState.Scanning -> MaterialTheme.colorScheme.onPrimary
        is MeshUiState.Joining -> MaterialTheme.colorScheme.onSecondaryContainer
        is MeshUiState.InEvent -> Color.White
        is MeshUiState.Hosting -> Color.White
        is MeshUiState.Error -> MaterialTheme.colorScheme.onError
        is MeshUiState.Advertising -> Color.White
    }