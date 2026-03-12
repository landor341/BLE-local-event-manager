package edu.uwm.cs595.goup11.frontend.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uwm.cs595.goup11.backend.network.*
import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Colour tokens — dark terminal theme
// ─────────────────────────────────────────────────────────────────────────────
private val BgColor      = Color(0xFF080A0D)
private val SurfaceColor = Color(0xFF111318)
private val BorderColor  = Color(0xFF1E2229)
private val GreenColor   = Color(0xFF00E676)
private val BlueColor    = Color(0xFF40C4FF)
private val AmberColor   = Color(0xFFFFB300)
private val RedColor     = Color(0xFFFF3D57)
private val TextColor    = Color(0xFFB8C4D4)
private val TextDimColor = Color(0xFF4A5568)
private val Mono         = FontFamily.Monospace

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────
class DevNetworkActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val nearbyPerms = NearbyPermissions(this)   // must be created in constructor, not onCreate

    // Compose-observable state — changing these triggers recomposition
    private val clientState        = mutableStateOf<Client?>(null)
    private val peersState         = mutableStateListOf<String>()
    private val eventNameState     = mutableStateOf<String?>(null)
    private val discoveredEvents   = mutableStateListOf<DiscoveredEvent>()
    private val isDiscoveringState = mutableStateOf(false)
    private val permissionError    = mutableStateOf<String?>(null)
    private var discoverJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DevNetworkScreen(
                client             = clientState.value,
                peers              = peersState,
                eventName          = eventNameState.value,
                discoveredEvents   = discoveredEvents,
                isDiscovering      = isDiscoveringState.value,
                permissionError    = permissionError.value,
                onCreateNetwork    = { name, event, topo, maxPeers ->
                    nearbyPerms.request(this) { granted ->
                        if (granted) createNetwork(name, event, topo, maxPeers)
                        else permissionError.value = "Bluetooth & location permissions are required"
                    }
                },
                onJoinNetwork      = { name, event ->
                    nearbyPerms.request(this) { granted ->
                        if (granted) joinNetwork(name, event)
                        else permissionError.value = "Bluetooth & location permissions are required"
                    }
                },
                onJoinDiscovered   = { name, event ->
                    nearbyPerms.request(this) { granted ->
                        if (granted) joinNetwork(name, event)
                        else permissionError.value = "Bluetooth & location permissions are required"
                    }
                },
                onLeaveNetwork     = {
                    scope.launch { clientState.value?.leaveNetwork() }
                    clientState.value = null
                    peersState.clear()
                    eventNameState.value = null
                },
                onSendMessage      = { to, body -> sendMessage(to, body) },
                onStartDiscover    = { name ->
                    nearbyPerms.request(this) { granted ->
                        if (granted) startDiscover(name)
                        else permissionError.value = "Bluetooth & location permissions are required"
                    }
                },
                onStopDiscover     = { stopDiscover() },
                onPermissionErrorDismissed = { permissionError.value = null }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscover()
        scope.launch { clientState.value?.leaveNetwork() }
        scope.cancel()
    }

    // ── Backend wiring ────────────────────────────────────────────────────────

    private fun createNetwork(name: String, event: String, topo: String, maxPeers: Int) {
        val net = ConnectNetwork(context = this, scope = scope)
        val c   = Client(displayName = name, network = net, scope = scope)
        val topology = when (topo) {
            "msh" -> MeshTopology(maxPeerCount = maxPeers)
            else  -> SnakeTopology(maxPeerCount = maxPeers)
        }
        c.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(c)
        scope.launch { c.createNetwork(event, topology) }
        clientState.value    = c
        eventNameState.value = event
    }

    private fun joinNetwork(name: String, event: String) {
        val net = ConnectNetwork(context = this, scope = scope)
        val c   = Client(displayName = name, network = net, scope = scope)
        c.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(c)
        scope.launch { c.joinNetwork(event) }
        clientState.value    = c
        eventNameState.value = event
    }

    /**
     * Listen to network events so the UI peer list stays in sync.
     * Runs on the Main dispatcher so mutableStateListOf updates are safe.
     */
    private fun wireClientEvents(c: Client) {
        peersState.clear()
        scope.launch {
            c.network?.events?.collect { ev ->
                when (ev) {
                    is NetworkEvent.EndpointConnected    -> {
                        if (!peersState.contains(ev.endpointId))
                            peersState.add(ev.endpointId)
                    }
                    is NetworkEvent.EndpointDisconnected -> peersState.remove(ev.endpointId)
                    else -> Unit
                }
            }
        }
    }

    /**
     * Spin up a temporary ConnectNetwork just for scanning.
     * We call init() with a dummy identity so requireClient() passes,
     * but we never advertise so we won't appear in other nodes' discovery results.
     */
    private fun startDiscover(scannerName: String) {
        if (isDiscoveringState.value) return
        discoveredEvents.clear()
        isDiscoveringState.value = true

        discoverJob = scope.launch {
            val scanNet = ConnectNetwork(context = this@DevNetworkActivity, scope = scope)
            // init() with a temporary identity — needed so connectionsClient is initialised
            scanNet.init("SCANNER:$scannerName", Network.Config(defaultTtl = 5))
            scanNet.startDiscovery()
            scanNet.events
                .filterIsInstance<NetworkEvent.EndpointDiscovered>()
                .collect { ev ->
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

    private fun stopDiscover() {
        discoverJob?.cancel()
        discoverJob = null
        isDiscoveringState.value = false
    }

    private fun sendMessage(toEndpointId: String, body: String) {
        val c    = clientState.value ?: return
        val from = c.endpointId      ?: return
        c.sendMessage(
            Message(
                to   = toEndpointId,
                from = from,
                type = MessageType.TEXT_MESSAGE,
                ttl  = 5,
                data = body.toByteArray(Charsets.UTF_8)
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root screen — tabs
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DevNetworkScreen(
    client:                    Client?,
    peers:                     List<String>,
    eventName:                 String?,
    discoveredEvents:          List<DiscoveredEvent>,
    isDiscovering:             Boolean,
    permissionError:           String?,
    onCreateNetwork:           (name: String, event: String, topo: String, maxPeers: Int) -> Unit,
    onJoinNetwork:             (name: String, event: String) -> Unit,
    onJoinDiscovered:          (name: String, event: String) -> Unit,
    onLeaveNetwork:            () -> Unit,
    onSendMessage:             (to: String, body: String) -> Unit,
    onStartDiscover:           (scannerName: String) -> Unit,
    onStopDiscover:            () -> Unit,
    onPermissionErrorDismissed: () -> Unit,
) {
    val isOnline   = client?.isConnected() == true
    val endpointId = client?.endpointId
    val logLines   = remember { mutableStateListOf<LogLine>() }
    val messages   = remember { mutableStateListOf<ChatMessage>() }

    // Base tabs always present; event tab appears once online
    val baseTabs = listOf("Network", "Discover", "Messages", "Log")
    val tabs     = if (isOnline && eventName != null) listOf(eventName) + baseTabs else baseTabs
    var selectedTab by remember { mutableStateOf(0) }

    // When we go online, jump straight to the event tab
    LaunchedEffect(isOnline) {
        if (isOnline) selectedTab = 0
    }

    // Register message listener whenever the client changes
    DisposableEffect(client) {
        client?.addMessageListener { msg ->
            val body = msg.data?.toString(Charsets.UTF_8) ?: return@addMessageListener
            messages.add(ChatMessage(from = msg.from, body = body, sent = false))
            logLines.addLine("← [${msg.from.shortName()}] $body", LogLevel.INFO)
        }
        onDispose { }
    }

    Surface(color = BgColor, modifier = Modifier.fillMaxSize()) {
        Column {
            DevHeader(isOnline = isOnline, endpointId = endpointId)

            // Permission error banner
            if (permissionError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RedColor.copy(alpha = 0.15f))
                        .border(1.dp, RedColor.copy(alpha = 0.4f), RoundedCornerShape(0.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        permissionError,
                        fontFamily = Mono, fontSize = 11.sp, color = RedColor,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onPermissionErrorDismissed) {
                        Text("✕", fontFamily = Mono, fontSize = 12.sp, color = RedColor)
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = SurfaceColor,
                contentColor     = GreenColor,
                indicator = { tabPositions ->
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color    = GreenColor
                    )
                }
            ) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = selectedTab == i,
                        onClick  = { selectedTab = i },
                        text     = {
                            Text(
                                text       = title.uppercase(),
                                fontFamily = Mono,
                                fontSize   = 11.sp,
                                color      = if (selectedTab == i) GreenColor else TextDimColor
                            )
                        }
                    )
                }
            }

            // Resolve which content to show based on tab index and whether event tab exists
            val offset = if (isOnline && eventName != null) 1 else 0
            when {
                isOnline && eventName != null && selectedTab == 0 -> EventTab(
                    eventName  = eventName,
                    peers      = peers,
                    selfId     = endpointId ?: "",
                    onLeave    = {
                        logLines.addLine("Left network '$eventName'", LogLevel.WARN)
                        onLeaveNetwork()
                    }
                )
                selectedTab - offset == 0 -> NetworkTab(
                    isOnline        = isOnline,
                    endpointId      = endpointId,
                    onCreateNetwork = { name, event, topo, max ->
                        logLines.addLine("Created '$event' as $name [$topo]", LogLevel.OK)
                        onCreateNetwork(name, event, topo, max)
                    },
                    onJoinNetwork   = { name, event ->
                        logLines.addLine("Joining '$event' as $name…", LogLevel.INFO)
                        onJoinNetwork(name, event)
                    },
                    onLeaveNetwork  = {
                        logLines.addLine("Left network", LogLevel.WARN)
                        onLeaveNetwork()
                    }
                )
                selectedTab - offset == 1 -> DiscoverTab(
                    isDiscovering    = isDiscovering,
                    discoveredEvents = discoveredEvents,
                    onStartDiscover  = onStartDiscover,
                    onStopDiscover   = onStopDiscover,
                    onJoin           = { name, event ->
                        logLines.addLine("Joining '$event' as $name…", LogLevel.INFO)
                        onJoinDiscovered(name, event)
                    }
                )
                selectedTab - offset == 2 -> MessagesTab(
                    isOnline      = isOnline,
                    endpointId    = endpointId,
                    messages      = messages,
                    onSendMessage = { to, body ->
                        messages.add(ChatMessage(from = endpointId ?: "", body = body, sent = true))
                        logLines.addLine("→ [${to.shortName()}] $body", LogLevel.OK)
                        onSendMessage(to, body)
                    }
                )
                else -> LogTab(lines = logLines, onClear = { logLines.clear() })
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DevHeader(isOnline: Boolean, endpointId: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text       = "MESH · DEV",
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize   = 16.sp,
            color      = GreenColor,
            letterSpacing = 0.15.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        color = if (isOnline) GreenColor else TextDimColor,
                        shape = RoundedCornerShape(50)
                    )
            )
            Text(
                text       = if (isOnline) "ONLINE" else "OFFLINE",
                fontFamily = Mono,
                fontSize   = 11.sp,
                color      = if (isOnline) GreenColor else TextDimColor
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Discover Tab — scan for active events and join one
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DiscoverTab(
    isDiscovering:    Boolean,
    discoveredEvents: List<DiscoveredEvent>,
    onStartDiscover:  (scannerName: String) -> Unit,
    onStopDiscover:   () -> Unit,
    onJoin:           (name: String, eventName: String) -> Unit,
) {
    var scannerName  by remember { mutableStateOf("") }
    var joinName     by remember { mutableStateOf("") }
    var joiningEvent by remember { mutableStateOf<DiscoveredEvent?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Scanner controls ──
        DevCard(title = "SCANNER") {
            DevField(label = "Your Display Name") {
                DevInput(
                    value         = scannerName,
                    onValueChange = { scannerName = it },
                    placeholder   = "e.g. Alice",
                    enabled       = !isDiscovering
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DevButton(
                    text     = if (isDiscovering) "● Scanning…" else "⬡ Scan",
                    color    = if (isDiscovering) AmberColor else GreenColor,
                    enabled  = !isDiscovering && scannerName.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { onStartDiscover(scannerName.trim()) }

                DevButton(
                    text     = "■ Stop",
                    color    = RedColor,
                    enabled  = isDiscovering,
                    modifier = Modifier.weight(1f)
                ) { onStopDiscover() }
            }
            if (isDiscovering) {
                Text(
                    "Listening for nearby events…",
                    fontFamily = Mono, fontSize = 10.sp, color = AmberColor
                )
            }
        }

        // ── Results ──
        DevCard(title = "FOUND EVENTS (${discoveredEvents.size})") {
            if (discoveredEvents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isDiscovering) "No events found yet…" else "Press Scan to search",
                        fontFamily = Mono, fontSize = 11.sp, color = TextDimColor
                    )
                }
            } else {
                discoveredEvents.forEach { ev ->
                    val isSelected = joiningEvent?.eventName == ev.eventName
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) GreenColor.copy(alpha = 0.08f) else BgColor,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) GreenColor.copy(alpha = 0.5f) else BorderColor,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { joiningEvent = if (isSelected) null else ev }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ev.eventName,
                                fontFamily = Mono, fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, color = if (isSelected) GreenColor else TextColor
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (ev.topologyCode == "msh") BlueColor.copy(alpha = 0.12f)
                                        else GreenColor.copy(alpha = 0.12f),
                                        RoundedCornerShape(2.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (ev.topologyCode == "msh") BlueColor.copy(alpha = 0.4f)
                                        else GreenColor.copy(alpha = 0.4f),
                                        RoundedCornerShape(2.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    ev.topologyCode.uppercase(),
                                    fontFamily = Mono, fontSize = 9.sp,
                                    color = if (ev.topologyCode == "msh") BlueColor else GreenColor
                                )
                            }
                        }
                        Text(
                            "Host: ${ev.hostName}",
                            fontFamily = Mono, fontSize = 11.sp, color = TextDimColor
                        )
                    }
                }
            }
        }

        // ── Join panel — appears when an event is selected ──
        joiningEvent?.let { ev ->
            DevCard(title = "JOIN · ${ev.eventName}") {
                DevField(label = "Your Display Name") {
                    DevInput(
                        value         = joinName,
                        onValueChange = { joinName = it },
                        placeholder   = "e.g. Alice"
                    )
                }
                DevButton(
                    text     = "→ Join ${ev.eventName}",
                    color    = BlueColor,
                    enabled  = joinName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    onStopDiscover()
                    onJoin(joinName.trim(), ev.eventName)
                    joiningEvent = null
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Event Tab — shown as first tab once online, displays connected peers
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EventTab(
    eventName: String,
    peers:     List<String>,
    selfId:    String,
    onLeave:   () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Event info
        DevCard(title = "EVENT") {
            DevKV("name",   eventName)
            DevKV("self",   selfId.shortName())
            DevKV("peers",  peers.size.toString())
            DevKV("status", "active", valueColor = GreenColor)
        }

        // Peer list
        DevCard(title = "CONNECTED PEERS (${peers.size})") {
            if (peers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Waiting for peers to connect…",
                        fontFamily = Mono, fontSize = 11.sp, color = TextDimColor
                    )
                }
            } else {
                peers.forEach { peerId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgColor, RoundedCornerShape(4.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Green dot
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(GreenColor, RoundedCornerShape(50))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                peerId.shortName(),
                                fontFamily = Mono, fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, color = TextColor
                            )
                            Text(
                                peerId,
                                fontFamily = Mono, fontSize = 9.sp,
                                color = TextDimColor,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Leave button
        DevButton(
            text     = "✕ Leave Event",
            color    = RedColor,
            modifier = Modifier.fillMaxWidth(),
            onClick  = onLeave
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 1 — Network
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun NetworkTab(
    isOnline:        Boolean,
    endpointId:      String?,
    onCreateNetwork: (String, String, String, Int) -> Unit,
    onJoinNetwork:   (String, String) -> Unit,
    onLeaveNetwork:  () -> Unit,
) {
    var name     by remember { mutableStateOf("") }
    var event    by remember { mutableStateOf("") }
    var topo     by remember { mutableStateOf("snk") }
    var maxPeers by remember { mutableStateOf("2") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Config card ──
        DevCard(title = "NODE CONFIG") {
            DevField(label = "Display Name") {
                DevInput(value = name, onValueChange = { name = it },
                    placeholder = "e.g. Alice", enabled = !isOnline)
            }
            DevField(label = "Event Name") {
                DevInput(value = event, onValueChange = { event = it },
                    placeholder = "e.g. TechConf2024", enabled = !isOnline)
            }
            DevField(label = "Topology") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopoChip(label = "Snake", code = "snk", selected = topo == "snk",
                        enabled = !isOnline) { topo = "snk" }
                    TopoChip(label = "Mesh",  code = "msh", selected = topo == "msh",
                        enabled = !isOnline) { topo = "msh" }
                }
            }
            DevField(label = "Max Peers") {
                DevInput(value = maxPeers, onValueChange = { maxPeers = it },
                    placeholder = "2", enabled = !isOnline,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = ImeAction.Done
                    )
                )
            }
        }

        // ── Action buttons ──
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DevButton(
                text    = "⬡ Create",
                color   = GreenColor,
                enabled = !isOnline && name.isNotBlank() && event.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                onCreateNetwork(name.trim(), event.trim(), topo, maxPeers.toIntOrNull() ?: 2)
            }
            DevButton(
                text    = "→ Join",
                color   = BlueColor,
                enabled = !isOnline && name.isNotBlank() && event.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                onJoinNetwork(name.trim(), event.trim())
            }
        }
        DevButton(
            text    = "✕ Leave",
            color   = RedColor,
            enabled = isOnline,
            modifier = Modifier.fillMaxWidth()
        ) { onLeaveNetwork() }

        // ── State card ──
        DevCard(title = "NODE STATE") {
            DevKV("status",   if (isOnline) "connected" else "disconnected",
                valueColor = if (isOnline) GreenColor else RedColor)
            DevKV("name",     name.ifBlank { "—" })
            DevKV("event",    event.ifBlank { "—" })
            DevKV("topology", topo)
            DevKV("endpoint", endpointId ?: "—", smallText = true)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 2 — Messages
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MessagesTab(
    isOnline:      Boolean,
    endpointId:    String?,
    messages:      List<ChatMessage>,
    onSendMessage: (to: String, body: String) -> Unit,
) {
    var recipient by remember { mutableStateOf("") }
    var body      by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Recipient input
        DevCard(title = "RECIPIENT") {
            DevField(label = "Endpoint ID (paste from Node State)") {
                DevInput(
                    value         = recipient,
                    onValueChange = { recipient = it },
                    placeholder   = "EVT:…|N:…",
                    fontSize      = 10.sp
                )
            }
        }

        // Message thread
        DevCard(title = "THREAD", modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center) {
                    Text("No messages yet", fontFamily = Mono, fontSize = 11.sp, color = TextDimColor)
                }
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(messages) { msg ->
                        MessageBubble(msg = msg, selfId = endpointId ?: "")
                    }
                }
            }
        }

        // Compose row
        Row(
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = body,
                onValueChange = { body = it },
                modifier      = Modifier.weight(1f).heightIn(min = 56.dp, max = 120.dp),
                placeholder   = { Text("Type a message…", fontFamily = Mono, fontSize = 12.sp, color = TextDimColor) },
                textStyle     = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 13.sp, color = TextColor),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = GreenColor,
                    unfocusedBorderColor = BorderColor,
                    cursorColor          = GreenColor,
                    focusedContainerColor   = SurfaceColor,
                    unfocusedContainerColor = SurfaceColor,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (body.isNotBlank() && recipient.isNotBlank() && isOnline) {
                            onSendMessage(recipient.trim(), body.trim())
                            body = ""
                        }
                    }
                )
            )
            DevButton(
                text    = "↑",
                color   = GreenColor,
                enabled = isOnline && body.isNotBlank() && recipient.isNotBlank(),
                modifier = Modifier.size(56.dp)
            ) {
                onSendMessage(recipient.trim(), body.trim())
                body = ""
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, selfId: String) {
    val isSent = msg.from == selfId || msg.sent
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (isSent) Color(0x1400E676) else SurfaceColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .border(
                    width = 1.dp,
                    color = if (isSent) GreenColor.copy(alpha = 0.4f) else BorderColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column {
                Text(msg.body, fontFamily = Mono, fontSize = 12.sp,
                    color = if (isSent) GreenColor else TextColor)
                Text(
                    text       = "${if (isSent) "→" else "←"} ${msg.from.shortName()} · ${msg.ts}",
                    fontFamily = Mono,
                    fontSize   = 9.sp,
                    color      = TextDimColor,
                    modifier   = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 3 — Log
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LogTab(lines: List<LogLine>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("ACTIVITY LOG", fontFamily = Mono, fontSize = 10.sp,
                color = TextDimColor, letterSpacing = 0.2.sp)
            TextButton(onClick = onClear) {
                Text("CLEAR", fontFamily = Mono, fontSize = 10.sp, color = TextDimColor)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceColor, RoundedCornerShape(4.dp))
                .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                .padding(10.dp)
        ) {
            if (lines.isEmpty()) {
                Text("No activity yet", fontFamily = Mono, fontSize = 11.sp, color = TextDimColor)
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(lines) { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(line.ts, fontFamily = Mono, fontSize = 10.sp, color = TextDimColor)
                            Text(line.msg, fontFamily = Mono, fontSize = 11.sp,
                                color = when (line.level) {
                                    LogLevel.OK   -> GreenColor
                                    LogLevel.INFO -> BlueColor
                                    LogLevel.WARN -> AmberColor
                                    LogLevel.ERR  -> RedColor
                                })
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable components
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DevCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(4.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
    ) {
        Text(
            text     = title,
            fontFamily = Mono,
            fontSize = 9.sp,
            color    = TextDimColor,
            letterSpacing = 0.2.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131820), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        )
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun DevField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = Mono, fontSize = 9.sp, color = TextDimColor, letterSpacing = 0.1.sp)
        content()
    }
}

@Composable
private fun DevInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    enabled: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        imeAction = ImeAction.Next
    )
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        enabled       = enabled,
        modifier      = Modifier.fillMaxWidth().height(48.dp),
        textStyle     = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = fontSize, color = TextColor),
        placeholder   = { Text(placeholder, fontFamily = Mono, fontSize = fontSize, color = TextDimColor) },
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = GreenColor,
            unfocusedBorderColor    = BorderColor,
            disabledBorderColor     = BorderColor.copy(alpha = 0.4f),
            cursorColor             = GreenColor,
            focusedContainerColor   = BgColor,
            unfocusedContainerColor = BgColor,
            disabledContainerColor  = BgColor,
        ),
        singleLine      = true,
        keyboardOptions = keyboardOptions,
        shape           = RoundedCornerShape(4.dp)
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
        onClick  = onClick,
        enabled  = enabled,
        modifier = modifier.height(48.dp),
        shape    = RoundedCornerShape(4.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor         = color.copy(alpha = 0.12f),
            contentColor           = color,
            disabledContainerColor = color.copy(alpha = 0.04f),
            disabledContentColor   = color.copy(alpha = 0.25f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (enabled) color.copy(alpha = 0.6f) else color.copy(alpha = 0.15f)
        )
    ) {
        Text(text, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, letterSpacing = 0.1.sp)
    }
}

@Composable
private fun TopoChip(
    label: String, code: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit
) {
    val borderColor = when {
        !enabled -> BorderColor
        selected -> GreenColor
        else     -> BorderColor
    }
    val textColor = when {
        !enabled -> TextDimColor
        selected -> GreenColor
        else     -> TextDimColor
    }
    Box(
        modifier = Modifier
            .background(
                if (selected) GreenColor.copy(alpha = 0.10f) else BgColor,
                RoundedCornerShape(4.dp)
            )
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text("$label ($code)", fontFamily = Mono, fontSize = 11.sp, color = textColor)
    }
}

@Composable
private fun DevKV(key: String, value: String, valueColor: Color = TextColor, smallText: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, fontFamily = Mono, fontSize = 11.sp, color = TextDimColor,
            modifier = Modifier.width(72.dp))
        Text(value, fontFamily = Mono, fontSize = if (smallText) 9.sp else 11.sp,
            color = valueColor, modifier = Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────
private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private fun nowTs() = timeFmt.format(Date())

private data class ChatMessage(val from: String, val body: String, val sent: Boolean,
                               val ts: String = nowTs())

private data class LogLine(val msg: String, val level: LogLevel,
                           val ts: String = nowTs())

private enum class LogLevel { OK, INFO, WARN, ERR }

private fun MutableList<LogLine>.addLine(msg: String, level: LogLevel) {
    add(LogLine(msg = msg, level = level))
}

// ── Extract the N: display name from an encoded endpoint ID ──
private fun String.shortName(): String =
    Regex("N:([^|]+)").find(this)?.groupValues?.get(1) ?: this.takeLast(12)

// ─────────────────────────────────────────────────────────────────────────────
// Discovered event model
// ─────────────────────────────────────────────────────────────────────────────
data class DiscoveredEvent(
    val eventName:    String,
    val topologyCode: String,
    val hostName:     String,
    val endpointId:   String,
)