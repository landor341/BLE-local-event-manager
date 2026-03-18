package edu.uwm.cs595.goup11.frontend.core.mesh

import android.content.Context
import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.ClientType
import edu.uwm.cs595.goup11.backend.network.ConnectNetwork
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Network
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.Peer
import edu.uwm.cs595.goup11.backend.network.UserRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ==========================================================
 * BackendFacade — BACKEND ISOLATION LAYER (Frontend-only)
 * ==========================================================
 *
 * PURPOSE
 * -------
 * This facade is the single choke point between the FRONTEND and the BACKEND networking module.
 * It prevents UI/ViewModels from depending directly on backend.network classes (Client/Network/Message/etc.).
 *
 * In a team environment, this protects the UI from breaking changes when backend APIs shift.
 *
 * PROJECT RULES
 * -------------
 * ✅ Frontend features/ViewModels MUST talk to the backend ONLY through:
 *      MeshGateway (UI contract) -> RealMeshGateway (adapter) -> BackendFacade (this file)
 *
 * ❌ Frontend features/ViewModels MUST NOT:
 *      - import edu.uwm.cs595.goup11.backend.network.*
 *      - instantiate Client, Network, LocalNetwork, ConnectNetwork
 *      - register message listeners directly on Network
 *
 * IMPORTANT BACKEND FACTS (based on current backend code you shared)
 * ---------------------------------------------------------------
 * 1) Client.networkState() and Client.networkEvents() are TODO.
 *    -> Therefore this facade exposes Network.state and Network.events directly.
 *
 * 2) Client.scanNetworks() is unsafe with LocalNetwork:
 *    - Client.scanNetworks() calls Network.startScan() and awaits it.
 *    - LocalNetwork.startScan() loops while scanning (until stopScan is called),
 *      meaning the await NEVER returns.
 *    -> Therefore this facade starts scan in a background coroutine and immediately
 *       returns Network.discoveredNetworks as a Flow<String>.
 *
 * 3) Messaging:
 *    - Client.sendMessage(message) has routing fallthrough behavior right now.
 *    - Client.sendMessage(to, message) contains a peer-list check bug (it checks message.to).
 *    -> For Sprint 3 integration stability, this facade calls Network.sendMessage(...) directly.
 *
 * NETWORK IMPLEMENTATION SELECTION
 * -------------------------------
 * - For Sprint 3 catch-up (stable demo + tests): useRealNearby=false (LocalNetwork)
 * - For real Bluetooth/Nearby behavior later:    useRealNearby=true  (ConnectNetwork)
 *
 * NOTE:
 * ConnectNetwork.create/join may still be incomplete depending on your backend status.
 *
 * FILE MAINTAINER
 * --------------
 * Primary maintainer: Frontend integration (Labib)
 * Any changes that affect public behavior should be coordinated/reviewed.
 */
interface BackendFacade {

    /** Stable client identifier used as Message.from */
    val myId: String

    /** Reactive backend network state (Idle/Scanning/Joining/Joined/Hosting/Error) */
    val state: StateFlow<NetworkState>

    /** One-time backend events (PeerConnected/PeerDisconnected/MessageReceived/etc.) */
    val events: SharedFlow<NetworkEvent>

    /** Current sessionId if joined/hosting, otherwise null */
    val currentSessionId: StateFlow<String?>

    /**
     * Initializes the backend side:
     * - creates/attaches the Network to Client
     * - sets up the Client->Network message listener path
     *
     * Call once per app lifecycle (e.g., in a ViewModel init or AppContainer.init).
     */
    fun start()

    /**
     * Starts scanning for networks and returns a flow of discovered session IDs.
     *
     * IMPORTANT:
     * - This function MUST return quickly and MUST NOT suspend forever.
     * - With LocalNetwork, startScan() runs in a loop, so scan is launched on a background coroutine.
     */
    fun scanNetworks(): Flow<String>

    /** Stops scanning (if currently scanning). */
    suspend fun stopScan()

