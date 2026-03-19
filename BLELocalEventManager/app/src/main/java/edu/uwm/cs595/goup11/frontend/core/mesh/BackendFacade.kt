package edu.uwm.cs595.goup11.frontend.core.mesh

import android.content.Context
import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.ClientType
import edu.uwm.cs595.goup11.backend.network.ConnectNetwork
import edu.uwm.cs595.goup11.backend.network.DeprecatedPeer
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Network
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.Peer
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Serves as a helper class to allow backend and frontend to communicate
 *
 * NOTE: Many of these methods have changed. DO NOT use methods marked as deprecated
 */
interface BackendFacade {

    val myId: String
    val state: StateFlow<NetworkState>
    val events: SharedFlow<NetworkEvent>
    val currentSessionId: StateFlow<String?>

    fun start()
    fun scanNetworks(): Flow<String>
    suspend fun stopScan()
    suspend fun createNetwork(eventName: String)
    suspend fun joinNetwork(sessionId: String): Peer
    fun leave()
    fun sendMessage(to: String, message: Message)
    fun addMessageListener(listener: (Message) -> Unit)

    /**
     * Unregister a previously added listener.
     * Call on leaveEvent() to stop processing messages after teardown.
     */
    fun removeMessageListener(listener: (Message) -> Unit)

    /**
     * Creates a network with an explicit topology choice.
     * The original [createNetwork] defaults to SnakeTopology.
     */
    suspend fun createNetwork(eventName: String, topology: TopologyChoice)
}

@Suppress("DEPRECATION")
class DefaultBackendFacade(
    private val context: Context,
    override val myId: String = "android-client",

    @Deprecated("Moved to topology")
    private val clientType: ClientType = ClientType.LEAF,

    private val userRole: UserRole = UserRole.ATTENDEE,

    private val useRealNearby: Boolean = true,
    private val config: Network.Config = Network.Config(defaultTtl = 5),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : BackendFacade {

    private val network: Network =
        if (useRealNearby) ConnectNetwork(context = context, scope = scope)
        else LocalNetwork()

    private val client: Client = Client(
        displayName = myId,
        network     = network,
        scope       = scope
    )


    private var currentEventName: String? = null

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Idle)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()



    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, replay = 1)
    override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    @Deprecated("No longer meaningful")
    override val currentSessionId: StateFlow<String?> = MutableStateFlow(null)



    override fun start() {
        // Translate new transport state → old NetworkState variants
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

        // Translate new NetworkEvent variants → old variants
        scope.launch {
            network.events.collect { ev ->
                when (ev) {
                    is NetworkEvent.EndpointConnected -> {
                        val decoded = AdvertisedName.decode(ev.encodedName)
                        val peer = DeprecatedPeer(ev.endpointId)
                        _events.tryEmit(NetworkEvent.PeerConnected(peer))
                        currentEventName?.let { _events.tryEmit(NetworkEvent.Joined(it)) }
                    }
                    is NetworkEvent.EndpointDisconnected ->
                        _events.tryEmit(NetworkEvent.PeerDisconnected(ev.endpointId))
                    is NetworkEvent.MessageReceived ->
                        _events.tryEmit(ev) // pass through unchanged
                    else ->
                        _events.tryEmit(ev) // pass through (EndpointDiscovered etc.)
                }
            }
        }
    }

    // Scanning

    override fun scanNetworks(): Flow<String> {
        scope.launch { network.startDiscovery() }
        // Return a flow of event names decoded from EndpointDiscovered events
        return network.events
            .filterIsInstance<NetworkEvent.EndpointDiscovered>()
            .map { ev ->
                AdvertisedName.decode(ev.encodedName)?.eventName ?: ev.encodedName
            }
    }

    override suspend fun stopScan() = network.stopDiscovery()

    // Hosting / Joining

    @Deprecated("Defaults to SnakeTopology. Use createNetwork(eventName, topology) to specify.",
        ReplaceWith("createNetwork(eventName, TopologyChoice.SNAKE)"))
    override suspend fun createNetwork(eventName: String) {
        createNetwork(eventName, TopologyChoice.SNAKE)
    }

    @Suppress("DEPRECATION")
    override suspend fun joinNetwork(sessionId: String): Peer {
        currentEventName = sessionId
        client.joinNetwork(sessionId)
        // Wait for the first EndpointConnected and wrap it in the deprecated Peer
        val connectedEvent = network.events
            .filterIsInstance<NetworkEvent.EndpointConnected>()
            .first()
        return Peer.generatePeer(connectedEvent.endpointId)
    }

    override fun leave() {
        scope.launch {
            client.leaveNetwork()
            currentEventName = null
        }
    }

    // Messaging

    override fun sendMessage(to: String, message: Message) {
        // Topology handles routing — just forward through the client
        client.sendMessage(message)
    }

    override fun addMessageListener(listener: (Message) -> Unit) {
        network.addListener(listener)
    }

    override fun removeMessageListener(listener: (Message) -> Unit) {
        network.removeListener(listener)
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
}