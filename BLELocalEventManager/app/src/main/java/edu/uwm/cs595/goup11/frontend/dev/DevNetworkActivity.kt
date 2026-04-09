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
private val BgColor      = Color(0xFFF8F9FA)
private val SurfaceColor = Color(0xFFFFFFFF)
private val BorderColor  = Color(0xFFDEE2E6)
private val GreenColor   = Color(0xFF1B8A4E)
private val BlueColor    = Color(0xFF0B6EBD)
private val AmberColor   = Color(0xFFB45309)
private val RedColor     = Color(0xFFDC2626)
private val TextColor    = Color(0xFF1A1D23)
private val TextDimColor = Color(0xFF6B7280)
private val Mono         = FontFamily.Monospace

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────
class DevNetworkActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val nearbyPerms = NearbyPermissions(this)   // must be created in constructor, not onCreate

    // Compose-observable state — changing these triggers recomposition
    private val clientState          = mutableStateOf<Client?>(null)
    private val eventNameState       = mutableStateOf<String?>(null)
    private val discoveredEvents     = mutableStateListOf<DiscoveredEvent>()
    private val isDiscoveringState   = mutableStateOf(false)
    private val isAdvertisingState   = mutableStateOf(false)
    private val isNodeDiscovering    = mutableStateOf(false)  // client's own discovery state
    private val permissionError      = mutableStateOf<String?>(null)
    private var discoverJob: kotlinx.coroutines.Job? = null
    private var networkStateJob: kotlinx.coroutines.Job? = null

    // Held so joinFromDiscover() can reuse the already-active scan network
    // instead of creating a new one and hitting STATUS_ALREADY_DISCOVERING.
    private var scanNet: ConnectNetwork? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Collect the directory's network-wide active peer list reactively.
            // Falls back to empty when offline (client is null).
            val networkPeers by produceState<List<PeerEntry>>(
                initialValue = emptyList(),
                key1         = clientState.value
            ) {
                val c = clientState.value
                if (c == null) { value = emptyList() }
                else c.networkPeersFlow.collect { value = it }
            }

            // Exclude self so the UI never shows the local node in the peer list
            val peersExcludingSelf = networkPeers.filter {
                it.endpointId != clientState.value?.endpointId
            }

            DevNetworkScreen(
                client             = clientState.value,
                networkPeers       = peersExcludingSelf,
                eventName          = eventNameState.value,
                discoveredEvents   = discoveredEvents,
                isDiscovering      = isDiscoveringState.value,
                isAdvertising      = isAdvertisingState.value,
                isNodeDiscovering  = isNodeDiscovering.value,
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
                        if (granted) joinFromDiscover(name, event)
                        else permissionError.value = "Bluetooth & location permissions are required"
                    }
                },
                onLeaveNetwork     = {
                    scope.launch {
                        // Cancel event collection FIRST so incoming PINGs/PONGs
                        // are no longer processed while we're tearing down.
                        networkStateJob?.cancel()
                        networkStateJob = null
                        clientState.value?.leaveNetwork()
                        clientState.value = null
                        eventNameState.value = null
                        isAdvertisingState.value = false
                        isNodeDiscovering.value = false
                        scanNet = null
                    }
                },
                onSendMessage        = { to, body -> sendMessage(to, body) },
                onStartDiscover      = {
                    nearbyPerms.request(this) { granted ->
                        if (granted) startDiscover()
                        else permissionError.value = "Bluetooth & location permissions are required"
                    }
                },
                onStopDiscover       = { stopDiscover() },
                onStartAdvertising   = { toggleAdvertising(true) },
                onStopAdvertising    = { toggleAdvertising(false) },
                onStartNodeDiscovery = { toggleNodeDiscovery(true) },
                onStopNodeDiscovery  = { toggleNodeDiscovery(false) },
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
     * Wire radio state observers. Peer tracking is now handled entirely by the
     * directory's networkPeersFlow, collected reactively in setContent above.
     */
    private fun wireClientEvents(c: Client) {
        networkStateJob?.cancel()
        networkStateJob = scope.launch {
            launch { c.network?.isAdvertising?.collect { isAdvertisingState.value = it } }
            launch { c.network?.isDiscovering?.collect { isNodeDiscovering.value  = it } }
        }
    }

    private fun toggleAdvertising(start: Boolean) {
        val net = clientState.value?.network ?: return
        val encodedName = clientState.value?.endpointId ?: return
        if (start) net.startAdvertising(encodedName)
        else net.stopAdvertising()
    }

    private fun toggleNodeDiscovery(start: Boolean) {
        val net = clientState.value?.network ?: return
        if (start) scope.launch { net.startDiscovery() }
        else scope.launch { net.stopDiscovery() }
    }

    private fun startDiscover() {
        if (isDiscoveringState.value) return
        discoveredEvents.clear()
        isDiscoveringState.value = true

        val net = ConnectNetwork(context = this, scope = scope)
        net.init("SCANNER:${java.util.UUID.randomUUID()}", Network.Config(defaultTtl = 5))
        scanNet = net

        discoverJob = scope.launch {
            net.startDiscovery()
            net.events
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

    /**
     * Join an event using the already-running scan network from the Discover tab.
     * Reuses [scanNet] so we don't hit STATUS_ALREADY_DISCOVERING by starting
     * a second discovery on a fresh ConnectNetwork instance.
     */
    private fun joinFromDiscover(name: String, event: String) {
        val net = scanNet ?: run {
            // Scan wasn't started — fall back to a fresh join
            joinNetwork(name, event)
            return
        }
        discoverJob?.cancel()
        discoverJob = null
        isDiscoveringState.value = false
        scanNet = null

        val c = Client(displayName = name, network = net, scope = scope)
        c.attachNetwork(net, Network.Config(defaultTtl = 5))
        wireClientEvents(c)
        scope.launch { c.joinNetwork(event) }
        clientState.value    = c
        eventNameState.value = event
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
    networkPeers:              List<PeerEntry>,  // all ACTIVE peers network-wide, self excluded
    eventName:                 String?,
    discoveredEvents:          List<DiscoveredEvent>,
    isDiscovering:             Boolean,
    isAdvertising:             Boolean,
    isNodeDiscovering:         Boolean,
    permissionError:           String?,
    onCreateNetwork:           (name: String, event: String, topo: String, maxPeers: Int) -> Unit,
    onJoinNetwork:             (name: String, event: String) -> Unit,
    onJoinDiscovered:          (name: String, event: String) -> Unit,
    onLeaveNetwork:            () -> Unit,
    onSendMessage:             (to: String, body: String) -> Unit,
    onStartDiscover:           () -> Unit,
    onStopDiscover:            () -> Unit,
    onStartAdvertising:        () -> Unit,
    onStopAdvertising:         () -> Unit,
    onStartNodeDiscovery:      () -> Unit,
    onStopNodeDiscovery:       () -> Unit,
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
                    eventName         = eventName,
                    networkPeers      = networkPeers,
                    selfId            = endpointId ?: "",
                    isAdvertising     = isAdvertising,
                    isNodeDiscovering = isNodeDiscovering,
                    onStartAdvertising    = onStartAdvertising,
                    onStopAdvertising     = onStopAdvertising,
                    onStartNodeDiscovery  = onStartNodeDiscovery,
                    onStopNodeDiscovery   = onStopNodeDiscovery,
                    onLeave    = {
                        logLines.addLine("Left network '$eventName'", LogLevel.WARN)
                        onLeaveNetwork()
                    }
                )
                selectedTab - offset == 0 -> NetworkTab(
                    isOnline          = isOnline,
                    endpointId        = endpointId,
                    isAdvertising     = isAdvertising,
                    isNodeDiscovering = isNodeDiscovering,
                    onCreateNetwork   = { name, event, topo, max ->
                        logLines.addLine("Created '$event' as $name [$topo]", LogLevel.OK)
                        onCreateNetwork(name, event, topo, max)
                    },
                    onJoinNetwork     = { name, event ->
                        logLines.addLine("Joining '$event' as $name…", LogLevel.INFO)
                        onJoinNetwork(name, event)
                    },
                    onLeaveNetwork    = {
                        logLines.addLine("Left network", LogLevel.WARN)
                        onLeaveNetwork()
                    },
                    onStartAdvertising   = onStartAdvertising,
                    onStopAdvertising    = onStopAdvertising,
                    onStartNodeDiscovery = onStartNodeDiscovery,
                    onStopNodeDiscovery  = onStopNodeDiscovery,
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
                    networkPeers  = networkPeers,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text       = "DEV PAGE",
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
        if (endpointId != null) {
            Text(
                text       = endpointId.shortName(),
                fontFamily = Mono,
                fontSize   = 11.sp,
                color      = TextDimColor,
                letterSpacing = 0.1.sp
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
    onStartDiscover:  () -> Unit,
    onStopDiscover:   () -> Unit,
    onJoin:           (name: String, eventName: String) -> Unit,
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DevButton(
                    text     = if (isDiscovering) "● Scanning…" else "⬡ Scan",
                    color    = if (isDiscovering) AmberColor else GreenColor,
                    enabled  = !isDiscovering,
                    modifier = Modifier.weight(1f)
                ) { onStartDiscover() }

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
// Event Tab — shows all peers known to the directory, not just direct neighbors
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EventTab(
    eventName:            String,
    networkPeers:         List<PeerEntry>,
    selfId:               String,
    isAdvertising:        Boolean,
    isNodeDiscovering:    Boolean,
    onStartAdvertising:   () -> Unit,
    onStopAdvertising:    () -> Unit,
    onStartNodeDiscovery: () -> Unit,
    onStopNodeDiscovery:  () -> Unit,
    onLeave:              () -> Unit,
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
            DevKV("peers",  networkPeers.size.toString())
            DevKV("status", "active", valueColor = GreenColor)
        }

        // Radio controls
        RadioControls(
            isAdvertising        = isAdvertising,
            isDiscovering        = isNodeDiscovering,
            onStartAdvertising   = onStartAdvertising,
            onStopAdvertising    = onStopAdvertising,
            onStartDiscovering   = onStartNodeDiscovery,
            onStopDiscovering    = onStopNodeDiscovery,
        )

        // Network-wide peer list from the directory
        DevCard(title = "NETWORK PEERS (${networkPeers.size})") {
            if (networkPeers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Waiting for peers to join…",
                        fontFamily = Mono, fontSize = 11.sp, color = TextDimColor
                    )
                }
            } else {
                networkPeers.forEach { peer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgColor, RoundedCornerShape(4.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(GreenColor, RoundedCornerShape(50)))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                peer.displayName,
                                fontFamily = Mono, fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, color = TextColor
                            )
                            Text(
                                peer.endpointId.shortName(),
                                fontFamily = Mono, fontSize = 8.sp,
                                color = TextDimColor, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        // Lamport clock badge — useful for verifying directory convergence on-device
                        Box(
                            modifier = Modifier
                                .background(BlueColor.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                                .border(1.dp, BlueColor.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "c:${peer.lamportClock}",
                                fontFamily = Mono, fontSize = 8.sp, color = BlueColor
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
// Radio Controls — manual advertising / discovery toggles
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RadioControls(
    isAdvertising:     Boolean,
    isDiscovering:     Boolean,
    onStartAdvertising:  () -> Unit,
    onStopAdvertising:   () -> Unit,
    onStartDiscovering:  () -> Unit,
    onStopDiscovering:   () -> Unit,
) {
    DevCard(title = "RADIO") {
        // Advertising row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("ADVERTISING", fontFamily = Mono, fontSize = 10.sp, color = TextDimColor)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (isAdvertising) GreenColor else TextDimColor,
                                RoundedCornerShape(50)
                            )
                    )
                    Text(
                        if (isAdvertising) "broadcasting" else "silent",
                        fontFamily = Mono, fontSize = 11.sp,
                        color = if (isAdvertising) GreenColor else TextDimColor
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevButton(
                    text    = "ON",
                    color   = GreenColor,
                    enabled = !isAdvertising,
                    modifier = Modifier.width(64.dp)
                ) { onStartAdvertising() }
                DevButton(
                    text    = "OFF",
                    color   = RedColor,
                    enabled = isAdvertising,
                    modifier = Modifier.width(64.dp)
                ) { onStopAdvertising() }
            }
        }

        HorizontalDivider(color = BorderColor, thickness = 1.dp)

        // Discovery row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("DISCOVERING", fontFamily = Mono, fontSize = 10.sp, color = TextDimColor)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (isDiscovering) BlueColor else TextDimColor,
                                RoundedCornerShape(50)
                            )
                    )
                    Text(
                        if (isDiscovering) "scanning" else "idle",
                        fontFamily = Mono, fontSize = 11.sp,
                        color = if (isDiscovering) BlueColor else TextDimColor
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevButton(
                    text    = "ON",
                    color   = BlueColor,
                    enabled = !isDiscovering,
                    modifier = Modifier.width(64.dp)
                ) { onStartDiscovering() }
                DevButton(
                    text    = "OFF",
                    color   = RedColor,
                    enabled = isDiscovering,
                    modifier = Modifier.width(64.dp)
                ) { onStopDiscovering() }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 1 — Network
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun NetworkTab(
    isOnline:            Boolean,
    endpointId:          String?,
    isAdvertising:       Boolean,
    isNodeDiscovering:   Boolean,
    onCreateNetwork:     (String, String, String, Int) -> Unit,
    onJoinNetwork:       (String, String) -> Unit,
    onLeaveNetwork:      () -> Unit,
    onStartAdvertising:  () -> Unit,
    onStopAdvertising:   () -> Unit,
    onStartNodeDiscovery: () -> Unit,
    onStopNodeDiscovery:  () -> Unit,
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

        // ── Radio controls (only when online) ──
        if (isOnline) {
            RadioControls(
                isAdvertising      = isAdvertising,
                isDiscovering      = isNodeDiscovering,
                onStartAdvertising = onStartAdvertising,
                onStopAdvertising  = onStopAdvertising,
                onStartDiscovering = onStartNodeDiscovery,
                onStopDiscovering  = onStopNodeDiscovery,
            )
        }

        // ── State card ──
        DevCard(title = "NODE STATE") {
            DevKV("status",   if (isOnline) "connected" else "disconnected",
                valueColor = if (isOnline) GreenColor else RedColor)
            DevKV("advert",   if (isAdvertising) "on" else "off",
                valueColor = if (isAdvertising) GreenColor else TextDimColor)
            DevKV("discover", if (isNodeDiscovering) "on" else "off",
                valueColor = if (isNodeDiscovering) BlueColor else TextDimColor)
            DevKV("name",     name.ifBlank { "—" })
            DevKV("event",    event.ifBlank { "—" })
            DevKV("topology", topo)
            DevKV("encoded",  endpointId ?: "—", smallText = true)
            DevKV("nearby id","(shown per-peer below)", smallText = true)
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
    networkPeers:  List<PeerEntry>,
    messages:      List<ChatMessage>,
    onSendMessage: (to: String, body: String) -> Unit,
) {
    var selectedPeer by remember { mutableStateOf<PeerEntry?>(null) }
    var body         by remember { mutableStateOf("") }
    val listState    = rememberLazyListState()

    // Auto-select the only peer if there's exactly one
    LaunchedEffect(networkPeers) {
        if (networkPeers.size == 1) selectedPeer = networkPeers.first()
        else if (selectedPeer != null && networkPeers.none { it.endpointId == selectedPeer!!.endpointId })
            selectedPeer = null
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Peer picker
        DevCard(title = "RECIPIENT") {
            if (networkPeers.isEmpty()) {
                Text("No peers in network yet", fontFamily = Mono, fontSize = 11.sp, color = TextDimColor)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    networkPeers.forEach { peer ->
                        val selected = selectedPeer?.endpointId == peer.endpointId
                        Box(
                            modifier = Modifier
                                .background(
                                    if (selected) GreenColor.copy(alpha = 0.12f) else BgColor,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (selected) GreenColor.copy(alpha = 0.6f) else BorderColor,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { selectedPeer = peer }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text(peer.displayName, fontFamily = Mono, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) GreenColor else TextColor)
                                Text(peer.endpointId, fontFamily = Mono, fontSize = 9.sp,
                                    color = TextDimColor, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
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
                        MessageBubble(msg = msg, selfId = endpointId ?: "", networkPeers = networkPeers)
                    }
                }
            }
        }

        // Compose row
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value         = body,
                onValueChange = { body = it },
                modifier      = Modifier.weight(1f).heightIn(min = 56.dp, max = 120.dp),
                placeholder   = {
                    Text(
                        if (selectedPeer != null) "Message ${selectedPeer!!.displayName}…"
                        else "Select a peer first…",
                        fontFamily = Mono, fontSize = 12.sp, color = TextDimColor
                    )
                },
                textStyle = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 13.sp, color = TextColor),
                colors    = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = GreenColor,
                    unfocusedBorderColor    = BorderColor,
                    cursorColor             = GreenColor,
                    focusedContainerColor   = SurfaceColor,
                    unfocusedContainerColor = SurfaceColor,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        val peer = selectedPeer
                        if (body.isNotBlank() && peer != null && isOnline) {
                            // Route by endpointId — topology handles forwarding to non-direct peers
                            onSendMessage(peer.endpointId, body.trim())
                            body = ""
                        }
                    }
                )
            )
            DevButton(
                text    = "↑",
                color   = GreenColor,
                enabled = isOnline && body.isNotBlank() && selectedPeer != null,
                modifier = Modifier.size(56.dp)
            ) {
                selectedPeer?.let { peer ->
                    onSendMessage(peer.endpointId, body.trim())
                    body = ""
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, selfId: String, networkPeers: List<PeerEntry>) {
    val isSent = msg.from == selfId || msg.sent
    val senderName = if (isSent) "me"
    else networkPeers.find { it.endpointId == msg.from }?.displayName ?: msg.from.shortName()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (isSent) Color(0xFF1B8A4E).copy(alpha = 0.08f) else Color(0xFFF0F7FF),
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
                    text       = "${if (isSent) "→" else "←"} $senderName · ${msg.ts}",
                    fontFamily = Mono, fontSize = 9.sp, color = TextDimColor,
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
                .background(Color(0xFFF1F3F5), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
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