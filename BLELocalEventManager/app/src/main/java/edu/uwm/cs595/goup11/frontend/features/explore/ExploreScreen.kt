package edu.uwm.cs595.goup11.frontend.features.explore

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.domain.models.Event
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.tooling.preview.Preview
import edu.uwm.cs595.goup11.frontend.features.explore.components.EventCard

// ExploreScreen.kt
// Shows nearby events (mock now, mesh-backed later).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allEvents = remember { ExploreMockData.events() }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(query, allEvents) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allEvents
        else allEvents.filter {
            it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.hostName.lowercase().contains(q)
        }
    }

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
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                },
                placeholder = { Text("Search events, hosts, descriptions") }
            )

            Spacer(modifier = Modifier.height(12.dp))

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
                            onClick = {
                                // TODO
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyExploreState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("No events found", style = MaterialTheme.typography.titleMedium)
        Text(
            "Try a different search or wait for nearby discovery to find events.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ExplorePreview() {
    edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme {
        ExploreScreen(onBack = {})
    }
}
