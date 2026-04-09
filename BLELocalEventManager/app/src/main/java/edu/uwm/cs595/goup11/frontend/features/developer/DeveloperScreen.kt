package edu.uwm.cs595.goup11.frontend.features.developer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uwm.cs595.goup11.backend.network.*
import edu.uwm.cs595.goup11.backend.network.PeerEntry
import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshUiState
import edu.uwm.cs595.goup11.frontend.core.mesh.GatewayPeer
import edu.uwm.cs595.goup11.frontend.core.mesh.TopologyChoice
import edu.uwm.cs595.goup11.frontend.dev.DevNetworkScreen
import edu.uwm.cs595.goup11.frontend.dev.DiscoveredEvent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Dev mode selector
// ─────────────────────────────────────────────────────────────────────────────

private enum class DevMode { GATEWAY, DIRECT }

/**
 * DeveloperScreen — entry point wired into the nav graph.
 *
 * Presents a toggle between two sub-modes:
 *
 *  • GATEWAY — drives the full MeshGateway stack (BackendFacade → Client →
 *    ConnectNetwork). Useful for testing the frontend integration layer.
 *
 *  • DIRECT — bypasses the gateway entirely and calls Client/ConnectNetwork
 *    functions directly (the original DevNetworkActivity behaviour). Useful
 *    for low-level topology debugging without frontend abstractions in the way.
 */
