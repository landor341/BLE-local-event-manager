package edu.uwm.cs595.goup11.frontend.features.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.tooling.preview.Preview
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.features.explore.components.EventCard

/**
 * ExploreScreen — Sprint 3
 *
 * TOP PRIORITY RIGHT NOW:
 * - Make sure mesh status changes from Idle by actually starting + scanning.
 *
 * RULES:
 * - This screen must NOT import backend.network.*
 * - It can call MeshGateway methods only (start/startScanning/stopScanning).
 *
 * NOTE:
 * - We are still rendering mock EventCards for UI until you decide to swap list -> discovered.
 * - But mesh status shown here is REAL (MeshGateway.state).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onEventClick: (String) -> Unit,
    viewModel: ExploreViewModel,
    mesh: MeshGateway,
    modifier: Modifier = Modifier
) {
    // UI list (mock for now). Discovery list swap can happen later.
    val allEvents = remember { ExploreMockData.events() }

    var query by rememberSaveable { mutableStateOf("") }

    // Filter UI list (mock for now)
    val filtered = remember(query, allEvents) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allEvents
        else allEvents.filter {
            it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.hostName.lowercase().contains(q)
        }
    }

    // IMPORTANT:
    // - AppContainer.init() only constructs dependencies.
    // - Calling mesh.start() is what kicks off state/event collection.
    // - Calling mesh.startScanning() is what moves status away from Idle.
    LaunchedEffect(Unit) {
        mesh.start()
        mesh.startScanning()

        // Keeping this too (since your VM may also be doing discovery collection).
        viewModel.start()
        viewModel.startScanning()
    }

    // Real mesh status
    val meshState by mesh.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Explore") },
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
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mesh status card (REAL)
            MeshStatusCard(meshState)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                placeholder = { Text("Search events, hosts, descriptions") }
            )

            HorizontalDivider()

            if (filtered.isEmpty()) {
                EmptyExploreState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.id }) { event ->
                        EventCard(
                            event = event,
                            onClick = { onEventClick(event.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshStatusCard(state: MeshUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Mesh status", style = MaterialTheme.typography.titleMedium)

            val line = when (state) {
                MeshUiState.Idle -> "Idle (not started / not scanning)"
                MeshUiState.Scanning -> "Scanning for nearby events…"
                is MeshUiState.Joining -> "Joining: ${state.sessionId}"
                is MeshUiState.InEvent -> "Joined event: ${state.sessionId}"
                is MeshUiState.Hosting -> "Hosting event: ${state.sessionId}"
                is MeshUiState.Error -> "Error: ${state.reason}"
            }

            Spacer(Modifier.height(6.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyExploreState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("No events found", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tip: with LocalNetwork, you will discover nothing unless someone is hosting/advertising.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}