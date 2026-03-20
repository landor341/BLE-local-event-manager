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
 * The interface is kept compatible with RealMeshGateway.
 * DefaultBackendFacade bridges the old API surface to the current backend.
 *
 * Key additions from PR:
 *  - myRole: UserRole — frontend-only concept, not in the backend
 *  - createNetwork(eventName, topology) — new overload with explicit topology
 *  - removeMessageListener — needed for clean leaveEvent teardown
 */
interface BackendFacade {

    val myId:    String
    /** The local user's role — frontend-only, not carried in backend messages. */
    val myRole:  UserRole

    val state:            StateFlow<NetworkState>
    val events:           SharedFlow<NetworkEvent>
    val currentSessionId: StateFlow<String?>

    fun start()

    /**
     * Update the display name used for the next createNetwork/joinNetwork call.
     * The client is recreated with the new name on the next network operation.
     */
    fun setDisplayName(name: String)

    fun scanNetworks(): Flow<String>
    suspend fun stopScan()

    @Deprecated("Defaults to SnakeTopology. Use createNetwork(eventName, topology) instead.",
        ReplaceWith("createNetwork(eventName, TopologyChoice.SNAKE)"))
    suspend fun createNetwork(eventName: String)

    /** Create a network with an explicit topology choice. */
    suspend fun createNetwork(eventName: String, topology: TopologyChoice)

    suspend fun joinNetwork(sessionId: String)

    fun leave()

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

    private var displayName: String = myId

    // Client is created lazily so the display name can be updated before
    // the first network operation via setDisplayName().
    private var _client: Client? = null
    private val client: Client
        get() = _client ?: Client(
            displayName = displayName,
            network     = network,
            scope       = scope
        ).also {
            // attachNetwork wires onConnectionRequest and the message listener.
            // Must be called before any createNetwork/joinNetwork/scanNetworks.
            it.attachNetwork(network, config)
            _client = it
        }

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
        scope.launch {
            kotlinx.coroutines.flow.combine(
                network.isAdvertising,
                network.isDiscovering
            ) { advertising, discovering ->
                val eventName = currentEventName
                when {
                    advertising && eventName != null -> NetworkState.Joined(eventName)
                    advertising                     -> NetworkState.Hosting(eventName ?: "")
                    discovering                     -> NetworkState.Scanning
                    else                            -> NetworkState.Idle
                }
            }.collect { _state.value = it }
        }

        scope.launch {
            network.events.collect { ev ->
                when (ev) {
                    is NetworkEvent.EndpointConnected -> {
                        @Suppress("DEPRECATION")
                        _events.tryEmit(NetworkEvent.PeerConnected(
                            edu.uwm.cs595.goup11.backend.network.DeprecatedPeer(ev.endpointId)
                        ))
                        currentEventName?.let { _events.tryEmit(NetworkEvent.Joined(it)) }
                    }
                    is NetworkEvent.EndpointDisconnected ->
                        _events.tryEmit(NetworkEvent.PeerDisconnected(ev.endpointId))
                    else -> _events.tryEmit(ev)
                }
            }
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    override fun scanNetworks(): Flow<String> {
        scope.launch {
            // Init with a transient scanner identity so ConnectNetwork is ready
            // before startDiscovery() is called. This mirrors what Client.joinNetwork
            // does with "JOINING:$displayName" before scanning for a host.
            network.init("SCANNER:${displayName}", Network.Config(defaultTtl = 5))
            network.startDiscovery()
        }
        return network.events
            .filterIsInstance<NetworkEvent.EndpointDiscovered>()
            .map { ev -> AdvertisedName.decode(ev.encodedName)?.eventName ?: ev.encodedName }
    }

    override suspend fun stopScan() {
        network.stopDiscovery()
        // Reset so a subsequent createNetwork/joinNetwork gets a clean init
        // with the proper identity rather than the scanner placeholder.
        currentEventName = null
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
        // No peer reference returned — topology handles all routing internally.
    }

    override fun setDisplayName(name: String) {
        displayName = name
        // Invalidate the cached client so the next operation uses the new name
        _client = null
    }

    override fun leave() {
        scope.launch {
            client.leaveNetwork()
            currentEventName = null
        }
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    override fun sendMessage(to: String, message: Message) {
        client.sendMessage(message)
    }

    override fun addMessageListener(listener: (Message) -> Unit) {
        network.addListener(listener)
    }

    override fun removeMessageListener(listener: (Message) -> Unit) {
        network.removeListener(listener)
    }
}