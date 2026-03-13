package edu.uwm.cs595.goup11.frontend.features.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    mesh: MeshGateway,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uiState by mesh.state.collectAsState()
    val logs by mesh.logs.collectAsState(initial = "Initializing logs...")
    
    // We want to keep a list of logs to show in the UI
    val logList = remember { mutableStateListOf<String>() }
    
    LaunchedEffect(mesh) {
        mesh.start()
        // Automatically start scanning when entering developer mode to detect other nodes
        logList.add("starting initial scanning")
        mesh.startScanning()
    }

    LaunchedEffect(mesh) {
        mesh.logs.collect { newLog ->
            logList.add(newLog)
            if (logList.size > 100) logList.removeAt(0)
        }
    }

    var eventNodeText by remember { mutableStateOf("Dev-Node-01") }
    var isHosting = false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer Tools") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Event Node Configuration
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Event Node Configuration", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = eventNodeText,
                        onValueChange = { eventNodeText = it },
                        label = { Text("Broadcast Text (Session ID)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isHosting
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            scope.launch {
                                if (isHosting) {
                                    mesh.leaveEvent()
                                    // Resume scanning after stopping hosting so we can see other nodes
                                    mesh.startScanning()
                                } else {
                                    mesh.hostEvent(eventNodeText)
                                }
                                isHosting = ! isHosting
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isHosting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isHosting) "Deactivate Event Node" else "Activate Event Node")
                    }
                }
            }

            // Network Logs
            Text("Network Activity Logs", style = MaterialTheme.typography.titleMedium)
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(8.dp)
            ) {
                val listState = rememberLazyListState()
                
                // Auto-scroll to bottom when new logs arrive
                LaunchedEffect(logList.size) {
                    if (logList.isNotEmpty()) {
                        listState.animateScrollToItem(logList.size - 1)
                    }
                }

                LazyColumn(state = listState) {
                    items(logList) { log ->
                        Text(
                            text = log,
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            
            Button(
                onClick = { logList.clear() },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Clear Logs")
            }
        }
    }
}