@Composable
fun DeveloperScreen(
    mesh:   MeshGateway,
    onBack: () -> Unit
) {
    var mode by remember { mutableStateOf(DevMode.GATEWAY) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFFFFF))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            // Mode toggle — placed left of title so it never overlaps the nav FAB
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                DevMode.entries.forEach { m ->
                    val selected = mode == m
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) Color(0xFF1B8A4E) else Color(0xFFF1F3F5),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { mode = m }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            m.name,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else Color(0xFF6B7280)
                        )
                    }
                }
            }
            Text(
                "Developer",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider()

        // ── Mode content ─────────────────────────────────────────────────────
        when (mode) {
            DevMode.GATEWAY -> GatewayDevContent(mesh = mesh)
            DevMode.DIRECT  -> DirectDevContent()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GATEWAY MODE — drives MeshGateway
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GatewayDevContent(mesh: MeshGateway) {
    val scope = rememberCoroutineScope()
    val uiState by mesh.state.collectAsState()

    val logs        = remember { mutableStateListOf<String>() }
    val chatMessages = remember { mutableStateListOf<String>() }

    // Collect gateway logs and chat
    LaunchedEffect(mesh) {
        launch { mesh.start() }
        launch { mesh.logs.collect  { logs.add(it) } }
        launch { mesh.chat.collect  { chatMessages.add("[${it.sender.take(8)}] ${it.text}") } }
    }

    val connectedPeers by mesh.connectedPeers.collectAsState()
    var selectedPeer by remember { mutableStateOf<GatewayPeer?>(null) }

    // Auto-select only peer when there is exactly one
    LaunchedEffect(connectedPeers) {
        if (connectedPeers.size == 1) selectedPeer = connectedPeers.first()
        else if (selectedPeer != null && connectedPeers.none { it.endpointId == selectedPeer!!.endpointId })
            selectedPeer = null
    }

    var eventName    by remember { mutableStateOf("") }
    var displayName  by remember { mutableStateOf("") }
    var chatText     by remember { mutableStateOf("") }
    var topoChoice   by remember { mutableStateOf(TopologyChoice.SNAKE) }
    val logState     = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logState.animateScrollToItem(logs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // Status
        GwCard("STATUS") {
            val label = when (val s = uiState) {
                is MeshUiState.Idle       -> "Idle"
                is MeshUiState.Scanning   -> "Scanning…"
                is MeshUiState.Advertising -> "Advertising"
                is MeshUiState.InEvent    -> "In event: ${s.sessionId}"
                is MeshUiState.Error      -> "Error: ${s.reason}"
                else                      -> s::class.simpleName ?: "Unknown"
            }
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }

        // Config
        GwCard("CONFIG") {
            GwField("Display Name") {
                GwInput(
                    value         = displayName,
                    onValueChange = {
                        displayName = it
                        mesh.setDisplayName(it.trim())
                    },
                    placeholder   = "e.g. Alice",
                    enabled       = uiState == MeshUiState.Idle
                )
            }
            GwField("Event Name") {
                GwInput(eventName, { eventName = it }, "e.g. TechConf",
                    enabled = uiState == MeshUiState.Idle)
            }
            GwField("Topology") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TopologyChoice.entries.forEach { t ->
                        val sel = topoChoice == t
                        Box(
                            modifier = Modifier
                                .background(
                                    if (sel) Color(0xFF0B6EBD).copy(alpha = 0.12f) else Color(0xFFF8F9FA),
                                    RoundedCornerShape(4.dp)
                                )
                                .border(1.dp,
                                    if (sel) Color(0xFF0B6EBD) else Color(0xFFDEE2E6),
                                    RoundedCornerShape(4.dp))
                                .clickable { topoChoice = t }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(t.code, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = if (sel) Color(0xFF0B6EBD) else Color(0xFF6B7280))
                        }
                    }
                }
            }
        }

        // Actions
        GwCard("ACTIONS") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GwButton("Host", Color(0xFF1B8A4E),
                    enabled = uiState == MeshUiState.Idle && eventName.isNotBlank()
                ) { scope.launch { mesh.hostEvent(eventName, topoChoice) } }

                GwButton("Scan", Color(0xFF0B6EBD),
                    enabled = uiState == MeshUiState.Idle
                ) { scope.launch { mesh.startScanning() } }

                GwButton("Stop Scan", Color(0xFFB45309),
                    enabled = uiState == MeshUiState.Scanning
                ) { scope.launch { mesh.stopScanning() } }

                GwButton("Leave", Color(0xFFDC2626),
                    enabled = uiState is MeshUiState.InEvent
                ) { scope.launch { mesh.leaveEvent() } }
            }
        }

        // Peers
        if (connectedPeers.isNotEmpty() || uiState is MeshUiState.InEvent) {
            GwCard("PEERS (${connectedPeers.size})") {
                if (connectedPeers.isEmpty()) {
                    Text("No peers connected yet",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = Color(0xFF6B7280))
                } else {
                    connectedPeers.forEach { peer ->
                        val isSelected = selectedPeer?.endpointId == peer.endpointId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) Color(0xFF1B8A4E).copy(alpha = 0.08f)
                                    else Color(0xFFF8F9FA),
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF1B8A4E).copy(alpha = 0.5f)
                                    else Color(0xFFDEE2E6),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    selectedPeer = if (isSelected) null else peer
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier.size(8.dp).background(
                                    Color(0xFF1B8A4E), RoundedCornerShape(50)
                                )
                            )
                            Column(Modifier.weight(1f)) {
                                Text(peer.displayName,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color(0xFF1B8A4E) else Color(0xFF1A1D23))
                                Text(peer.endpointId,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp, color = Color(0xFF6B7280))
                            }
                            if (isSelected) {
                                Text("selected", fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp, color = Color(0xFF1B8A4E))
                            }
                        }
                    }
                }
            }
        }

        // Discovered events (only while scanning)
        if (uiState == MeshUiState.Scanning) {
            val discovered = remember { mutableStateListOf<edu.uwm.cs595.goup11.frontend.core.mesh.DiscoveredEventSummary>() }
            LaunchedEffect(mesh) {
                mesh.discoveredEvents.collect { discovered.add(it) }
            }
            GwCard("DISCOVERED (${discovered.size})") {
                if (discovered.isEmpty()) {
                    Text("Scanning…", fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp, color = Color(0xFF6B7280))
                } else {
                    discovered.forEach { ev ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ev.sessionId, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("host: ${ev.hostName}  topo: ${ev.topologyCode}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp, color = Color(0xFF6B7280))
                            }
                            GwButton("Join", Color(0xFF1B8A4E), modifier = Modifier.height(32.dp)) {
                                scope.launch { mesh.joinEvent(ev.sessionId) }
                            }
                        }
                    }
                }
            }
        }

        // Chat
        if (uiState is MeshUiState.InEvent) {
            val chatTitle = if (selectedPeer != null) "CHAT → ${selectedPeer!!.displayName}"
            else "CHAT (broadcast)"
            GwCard(chatTitle) {
                if (chatMessages.isNotEmpty()) {
                    chatMessages.takeLast(6).forEach { m ->
                        Text(m, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                } else {
                    Text("No messages yet", fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp, color = Color(0xFF6B7280))
                }
                if (connectedPeers.isEmpty()) {
                    Text("No peers connected", fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp, color = Color(0xFF6B7280))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = chatText,
                            onValueChange = { chatText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (selectedPeer != null) "Message ${selectedPeer!!.displayName}…"
                                    else "Broadcast to all…",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp, color = Color(0xFF6B7280))
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )
                        GwButton("Send", Color(0xFF1B8A4E),
                            enabled = chatText.isNotBlank()
                        ) {
                            val text = chatText
                            val peer = selectedPeer
                            scope.launch {
                                if (peer != null) mesh.sendDirectMessage(peer.encodedName, text)
                                else mesh.sendChat(text)
                            }
                            chatText = ""
                        }
                    }
                    if (connectedPeers.size > 1) {
                        Text(
                            if (selectedPeer != null) "Tap peer above to deselect and broadcast"
                            else "Tap a peer above to send directly",
                            fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
            }
        }

        // Log
        GwCard("LOG (${logs.size})", modifier = Modifier.heightIn(min = 80.dp, max = 240.dp)) {
            LazyColumn(state = logState) {
                items(logs) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        color = Color(0xFF374151),
                        modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DIRECT MODE — raw Client/ConnectNetwork, no gateway in the way
// Hosts all the state that DevNetworkActivity previously held as Activity fields.
// ─────────────────────────────────────────────────────────────────────────────

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
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun allGranted() = requiredPerms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = requiredPerms.all { results[it] == true }
        if (granted) pendingAction?.invoke()
        else permissionError.value = "Bluetooth & location permissions are required"
        pendingAction = null
    }

    fun requestPerms(onGranted: () -> Unit) {
        if (allGranted()) { onGranted(); return }
        pendingAction = onGranted
        permLauncher.launch(requiredPerms)
    }

    // ── Helpers (mirrors DevNetworkActivity private funs) ────────────────────

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
            else  -> SnakeTopology(maxPeerCount = maxPeers)
        }
        val c = Client(displayName = name, network = net, scope = scope)
        c.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(c)
        scope.launch { c.createNetwork(event, topology) }
        clientState.value    = c
        eventNameState.value = event
    }

    fun joinNetwork(name: String, event: String) {
        val net = ConnectNetwork(context = context, scope = scope)
        val c = Client(displayName = name, network = net, scope = scope)
        c.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(c)
        scope.launch { c.joinNetwork(event) }
        clientState.value    = c
        eventNameState.value = event
    }

    fun joinFromDiscover(name: String, event: String) {
        val net = scanNet ?: run { joinNetwork(name, event); return }
        discoverJob?.cancel()
        discoverJob = null
        isDiscovering.value = false
        scanNet = null
        val c = Client(displayName = name, network = net, scope = scope)
        c.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(c)
        scope.launch { c.joinNetwork(event) }
        clientState.value    = c
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
            net.events.filterIsInstance<NetworkEvent.EndpointDiscovered>().collect { ev ->
                val decoded = AdvertisedName.decode(ev.encodedName) ?: return@collect
                val existing = discoveredEvents.indexOfFirst { it.eventName == decoded.eventName }
                val entry = DiscoveredEvent(
                    eventName    = decoded.eventName,
                    topologyCode = decoded.topologyCode,
                    hostName     = decoded.displayName,
                    endpointId   = ev.endpointId
                )
                if (existing == -1) discoveredEvents.add(entry)
                else discoveredEvents[existing] = entry
            }
        }
    }

    fun stopDiscover() {
        discoverJob?.cancel()
        discoverJob = null
        isDiscovering.value = false
    }

    fun sendMessage(toEndpointId: String, body: String) {
        val c    = clientState.value ?: return
        val from = c.endpointId      ?: return
        c.sendMessage(Message(
            to   = toEndpointId,
            from = from,
            type = MessageType.TEXT_MESSAGE,
            ttl  = 5,
            data = body.toByteArray(Charsets.UTF_8)
        ))
    }

    // Cleanup when composable leaves composition
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
        onJoinNetwork      = { name, event ->
            requestPerms { joinNetwork(name, event) }
        },
        onJoinDiscovered   = { name, event ->
            requestPerms { joinFromDiscover(name, event) }
        },
        onLeaveNetwork     = {
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
        onSendMessage        = { to, body -> sendMessage(to, body) },
        onStartDiscover      = {
            requestPerms { startDiscover() }
        },
        onStopDiscover       = { stopDiscover() },
        onStartAdvertising   = { toggleAdvertising(clientState.value, scope) },
        onStopAdvertising    = { toggleAdvertising(clientState.value, scope, start = false) },
        onStartNodeDiscovery = { toggleNodeDiscovery(clientState.value, scope, start = true) },
        onStopNodeDiscovery  = { toggleNodeDiscovery(clientState.value, scope, start = false) },
        onPermissionErrorDismissed = { permissionError.value = null }
    )
}

