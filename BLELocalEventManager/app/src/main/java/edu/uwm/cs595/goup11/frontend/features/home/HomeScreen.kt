package edu.uwm.cs595.goup11.frontend.features.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import java.time.format.DateTimeFormatter

/**
 * HomeScreen — Sprint 3
 *
 * PURPOSE:
 * - Give the user the next obvious action: Discover events.
 * - Show current mesh status (Idle/Scanning/Joined/Hosting).
 *
 * IMPORTANT:
 * AppContainer.init(...) only CONSTRUCTS the gateway.
 * The gateway will remain Idle until you call mesh.start().
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(
    onExploreClick: () -> Unit,
    onProfileClick: () -> Unit,
    mesh: MeshGateway? = null
) {
    // Start mesh once when this screen first appears.
    // Safe to call multiple times, but we only want it once.
    LaunchedEffect(mesh) {
        mesh?.start()
    }

    // Correct way to read StateFlow in Compose:
    // collectAsState will recompose when the state changes.
    val uiState: MeshUiState = if (mesh == null) {
        MeshUiState.Idle
    } else {
        val s by mesh.state.collectAsState()
        s
    }

    val allPres = remember { HomeMockData.presentations() }
    val myFormat = DateTimeFormatter.ofPattern("HH:mm")

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "BLE Local Event Manager",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Discover and join nearby events over Bluetooth.",
                style = MaterialTheme.typography.bodyLarge
            )

            StatusCard(state = uiState)

            Button(
                onClick = onExploreClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Discover nearby events")
            }

            OutlinedButton(
                onClick = { /* Sprint 3 optional: later navigate to Host screen */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Host an event (coming soon)")
            }

            Button(
                onClick = onProfileClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("View Personal Information")
            }
        }
    }
}

@Composable
private fun StatusCard(state: MeshUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Mesh status", style = MaterialTheme.typography.titleMedium)
            //TODO: Fix deprecated lines
            val line = when (state) {
                MeshUiState.Idle -> "Idle"
                MeshUiState.Scanning -> "Scanning for nearby events…"
                is MeshUiState.Joining -> "Joining:"
                is MeshUiState.InEvent -> "Joined event: ${state.sessionId}"
                is MeshUiState.Hosting -> "Hosting event: ${state.sessionId}"
                is MeshUiState.Error -> "Error: ${state.reason}"
                else -> {""}
            }

            Spacer(Modifier.height(6.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}