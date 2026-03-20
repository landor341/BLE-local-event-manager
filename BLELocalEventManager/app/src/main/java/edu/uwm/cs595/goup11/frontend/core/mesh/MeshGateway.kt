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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

interface BackendFacade {
    val myId: String
    val myRole: UserRole

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
}

class DefaultBackendFacade(
    private val context: Context,
    override val myId: String = "android-client",
    override val myRole: UserRole = UserRole.ATTENDEE,
    private val clientType: ClientType = ClientType.LEAF,
    private val useRealNearby: Boolean = false,
    private val config: Network.Config = Network.Config(defaultTtl = 5),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : BackendFacade {

    private val network: Network =
        if (useRealNearby) ConnectNetwork(context) else LocalNetwork()

    private val client: Client = Client(
        id = myId,
        type = clientType,
        role = myRole
    )

    private var started: Boolean = false

    override val state: StateFlow<NetworkState> get() = network.state
    override val events: SharedFlow<NetworkEvent> get() = network.events
    override val currentSessionId: StateFlow<String?> get() = network.currentSessionId

    override fun start() {
        if (started) return
        started = true
        client.attachNetwork(network, config)
    }

    override fun scanNetworks(): Flow<String> {
        scope.launch { network.startScan() }
        return network.discoveredNetworks
    }

    override suspend fun stopScan() {
        network.stopScan()
    }

    override suspend fun createNetwork(eventName: String) {
        client.createNetwork(eventName)
    }

    override suspend fun joinNetwork(sessionId: String): Peer {
        return client.joinNetwork(sessionId)
    }

    override fun leave() {
        network.leave()
    }

    override fun sendMessage(to: String, message: Message) {
        network.sendMessage(to, message)
    }

    override fun addMessageListener(listener: (Message) -> Unit) {
        network.addListener(listener)
    }
}