    /**
     * Creates/hosts a network and begins advertising.
     * In backend Client.kt, createNetwork() calls Network.create(eventName) + startAdvertising().
     */
    suspend fun createNetwork(eventName: String)

    /**
     * Joins a network and returns the router Peer selected by the backend.
     * This Peer.endpointId is used for sending chat (Sprint 3).
     */
    suspend fun joinNetwork(sessionId: String): Peer

    /**
     * Leaves the current network.
     *
     * NOTE:
     * Client.leaveNetwork() is TODO in your backend, so we call Network.leave() directly.
     */
    fun leave()

    /**
     * Sends a message to a specific peer endpointId.
     *
     * Sprint 3 stability:
     * We call Network.sendMessage directly to avoid current Client routing quirks.
     */
    fun sendMessage(to: String, message: Message)

    /**
     * Adds a listener for raw messages received by this device.
     *
     * In your backend:
     * - Client.attachNetwork already calls Network.addListener { client.onMessageReceived(it) }
     * - Network.addListener supports multiple listeners (LocalNetwork stores a list)
     *
     * So it is safe to register the RealMeshGateway listener here.
     */
    fun addMessageListener(listener: (Message) -> Unit)
}

/**
 * DefaultBackendFacade
 *
 * Concrete implementation used by the frontend.
 * Owns ONE Client and ONE Network instance for the app process.
 */
class DefaultBackendFacade(
    private val context: Context,
    override val myId: String = "android-client",


    @Deprecated("Moved to topology. Client no longer manually controls this")
    private val clientType: ClientType = ClientType.LEAF,

    private val userRole: UserRole = UserRole.ATTENDEE, // Added to support role-based features

    private val useRealNearby: Boolean = false, // Sprint 3: keep false (LocalNetwork)
    private val config: Network.Config = Network.Config(defaultTtl = 5),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : BackendFacade {

    /**
     * Network selection:
     * - LocalNetwork: in-memory emulator (good for Sprint 3 wiring + demos in single process)
     * - ConnectNetwork: real Nearby/Bluetooth layer (enable when backend is ready)
     */
    private val network: Network =
        if (useRealNearby) ConnectNetwork(context) else LocalNetwork()

    /** Backend Client node. Population of role ensures it's attached to outgoing messages. */
    private val client: Client = Client(displayName = myId)

    override val state: StateFlow<NetworkState> get() = network.state
    override val events: SharedFlow<NetworkEvent> get() = network.events

    @Deprecated("No longer used")
    override val currentSessionId: StateFlow<String?> get() = MutableStateFlow<String>("")


    /**
     * Start backend networking:
     * - attaches client to network
     * - network.init(...) is invoked inside client.attachNetwork(...)
     * - client registers its own message listener on the network
     */
    override fun start() {
        client.attachNetwork(network, config)
    }

    /**
     * Starts scanning and returns discoveredNetworks flow.
     *
     * WHY this is not suspend:
     * - LocalNetwork.startScan() loops while scanning.
     * - Calling client.scanNetworks() would suspend forever because it awaits startScan().
     *
     * So we start scan asynchronously and return the discovery flow immediately.
     */
    override fun scanNetworks(): Flow<String> {
        //TODO: This is no longer used this way. Instead all networks are thrown as events
        scope.launch { network.startDiscovery() }
        return network.discoveredNetworks
    }

    override suspend fun stopScan() {
        network.stopScan()
    }

    override suspend fun createNetwork(eventName: String) {
        // Backend handles create + advertising internally.
        client.createNetwork(eventName)
    }

    override suspend fun joinNetwork(sessionId: String): Peer {
        return client.joinNetwork(sessionId)
    }

    override fun leave() {
        // Client.leaveNetwork() is TODO, so call Network directly.
        network.leave()
    }

    override fun sendMessage(to: String, message: Message) {
        // Sprint 3: Avoid Client routing quirks; send raw on the network.
        network.sendMessage(to, message)
    }

    override fun addMessageListener(listener: (Message) -> Unit) {
        // LocalNetwork supports multiple listeners.
        // This listener is used by RealMeshGateway to emit ChatMessage flows.
        network.addListener(listener)
    }
}