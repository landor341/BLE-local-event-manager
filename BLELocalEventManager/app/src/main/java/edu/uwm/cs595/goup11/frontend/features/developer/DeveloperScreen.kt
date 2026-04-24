package edu.uwm.cs595.goup11.frontend.features.developer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uwm.cs595.goup11.backend.network.*
import edu.uwm.cs595.goup11.backend.network.PeerEntry
import androidx.core.content.ContextCompat
import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.ConnectNetwork
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.MockClient
import edu.uwm.cs595.goup11.backend.network.Network
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import edu.uwm.cs595.goup11.frontend.core.mesh.DiscoveredEventSummary
import edu.uwm.cs595.goup11.frontend.core.mesh.GatewayPeer
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.core.mesh.TopologyChoice
import edu.uwm.cs595.goup11.frontend.dev.DevNetworkScreen
import edu.uwm.cs595.goup11.frontend.dev.DiscoveredEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import java.nio.charset.StandardCharsets
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.withContext
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import java.time.LocalDateTime

private enum class DevMode {
    GATEWAY,
    DIRECT,
    MOCK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    mesh: MeshGateway,
    onBack: () -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(DevMode.GATEWAY) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Developer",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Gateway, direct, and mock workflow testing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeSelector(
                selected = mode,
                onSelected = { mode = it }
            )

            when (mode) {
                DevMode.GATEWAY -> GatewayDevContent(mesh = mesh)
                DevMode.DIRECT -> DirectDevContent()
                DevMode.MOCK -> MockDevContent()
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selected: DevMode,
    onSelected: (DevMode) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DevMode.entries.forEach { mode ->
                val isSelected = mode == selected
                val icon = when (mode) {
                    DevMode.GATEWAY -> Icons.Default.BugReport
                    DevMode.DIRECT -> Icons.Default.PlayArrow
                    DevMode.MOCK -> Icons.Default.SmartToy
                }

                Button(
                    onClick = { onSelected(mode) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mode.name,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────── */
/* GATEWAY MODE                                                              */
/* ────────────────────────────────────────────────────────────────────────── */

@Composable
private fun GatewayDevContent(mesh: MeshGateway) {
    val scope = rememberCoroutineScope()
    val uiState by mesh.state.collectAsState()

    val logs = remember { mutableStateListOf<String>() }
    val chatMessages = remember { mutableStateListOf<String>() }
    val discovered = remember { mutableStateListOf<DiscoveredEventSummary>() }

    LaunchedEffect(mesh) {
        launch { mesh.start() }
        launch {
            mesh.logs.collect { line ->
                logs.add(line)
            }
        }
        launch {
            mesh.chat.collect { message ->
                chatMessages.add("[${message.sender.take(8)}] ${message.text}")
            }
        }
        launch {
            mesh.discoveredEvents.collect { event ->
                if (discovered.none { it.sessionId == event.sessionId }) {
                    discovered.add(event)
                }
            }
        }
    }

    val connectedPeers by mesh.connectedPeers.collectAsState()
    var selectedPeer by remember { mutableStateOf<GatewayPeer?>(null) }

    LaunchedEffect(connectedPeers) {
        if (connectedPeers.size == 1) {
            selectedPeer = connectedPeers.first()
        } else if (
            selectedPeer != null &&
            connectedPeers.none { it.endpointId == selectedPeer?.endpointId }
        ) {
            selectedPeer = null
        }
    }

    var eventName by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var chatText by rememberSaveable { mutableStateOf("") }
    var topoChoice by rememberSaveable { mutableStateOf(TopologyChoice.SNAKE) }

    val logState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DevInfoBanner(
            title = "Gateway Mode",
            subtitle = "Uses your app's MeshGateway stack for end-to-end integration testing."
        )

        DevCard("STATUS") {
            val label = when (val s = uiState) {
                is MeshUiState.Idle -> "Idle"
                is MeshUiState.Scanning -> "Scanning"
                is MeshUiState.Joining -> "Joining ${s.sessionId}"
                is MeshUiState.Hosting -> "Hosting ${s.sessionId}"
                is MeshUiState.InEvent -> "In event ${s.sessionId}"
                is MeshUiState.Error -> "Error: ${s.reason}"
                is MeshUiState.Advertising -> "Advertising"
            }
            DevMonoText(label)
        }

        DevCard("CONFIG") {
            DevField("Display Name") {
                DevInput(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        mesh.setDisplayName(it.trim())
                    },
                    placeholder = "e.g. Alice",
                    enabled = uiState == MeshUiState.Idle
                )
            }

            DevField("Event Name") {
                DevInput(
                    value = eventName,
                    onValueChange = { eventName = it },
                    placeholder = "e.g. TechConf",
                    enabled = uiState == MeshUiState.Idle
                )
            }

            DevField("Topology") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopologyChoice.entries.forEach { choice ->
                        val selected = topoChoice == choice
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { topoChoice = choice }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = choice.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }

        DevCard("ACTIONS") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevButton(
                    text = "Host",
                    color = Color(0xFF1B8A4E),
                    enabled = uiState == MeshUiState.Idle && eventName.isNotBlank()
                ) {
                    scope.launch { mesh.hostEvent(eventName.trim(), topoChoice) }
                }

                DevButton(
                    text = "Scan",
                    color = Color(0xFF0B6EBD),
                    enabled = uiState == MeshUiState.Idle
                ) {
                    discovered.clear()
                    scope.launch { mesh.startScanning() }
                }

                DevButton(
                    text = "Stop Scan",
                    color = Color(0xFFB45309),
                    enabled = uiState == MeshUiState.Scanning
                ) {
                    scope.launch { mesh.stopScanning() }
                }

                DevButton(
                    text = "Leave",
                    color = Color(0xFFDC2626),
                    enabled = uiState is MeshUiState.InEvent || uiState is MeshUiState.Hosting
                ) {
                    scope.launch { mesh.leaveEvent() }
                }
            }
        }

        if (connectedPeers.isNotEmpty() || uiState is MeshUiState.InEvent || uiState is MeshUiState.Hosting) {
            DevCard("PEERS (${connectedPeers.size})") {
                if (connectedPeers.isEmpty()) {
                    DevHintText("No peers connected yet")
                } else {
                    connectedPeers.forEach { peer ->
                        val isSelected = selectedPeer?.endpointId == peer.endpointId

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    selectedPeer = if (isSelected) null else peer
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF1B8A4E), CircleShape)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                DevMonoText(peer.displayName, bold = true, size = 12.sp)
                                DevMonoText(
                                    text = peer.endpointId,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    size = 10.sp
                                )
                            }

                            if (isSelected) {
                                DevMonoText(
                                    text = "selected",
                                    color = MaterialTheme.colorScheme.primary,
                                    size = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
        if (uiState is MeshUiState.InEvent || uiState is MeshUiState.Hosting) {
            val presentations by mesh.presentations.collectAsState()

            DevCard("PRESENTATIONS (${presentations.size})") {
                if (presentations.isEmpty()) {
                    DevHintText("No presentations yet")
                } else {
                    presentations.forEach { p ->
                        DevMonoText("• ${p.name} @ ${p.location}", size = 11.sp)
                        DevMonoText(
                            text = "  Speaker: ${p.speakerName}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 10.sp
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                DevButton(
                    text = "Add Test Presentation",
                    color = Color(0xFF7C3AED),
                    enabled = uiState is MeshUiState.InEvent || uiState is MeshUiState.Hosting
                ) {
                    mesh.addPresentation(
                        Presentation(
                            id                = java.util.UUID.randomUUID().toString(),
                            name              = "Test Presentation ${presentations.size + 1}",
                            startTime         = LocalDateTime.now(),
                            endTime           = LocalDateTime.now().plusHours(1),
                            location          = "Room 101",
                            speakerName       = "Dev Speaker",
                            speakerEndpointId = mesh.myId,
                            status            = edu.uwm.cs595.goup11.backend.network.PresentationStatus.ACTIVE
                        )
                    )
                }
            }
        }
        if (uiState == MeshUiState.Scanning || discovered.isNotEmpty()) {
            DevCard("DISCOVERED (${discovered.size})") {
                if (discovered.isEmpty()) {
                    DevHintText("Scanning…")
                } else {
                    discovered.forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                DevMonoText(event.title.ifBlank { event.sessionId }, bold = true)
                                DevMonoText(
                                    text = "session: ${event.sessionId}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    size = 10.sp
                                )
                                DevMonoText(
                                    text = "venue: ${event.venue}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    size = 10.sp
                                )
                            }

                            DevButton(
                                text = "Join",
                                color = Color(0xFF1B8A4E),
                                modifier = Modifier
                            ) {
                                scope.launch { mesh.joinEvent(event.sessionId) }
                            }
                        }
                    }
                }
            }
        }

        if (uiState is MeshUiState.InEvent || uiState is MeshUiState.Hosting) {
            val chatTitle = if (selectedPeer != null) {
                "CHAT → ${selectedPeer?.displayName}"
            } else {
                "CHAT (broadcast)"
            }

            DevCard(chatTitle) {
                if (chatMessages.isNotEmpty()) {
                    chatMessages.takeLast(6).forEach { line ->
                        DevMonoText(line, size = 11.sp)
                    }
                } else {
                    DevHintText("No messages yet")
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = chatText,
                        onValueChange = { chatText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = if (selectedPeer != null) {
                                    "Message ${selectedPeer?.displayName}…"
                                } else {
                                    "Broadcast to all…"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        singleLine = true
                    )

                    DevButton(
                        text = "Send",
                        color = Color(0xFF1B8A4E),
                        enabled = chatText.isNotBlank()
                    ) {
                        val outgoing = chatText.trim()
                        val peer = selectedPeer
                        scope.launch {
                            if (peer != null) {
                                mesh.sendDirectMessage(peer.encodedName, outgoing)
                            } else {
                                mesh.sendChat(outgoing)
                            }
                        }
                        chatText = ""
                    }
                }

                if (connectedPeers.size > 1) {
                    DevHintText(
                        if (selectedPeer != null) {
                            "Tap the selected peer again to switch back to broadcast."
                        } else {
                            "Tap a peer above to send a direct message."
                        }
                    )
                }
            }
        }

        DevCard(
            title = "LOG (${logs.size})",
            modifier = Modifier.heightIn(min = 100.dp, max = 260.dp)
        ) {
            LazyColumn(state = logState) {
                items(logs) { line ->
                    DevMonoText(
                        text = line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 10.sp
                    )
                }
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────── */
/* MOCK MODE                                                                 */
/* ────────────────────────────────────────────────────────────────────────── */

private suspend fun safelyLeaveMockClient(client: MockClient?){
    if (client == null) return
    runCatching { client.leaveSession() }
    delay(250)
}

private suspend fun safelyShutdownMockClient(client: MockClient?) {
    if (client == null) return
    runCatching { client.leaveSession() }
    delay(250)
    runCatching { client.shutdown() }
    delay(150)
}

@Composable
private fun MockDevContent() {
    val scope = rememberCoroutineScope()

    var hostClient by remember { mutableStateOf<MockClient?>(null) }
    var attendeeClient by remember { mutableStateOf<MockClient?>(null) }

    val logs = remember { mutableStateListOf<String>() }
    val inbox = remember { mutableStateListOf<String>() }

    var sessionName by rememberSaveable { mutableStateOf("MockEvent") }
    var hostName by rememberSaveable { mutableStateOf("Host") }
    var attendeeName by rememberSaveable { mutableStateOf("Attendee") }
    var customMessage by rememberSaveable { mutableStateOf("Hello from Attendee") }

    var hostReady by remember { mutableStateOf(false) }
    var attendeeReady by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    val mockLogState = rememberLazyListState()

    fun appendLog(message: String) {
        logs.add(message)
    }

    suspend fun resetScenario(clearLogs: Boolean = false) {
        isBusy = true

        safelyLeaveMockClient(attendeeClient)
        safelyLeaveMockClient(hostClient)

        attendeeClient = null
        hostClient = null
        attendeeReady = false
        hostReady = false
        inbox.clear()
        if (clearLogs) logs.clear()

        runCatching { MockClient.purgeNetwork() }
        delay(200)

        appendLog("Mock scenario reset")
        isBusy = false
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            mockLogState.animateScrollToItem(logs.lastIndex)
        }
    }

    LaunchedEffect(hostClient) {
        val current = hostClient ?: return@LaunchedEffect
        current.messages.collectLatest { message ->
            val text = message.data?.toString(StandardCharsets.UTF_8).orEmpty()
            inbox.add("Host received ← ${message.from}: $text")
            appendLog("Host received message: $text")
        }
    }

    LaunchedEffect(attendeeClient) {
        val current = attendeeClient ?: return@LaunchedEffect
        current.messages.collectLatest { message ->
            val text = message.data?.toString(StandardCharsets.UTF_8).orEmpty()
            inbox.add("Attendee received ← ${message.from}: $text")
            appendLog("Attendee received message: $text")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch {
                safelyShutdownMockClient(attendeeClient)
                safelyShutdownMockClient(hostClient)
                attendeeClient = null
                hostClient = null
                runCatching { MockClient.purgeNetwork() }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DevInfoBanner(
            title = "Mock Mode",
            subtitle = "Manual test harness for create-event, send-message, and receive-message workflows using MockClient."
        )

        DevCard("WORKFLOW SETUP") {
            DevField("Session Name") {
                DevInput(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    placeholder = "e.g. MockEvent",
                    enabled = !isBusy
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    DevField("Host Name") {
                        DevInput(
                            value = hostName,
                            onValueChange = { hostName = it },
                            placeholder = "Host",
                            enabled = !isBusy
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    DevField("Attendee Name") {
                        DevInput(
                            value = attendeeName,
                            onValueChange = { attendeeName = it },
                            placeholder = "Attendee",
                            enabled = !isBusy
                        )
                    }
                }
            }
        }

        DevCard("TASK CHECKLIST") {
            MockStatusRow(
                label = "Create event workflow",
                value = if (hostReady) "Verified" else "Not run"
            )
            HorizontalDivider()
            MockStatusRow(
                label = "Send message workflow",
                value = if (logs.any { it.contains("sent message", ignoreCase = true) }) "Verified" else "Not run"
            )
            HorizontalDivider()
            MockStatusRow(
                label = "Receive message workflow",
                value = if (inbox.isNotEmpty()) "Verified" else "Not run"
            )
        }

        DevCard("ACTIONS") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevButton(
                    text = "Create Host",
                    color = Color(0xFF1B8A4E),
                    enabled = !isBusy && sessionName.isNotBlank() && hostName.isNotBlank()
                ) {
                    scope.launch {
                        isBusy = true
                        runCatching {
                            if (hostClient != null || attendeeClient != null) {
                                resetScenario(clearLogs = false)
                            }

                            logs.clear()
                            inbox.clear()

                            val host = MockClient(
                                displayName = hostName.trim(),
                                role = UserRole.ADMIN
                            )
                            hostClient = host
                            host.createSession(sessionName.trim())

                            hostReady = true
                            attendeeReady = false

                            appendLog("Host '${hostName.trim()}' created session '${sessionName.trim()}'")
                        }.onFailure { e ->
                            appendLog("Failed to create host session: ${e.message}")
                            hostClient = null
                            hostReady = false
                        }
                        isBusy = false
                    }
                }

                DevButton(
                    text = "Join Attendee",
                    color = Color(0xFF0B6EBD),
                    enabled = !isBusy && hostReady && !attendeeReady && sessionName.isNotBlank() && attendeeName.isNotBlank()
                ) {
                    scope.launch {
                        isBusy = true
                        runCatching {
                            safelyLeaveMockClient(attendeeClient)
                            attendeeClient = null
                            delay(150)

                            val attendee = MockClient(
                                displayName = attendeeName.trim(),
                                role = UserRole.ATTENDEE
                            )
                            attendeeClient = attendee
                            attendee.joinSession(sessionName.trim())

                            attendeeReady = true
                            appendLog("Attendee '${attendeeName.trim()}' joined session '${sessionName.trim()}'")
                        }.onFailure { e ->
                            appendLog("Failed to join attendee: ${e.message}")
                            attendeeClient = null
                            attendeeReady = false
                        }
                        isBusy = false
                    }
                }

                DevButton(
                    text = "Reset",
                    color = Color(0xFFB45309),
                    enabled = !isBusy && (hostClient != null || attendeeClient != null || logs.isNotEmpty() || inbox.isNotEmpty())
                ) {
                    scope.launch {
                        resetScenario(clearLogs = false)
                    }
                }
            }
        }

        DevCard("PARTICIPANTS") {
            MockParticipantRow(
                title = "Host",
                name = hostClient?.displayName ?: "Not created",
                status = if (hostReady) "Session created" else "Idle",
                endpointId = hostClient?.endpointId
            )

            HorizontalDivider()

            MockParticipantRow(
                title = "Attendee",
                name = attendeeClient?.displayName ?: "Not joined",
                status = if (attendeeReady) "Joined session" else "Idle",
                endpointId = attendeeClient?.endpointId
            )
        }

        DevCard("MESSAGE TESTING") {
            OutlinedTextField(
                value = customMessage,
                onValueChange = { customMessage = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Type a test message",
                        fontFamily = FontFamily.Monospace
                    )
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevButton(
                    text = "Attendee → Host",
                    color = Color(0xFF1B8A4E),
                    enabled = !isBusy && hostReady && attendeeReady && customMessage.isNotBlank()
                ) {
                    val target = hostClient?.endpointId
                    val sender = attendeeClient
                    if (target == null || sender == null) {
                        appendLog("Cannot send: host or attendee not ready")
                        return@DevButton
                    }

                    scope.launch {
                        isBusy = true
                        runCatching {
                            sender.sendChat(customMessage.trim(), target)
                            appendLog("Attendee sent message to host: ${customMessage.trim()}")
                            delay(150)
                        }.onFailure { e ->
                            appendLog("Failed sending attendee → host: ${e.message}")
                        }
                        isBusy = false
                    }
                }

                DevButton(
                    text = "Host → Attendee",
                    color = Color(0xFF7C3AED),
                    enabled = !isBusy && hostReady && attendeeReady && customMessage.isNotBlank()
                ) {
                    val target = attendeeClient?.endpointId
                    val sender = hostClient
                    if (target == null || sender == null) {
                        appendLog("Cannot send: host or attendee not ready")
                        return@DevButton
                    }

                    scope.launch {
                        isBusy = true
                        runCatching {
                            sender.sendChat(customMessage.trim(), target)
                            appendLog("Host sent message to attendee: ${customMessage.trim()}")
                            delay(150)
                        }.onFailure { e ->
                            appendLog("Failed sending host → attendee: ${e.message}")
                        }
                        isBusy = false
                    }
                }
            }

            DevHintText(
                "Recommended order: Create Host → Join Attendee → Send messages. Reset before starting a new scenario."
            )
        }

        DevCard("RECEIVED MESSAGES (${inbox.size})") {
            if (inbox.isEmpty()) {
                DevHintText("No messages received yet")
            } else {
                inbox.takeLast(10).forEach { line ->
                    DevMonoText(line, size = 11.sp)
                }
            }
        }

        DevCard(
            title = "MOCK LOG (${logs.size})",
            modifier = Modifier.heightIn(min = 100.dp, max = 260.dp)
        ) {
            LazyColumn(state = mockLogState) {
                items(logs) { line ->
                    DevMonoText(
                        text = line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MockParticipantRow(
    title: String,
    name: String,
    status: String,
    endpointId: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        DevMonoText(name, bold = true, size = 12.sp)
        DevMonoText(
            text = "status: $status",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 10.sp
        )
        DevMonoText(
            text = "endpoint: ${endpointId ?: "n/a"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 10.sp
        )
    }
}

@Composable
private fun MockStatusRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────── */
/* DIRECT MODE                                                                */
/* ────────────────────────────────────────────────────────────────────────── */

@Composable
private fun DirectDevContent() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Mirror DevNetworkActivity's state fields
    val clientState        = remember { mutableStateOf<Client?>(null) }
    val eventNameState     = remember { mutableStateOf<String?>(null) }
    val discoveredEvents   = remember { mutableStateListOf<DiscoveredEvent>() }
    val isDiscovering      = remember { mutableStateOf(false) }
    val isAdvertising      = remember { mutableStateOf(false) }
    val isNodeDiscovering  = remember { mutableStateOf(false) }
    val permissionError    = remember { mutableStateOf<String?>(null) }

    var networkStateJob    by remember { mutableStateOf<Job?>(null) }
    var discoverJob        by remember { mutableStateOf<Job?>(null) }
    var scanNet            by remember { mutableStateOf<ConnectNetwork?>(null) }

    // Permissions required by Nearby Connections
    val requiredPerms = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            else -> arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun allGranted(): Boolean {
        return requiredPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = requiredPerms.all { results[it] == true }
        if (granted) {
            pendingAction?.invoke()
        } else {
            permissionError.value = "Bluetooth & location permissions are required"
        }
        pendingAction = null
    }

    fun requestPerms(onGranted: () -> Unit) {
        if (allGranted()) {
            onGranted()
            return
        }
        pendingAction = onGranted
        permLauncher.launch(requiredPerms)
    }

    fun wireClientEvents(c: Client) {
        networkStateJob?.cancel()
        networkStateJob = scope.launch {
            launch { c.network?.isAdvertising?.collect { isAdvertising.value = it } }
            launch { c.network?.isDiscovering?.collect { isNodeDiscovering.value = it } }
        }
    }

    fun createNetwork(name: String, event: String, topo: String, maxPeers: Int) {
        val net = ConnectNetwork(context = context, scope = scope)
        val topology = when (topo) {
            "msh" -> MeshTopology(maxPeerCount = maxPeers)
            else -> SnakeTopology(maxPeerCount = maxPeers)
        }
        val client = Client(displayName = name, network = net, scope = scope)
        client.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(client)
        scope.launch { client.createNetwork(event, topology) }
        clientState.value = client
        eventNameState.value = event
    }

    fun joinNetwork(name: String, event: String) {
        val net = ConnectNetwork(context = context, scope = scope)
        val client = Client(displayName = name, network = net, scope = scope)
        client.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(client)
        scope.launch { client.joinNetwork(event) }
        clientState.value = client
        eventNameState.value = event
    }

    fun joinFromDiscover(name: String, event: String) {
        val net = scanNet ?: run {
            joinNetwork(name, event)
            return
        }

        discoverJob?.cancel()
        discoverJob = null
        isDiscovering.value = false
        scanNet = null

        val client = Client(displayName = name, network = net, scope = scope)
        client.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(client)
        scope.launch { client.joinNetwork(event) }
        clientState.value = client
        eventNameState.value = event
    }

    fun startDiscover() {
        if (isDiscovering.value) return

        discoveredEvents.clear()
        isDiscovering.value = true

        val net = ConnectNetwork(context = context, scope = scope)
        net.init("SCANNER:${java.util.UUID.randomUUID()}", Network.Config(defaultTtl = 5))
        scanNet = net

        discoverJob = scope.launch {
            net.startDiscovery()
            net.events
                .filterIsInstance<NetworkEvent.EndpointDiscovered>()
                .collect { event ->
                    val decoded = AdvertisedName.decode(event.encodedName) ?: return@collect
                    val existing = discoveredEvents.indexOfFirst {
                        it.eventName == decoded.eventName
                    }
                    val entry = DiscoveredEvent(
                        eventName = decoded.eventName,
                        topologyCode = decoded.topologyCode,
                        hostName = decoded.displayName,
                        endpointId = event.endpointId
                    )
                    if (existing == -1) {
                        discoveredEvents.add(entry)
                    } else {
                        discoveredEvents[existing] = entry
                    }
                }
        }
    }

    fun stopDiscover() {
        discoverJob?.cancel()
        discoverJob = null
        isDiscovering.value = false
    }

    fun sendMessage(toEndpointId: String, body: String) {
        val client = clientState.value ?: return
        val from = client.endpointId ?: return
        client.sendMessage(
            Message(
                to = toEndpointId,
                from = from,
                type = MessageType.TEXT_MESSAGE,
                ttl = 5,
                data = body.toByteArray(Charsets.UTF_8)
            )
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            stopDiscover()
            scope.launch { clientState.value?.leaveNetwork() }
        }
    }

    // Collect the directory's network-wide active peer list reactively
    val networkPeers by produceState<List<PeerEntry>>(
        initialValue = emptyList(),
        key1         = clientState.value
    ) {
        val c = clientState.value
        if (c == null) { value = emptyList() }
        else c.networkPeersFlow.collect { value = it }
    }
    val peersExcludingSelf = networkPeers.filter {
        it.endpointId != clientState.value?.endpointId
    }

    // ── Render DevNetworkScreen ───────────────────────────────────────────────
    DevNetworkScreen(
        client             = clientState.value,
        networkPeers       = peersExcludingSelf,
        eventName          = eventNameState.value,
        discoveredEvents   = discoveredEvents,
        isDiscovering      = isDiscovering.value,
        isAdvertising      = isAdvertising.value,
        isNodeDiscovering  = isNodeDiscovering.value,
        permissionError    = permissionError.value,
        onCreateNetwork    = { name, event, topo, maxPeers ->
            requestPerms { createNetwork(name, event, topo, maxPeers) }
        },
        onJoinNetwork = { name, event ->
            requestPerms { joinNetwork(name, event) }
        },
        onJoinDiscovered = { name, event ->
            requestPerms { joinFromDiscover(name, event) }
        },
        onLeaveNetwork = {
            scope.launch {
                networkStateJob?.cancel()
                networkStateJob = null
                clientState.value?.leaveNetwork()
                clientState.value = null
                eventNameState.value = null
                isAdvertising.value = false
                isNodeDiscovering.value = false
                scanNet = null
            }
        },
        onSendMessage = { to, body ->
            sendMessage(to, body)
        },
        onStartDiscover = {
            requestPerms { startDiscover() }
        },
        onStopDiscover = {
            stopDiscover()
        },
        onStartAdvertising = {
            toggleAdvertising(clientState.value, scope)
        },
        onStopAdvertising = {
            toggleAdvertising(clientState.value, scope, start = false)
        },
        onStartNodeDiscovery = {
            toggleNodeDiscovery(clientState.value, scope, start = true)
        },
        onStopNodeDiscovery = {
            toggleNodeDiscovery(clientState.value, scope, start = false)
        },
        onPermissionErrorDismissed = {
            permissionError.value = null
        }
    )
}

private fun toggleAdvertising(
    client: Client?,
    scope: CoroutineScope,
    start: Boolean = true
) {
    val network = client?.network ?: return
    val encodedName = client.endpointId ?: return
    if (start) {
        network.startAdvertising(encodedName)
    } else {
        network.stopAdvertising()
    }
}

private fun toggleNodeDiscovery(
    client: Client?,
    scope: CoroutineScope,
    start: Boolean
) {
    val network = client?.network ?: return
    if (start) {
        scope.launch { network.startDiscovery() }
    } else {
        scope.launch { network.stopDiscovery() }
    }
}

/* ────────────────────────────────────────────────────────────────────────── */
/* Shared helpers                                                             */
/* ────────────────────────────────────────────────────────────────────────── */

@Composable
private fun DevInfoBanner(
    title: String,
    subtitle: String
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DevCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun DevField(
    label: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun DevInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = {
            Text(
                text = placeholder,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        },
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        ),
        singleLine = true
    )
}

@Composable
private fun DevButton(
    text: String,
    color: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.12f),
            contentColor = color,
            disabledContainerColor = color.copy(alpha = 0.05f),
            disabledContentColor = color.copy(alpha = 0.35f)
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DevMonoText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: androidx.compose.ui.unit.TextUnit = 12.sp,
    bold: Boolean = false
) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color
    )
}

@Composable
private fun DevHintText(
    text: String
) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}