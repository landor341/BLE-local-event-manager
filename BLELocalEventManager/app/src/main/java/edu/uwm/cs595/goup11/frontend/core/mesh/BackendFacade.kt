package edu.uwm.cs595.goup11.frontend.core.mesh

import android.content.Context
import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.ClientType
import edu.uwm.cs595.goup11.backend.network.ConnectNetwork
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Network
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.PeerEntry
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.backend.network.topology.HubAndSpokeTopology
import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * BackendFacade — BACKEND ISOLATION LAYER (Frontend-only)
 *
 * Bridges the old API surface to the current backend.
 */
interface BackendFacade {

    val myId:    String
    val myRole:  UserRole

    val state:            StateFlow<NetworkState>
    val events:           SharedFlow<NetworkEvent>
    val currentSessionId: StateFlow<String?>

    val localEncodedName: String?
    val networkPeers:     StateFlow<List<PeerEntry>>

    fun start()

    fun setDisplayName(name: String)

    fun scanNetworks(): Flow<String>
    suspend fun stopScan()

    @Deprecated("Defaults to SnakeTopology. Use createNetwork(eventName, topology) instead.",
        ReplaceWith("createNetwork(eventName, TopologyChoice.SNAKE)"))
    suspend fun createNetwork(eventName: String)

    suspend fun createNetwork(eventName: String, topology: TopologyChoice)

    suspend fun joinNetwork(sessionId: String)

    suspend fun leave()

    fun sendMessage(to: String, message: Message)

    fun addMessageListener(listener: (Message) -> Unit)

    fun removeMessageListener(listener: (Message) -> Unit)
}

@Suppress("DEPRECATION")
class DefaultBackendFacade(
    private val context: Context,
    override val myId:   String   = "android-client",
    override val myRole: UserRole = UserRole.ATTENDEE,

    @Deprecated("Moved to topology")
    private val clientType: ClientType = ClientType.LEAF,

    private val useRealNearby: Boolean = true,
    private val config: Network.Config = Network.Config(defaultTtl = 5),
    private val scope: CoroutineScope  = CoroutineScope(Dispatchers.Default)
) : BackendFacade {

    private val network: Network =
        if (useRealNearby) ConnectNetwork(context = context, scope = scope)
        else LocalNetwork()

    // ── Peers ─────────────────────────────────────────────────────────────────

    private val _networkPeers = MutableStateFlow<List<PeerEntry>>(emptyList())
    override val networkPeers: StateFlow<List<PeerEntry>> = _networkPeers.asStateFlow()

    override val localEncodedName: String?
        get() = _client?.endpointId

    // ── Display name ──────────────────────────────────────────────────────────

    private var displayName: String = myId

    // ── Client ────────────────────────────────────────────────────────────────

    // Client is created lazily so the display name can be updated before
    // the first network operation via setDisplayName().
    // _client is nulled in leave() so each session gets a fresh instance
    // with attachNetwork() called, which re-registers networkMessageListener.
    private var _client: Client? = null
    private val client: Client
        get() = _client ?: Client(
            displayName = displayName,
            network     = network,
            scope       = scope
        ).also {
            it.attachNetwork(network, config)
            // Bridge app listeners into the new client instance
            it.addMessageListener { msg -> appListeners.forEach { l -> l(msg) } }
            _client = it
        }

    // ── Application-layer message listeners ───────────────────────────────────

    // We maintain our own listener list and route through Client so the
    // Client → network listener chain is never bypassed.
    private val appListeners = mutableListOf<(Message) -> Unit>()

    // ── Synthesised state ─────────────────────────────────────────────────────

    private var currentEventName: String? = null

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Idle)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, replay = 1)
    override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    @Deprecated("No longer meaningful")
    override val currentSessionId: StateFlow<String?> = MutableStateFlow(null)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun start() {
        // Eagerly create client so networkPeersFlow is stable from the start
        // and attachNetwork() + listener bridge are wired before any events arrive
        val c = client

        scope.launch {
            c.networkPeersFlow.collect { _networkPeers.value = it }
        }

        scope.launch {
            kotlinx.coroutines.flow.combine(
                network.isAdvertising,
                network.isDiscovering
            ) { advertising, discovering ->
                val eventName = currentEventName
                val result = when {
                    advertising && eventName != null -> NetworkState.Joined(eventName)
                    advertising                      -> NetworkState.Hosting(eventName ?: "")
                    discovering                      -> NetworkState.Scanning
                    else                             -> NetworkState.Idle
                }
                android.util.Log.d("BackendFacade", "isAdvertising=$advertising isDiscovering=$discovering eventName=$eventName => ${result::class.simpleName}")
                result
            }.collect { _state.value = it }
        }

        scope.launch {
            network.events.collect { ev ->
                when (ev) {
                    is NetworkEvent.EndpointConnected    -> {
                        _events.tryEmit(ev)
                        currentEventName?.let { _events.tryEmit(NetworkEvent.Joined(it)) }
                    }
                    is NetworkEvent.EndpointDisconnected -> _events.tryEmit(ev)
                    else                                 -> _events.tryEmit(ev)
                }
            }
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    override fun scanNetworks(): Flow<String> {
        android.util.Log.d("BackendFacade", "scanNetworks: client=${client.hashCode()}")
        scope.launch {
            runCatching {
                android.util.Log.d("BackendFacade", "scanNetworks: calling client.startScan()")
                client.startScan()
                android.util.Log.d("BackendFacade", "scanNetworks: startScan returned")
            }.onFailure { e ->
                android.util.Log.e("BackendFacade", "scanNetworks: startScan FAILED: ${e::class.simpleName}: ${e.message}")
            }
        }
        return client.discoveredEvents()
    }

    override suspend fun stopScan() {
        client.stopScan()
    }

    // ── Hosting / Joining ─────────────────────────────────────────────────────

    @Deprecated("Defaults to SnakeTopology. Use createNetwork(eventName, topology) instead.",
        ReplaceWith("createNetwork(eventName, TopologyChoice.SNAKE)"))
    override suspend fun createNetwork(eventName: String) {
        createNetwork(eventName, TopologyChoice.SNAKE)
    }

    override suspend fun createNetwork(eventName: String, topology: TopologyChoice) {
        currentEventName = eventName
        val topo = when (topology) {
            TopologyChoice.SNAKE         -> SnakeTopology()
            TopologyChoice.MESH          -> MeshTopology()
            TopologyChoice.HUB_AND_SPOKE -> HubAndSpokeTopology()
        }
        client.createNetwork(eventName, topo)
    }

    override suspend fun joinNetwork(sessionId: String) {
        currentEventName = sessionId
        client.joinNetwork(sessionId)
    }

    override fun setDisplayName(name: String) {
        if (name == displayName) return
        displayName = name
        // Only invalidate client if not in a session — name takes effect next session
        if (currentEventName == null) {
            _client = null
        }
    }

    override suspend fun leave() {
        client.leaveNetwork()
        currentEventName = null
        // Null client so the next session gets a fresh instance — this ensures
        // attachNetwork() is called again, re-registering networkMessageListener
        // with the network transport which leaveNetwork() removed.
        _client = null
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    override fun sendMessage(to: String, message: Message) {
        client.sendMessage(message)
    }

    // Route through appListeners which are bridged into Client in the lazy getter,
    // so messages flow: network → Client.networkMessageListener → Client.messageListeners
    // → appListeners bridge → RealMeshGateway.onBackendMessage
    override fun addMessageListener(listener: (Message) -> Unit) {
        appListeners.add(listener)
    }

    override fun removeMessageListener(listener: (Message) -> Unit) {
        appListeners.remove(listener)
    }
}