private fun toggleAdvertising(client: Client?, scope: CoroutineScope, start: Boolean = true) {
    val net         = client?.network ?: return
    val encodedName = client.endpointId ?: return
    if (start) net.startAdvertising(encodedName) else net.stopAdvertising()
}

private fun toggleNodeDiscovery(client: Client?, scope: CoroutineScope, start: Boolean) {
    val net = client?.network ?: return
    if (start) scope.launch { net.startDiscovery() }
    else       scope.launch { net.stopDiscovery() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gateway mode UI helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GwCard(
    title:    String,
    modifier: Modifier = Modifier,
    content:  @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFFDEE2E6), RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            color = Color(0xFF6B7280), letterSpacing = 0.1.sp)
        content()
    }
}

@Composable
private fun GwField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF6B7280))
        content()
    }
}

@Composable
private fun GwInput(
    value:         String,
    onValueChange: (String) -> Unit,
    placeholder:   String,
    enabled:       Boolean = true
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = { Text(placeholder, fontFamily = FontFamily.Monospace,
            fontSize = 12.sp, color = Color(0xFF6B7280)) },
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        singleLine = true
    )
}

@Composable
private fun GwButton(
    text:     String,
    color:    Color,
    enabled:  Boolean = true,
    modifier: Modifier = Modifier,
    onClick:  () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor         = color.copy(alpha = 0.12f),
            contentColor           = color,
            disabledContainerColor = color.copy(alpha = 0.04f),
            disabledContentColor   = color.copy(alpha = 0.3f)
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}