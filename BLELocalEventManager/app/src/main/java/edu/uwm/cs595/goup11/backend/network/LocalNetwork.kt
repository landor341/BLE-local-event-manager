package edu.uwm.cs595.goup11.backend.network
import androidx.annotation.VisibleForTesting
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.util.collections.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log

/**
 * Local in-memory network emulator. This class stores all networks locally and tries to
 * implement it as close as possible
 *
 * This class DOES NOT handle any message processing
 */
class LocalNetwork() : Network {

    /** Kotlin Logger*/
    override val logger: KLogger = KotlinLogging.logger {  }

    override val currentSessionId: StateFlow<String?>
        get() = TODO("Not yet implemented")

    // ---- Network interface reactive state/events ----
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Idle)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, replay = 1)
    override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    // discovered networks output
    private val _discoveredNetworks = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val discoveredNetworks: Flow<String> = _discoveredNetworks.asSharedFlow()

    // ---- Instance state ----
    private var isScanning = false
    private var isAdvertising = false

    private var client: Client? = null
    private var config: Network.Config? = null

    /**
     *  network id this client is currently joined to (or hosting). TODO: Move to [currentSessionId]
     *  @see [currentSessionId]
     *  */
    private var currentNetworkId: String? = null

    /** Message listeners */
    private val listeners = mutableListOf<(Message) -> Unit>()

    /** replyTo waiters: requestMessageId -> deferred(replyMessage) */
    private val replyWaiters = ConcurrentHashMap<String, CompletableDeferred<Message>>()

    override fun init(client: Client, config: Network.Config) {

        this.client = client
        this.config = config
        _state.value = NetworkState.Idle
        logger.debug { "${client.id} initialized the LocalNetwork" }
    }

    override fun shutdown() {
        // If joined, leave
        runCatching { leave() }
        stopAdvertising()

        isScanning = false
        _state.value = NetworkState.Idle

        // fail any waiters
        replyWaiters.values.forEach { d ->
            if (!d.isCompleted) d.completeExceptionally(RuntimeException("Network shutdown"))
        }
        replyWaiters.clear()

        listeners.clear()
        client = null
        config = null
        currentNetworkId = null
    }

    // ------------------ Scan ------------------

    override suspend fun startScan() {
        logger.debug { "${client?.id} started scan" }
        isScanning = true
        _state.value = NetworkState.Scanning

        // Emit current networks immediately
        InMemoryNetworks.listNetworkIds().forEach { id ->
            if(!InMemoryNetworks.getNetworkById(id)!!.advertisedBy.isEmpty()) {
                logger.debug { "${client?.id} found network. Id=${id}" }
                _discoveredNetworks.tryEmit(id)
            }
        }

        // Optional: keep polling while scanning so new networks appear
        while (isScanning) {
            delay(750)
            InMemoryNetworks.listNetworkIds().forEach { id ->
                if(!InMemoryNetworks.getNetworkById(id)!!.advertisedBy.isEmpty()) {
                    logger.debug { "${client?.id} found network. Id=${id}" }
                    _discoveredNetworks.tryEmit(id)
                }
            }
        }
    }

    override suspend fun stopScan() {
        logger.debug { "${client?.id} stopped scanning." }
        isScanning = false
        if (_state.value is NetworkState.Scanning) {
            _state.value = NetworkState.Idle
        }
    }

    // ------------------ Join / Leave ------------------

    override suspend fun join(sessionId: String): Peer {
        val c = client ?: throw Error("Client not instantiated")
        val net = InMemoryNetworks.getNetworkById(sessionId)
            ?: throw Error("Network '$sessionId' not found")

        _state.value = NetworkState.Joining(sessionId)

        // Pick random router to connect to
        val r = net.connectedClients.filter { c -> c.value.client.type == ClientType.ROUTER }
        val router = net.connectedClients[r.keys.random()]
        val routerPeer = Peer(
            router!!.client.id,
            router.client.id
        )
        // Register this client in the network, connecting to master/router by default
        net.addClient(
            client = c,
            network = this,
            routerPeer
        )

        currentNetworkId = sessionId

        _state.value = NetworkState.Joined(sessionId, routerPeer)
        _events.tryEmit(NetworkEvent.Joined(sessionId, routerPeer))
        _events.tryEmit(NetworkEvent.PeerConnected(routerPeer))
        logger.debug { "${c.id} joined network ${net.id} through ${routerPeer.endpointId}" }
        return routerPeer
    }

    override fun leave() {
        val c = client ?: throw Error("Client not instantiated")
        val netId = currentNetworkId ?: throw Error("Client is not in a network")

        val net = InMemoryNetworks.getNetworkById(netId)
            ?: throw Error("Network '$netId' not found")

        net.removeClient(c.id)

        currentNetworkId = null
        _state.value = NetworkState.Idle

        _events.tryEmit(NetworkEvent.PeerDisconnected(c.id))
    }

    // ------------------ Host / Delete ------------------

    override suspend fun create(eventName: String) {
        val c = client ?: throw Error("Client not instantiated")

        // For emulator: use eventName as id (you can swap to a generated id if you prefer)
        val id = eventName

        val created = InMemoryNetworks.createNetwork(
            id = id,
            masterClient = c,
            masterNetwork = this
        )

        currentNetworkId = created.id
        isAdvertising = true
        _state.value = NetworkState.Hosting(created.id)
    }

    override suspend fun deleteNetwork() {
        val c = client ?: throw Error("Client not instantiated")
        val netId = currentNetworkId ?: throw Error("Client is not in a network")

        val net = InMemoryNetworks.getNetworkById(netId)
            ?: throw Error("Network '$netId' not found")

        if (net.masterClient.id != c.id) {
            throw Error("Only the network owner can delete the network")
        }

        // disconnect everyone
        net.connectedClients.keys.toList().forEach { clientId ->
            net.removeClient(clientId)
        }

        InMemoryNetworks.deleteNetwork(netId)

        currentNetworkId = null
        isAdvertising = false
        _state.value = NetworkState.Idle
    }

    // ------------------ Messaging ------------------

    override fun sendMessage(to: String, message: Message) {
        val c = client ?: throw Error("Client not instantiated")
        val netId = currentNetworkId ?: throw Error("Client is not in a network")
        val net = InMemoryNetworks.getNetworkById(netId) ?: return

        net.sendMessage(fromClientId = c.id, toClientId = to, message = message)

        logger.debug { "${c.id} sent message to $to. Message type = ${message.type}. Id=${message.id}" }
    }

    override suspend fun sendMessageAndWait(
        to: String,
        message: Message,
        timeoutMillis: Long
    ): Message? {
        val netId = currentNetworkId ?: throw Error("Client is not in a network")
        InMemoryNetworks.getNetworkById(netId) ?: throw Error("Network not found")

        // Register waiter BEFORE sending
        val deferred = CompletableDeferred<Message>()
        replyWaiters[message.id] = deferred

        // Send request
        sendMessage(to, message)

        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            replyWaiters.remove(message.id)
            null
        } finally {
            // If completed, it was removed in onReceive; if not, remove now.
            replyWaiters.remove(message.id)
        }
    }

    /**
     * Called by the in-memory network to deliver messages to this client instance.
     */
    fun onReceive(message: Message) {
        // Complete request/response waiter if this is a reply
        val replyTo = message.replyTo
        if (!replyTo.isNullOrBlank()) {
            replyWaiters.remove(replyTo)?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(message)
            }
        }

        // Legacy listeners
        notifyListeners(message)

        // Event stream (useful for client)
        _events.tryEmit(NetworkEvent.MessageReceived(message))

        logger.debug { "${client?.id} received message on local network by ${message.from}. Type=${message.type}. Id=${message.id}" }
    }

    override fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    override fun notifyListeners(message: Message) {
        listeners.forEach { it.invoke(message) }
    }

    // ------------------ Advertising (no-op-ish for Local emulator) ------------------

    override fun startAdvertising() {
        val net = requireNet()
        val c = requireClient()
        isAdvertising = true
        net.startAdvertising(Peer(c.id, c.id))

    }

    override fun stopAdvertising() {
        val net = requireNet()
        val c = requireClient()
        isAdvertising = false
        net.stopAdvertising(Peer(c.id, c.id))
    }

    override fun onPeerConnect(peer: Peer) {
        logger.debug { "${client?.id} connected to peer ${peer.endpointId}" }
        _events.tryEmit(NetworkEvent.PeerConnected(peer))
    }

    override fun onPeerDisconnect(peer: Peer) {
        logger.debug { "${client?.id} disconnected to peer ${peer.endpointId}" }
        _events.tryEmit(NetworkEvent.PeerDisconnected(peer.endpointId))
    }

    private fun requireNet(): InMemoryNetworks.InMemoryNetwork {
        val netId = currentNetworkId ?: throw Error("Client is not in a network")
        val net = InMemoryNetworks.getNetworkById(netId) ?: throw Error("Network is required for this operation")

        return net
    }

    // ------------------ Helper Methods ------------------

    /**
     * This method will return the current client and throw an error when the client is not
     * present or is null
     */
    private fun requireClient(): Client {
        return client ?: throw Error("Client is required for this operation")
    }

    // ------------------ Debug Methods ------------------

    /**
     * Returns a string representing a graph of the network. It does not build a top-level
     * overview of the network, instead prints every client and who they are connected to
     *
     * Note: This method is only for testing
     */
    @VisibleForTesting
    fun prettyPrintNetworkGraph(): String {
        val netId = currentNetworkId ?: throw Error("Client is not in a network")
        val net = InMemoryNetworks.getNetworkById(netId) ?: return "Network not found"

        val sb = StringBuilder()

        sb.appendLine("===================================")
        sb.appendLine("Network Graph: $netId")
        sb.appendLine("Master: ${net.masterClient.id}")
        sb.appendLine("===================================")

        for ((clientId, connectedClient) in net.connectedClients) {
            sb.appendLine(clientId)

            if (connectedClient.connectedTo.isEmpty()) {
                sb.appendLine("  (no connections)")
            } else {
                for (neighbor in connectedClient.connectedTo) {
                    sb.appendLine("  └── $neighbor")
                }
            }

            sb.appendLine()
        }

        return sb.toString()
    }

    companion object {
        val logger = KotlinLogging.logger {  }
        fun purge() {
            logger.debug { "Purging InMemoryNetwork" }
            InMemoryNetworks.purgeNetworks()
        }
        /**
         * Generates a example network with the [networkId] as the title, and [numRouters] of routers
         * and [numClients] of clients. If you want a specific client to be the master client
         * (i.e. user who created the network) pass it as the [masterClient]. If passing a [masterClient]
         * you must also pass a [network]
         *
         * This will NOT call the [Client.joinNetwork] methods or any methods
         * that get called when a client joins, instead it will manually add clients to random routers
         */
        fun generateMockNetwork(
            networkId: String,
            numRouters: Int,
            numClients: Int,
            masterClient: Client = Client("MASTER", ClientType.ROUTER),
            network: LocalNetwork = LocalNetwork()
        ) {
            logger.atDebug {
                message = "Generating mock network"
                payload = mapOf(
                    "Class" to "LocalNetwork",
                    "Title" to "GenerateMockNetwork",
                    "ID" to networkId,
                    "numRouters" to numRouters,
                    "numClients" to numClients,
                    "masterClientId" to masterClient.id,
                    "masterClientType" to masterClient.type.toString()
                )
            }
            // Create network with master
            val net = InMemoryNetworks.createNetwork(networkId, masterClient, network)
            network.currentNetworkId = net.id

            // Keep references to all router ConnectedClients (including master)
            val routerIds = mutableListOf<String>()
            routerIds.add(masterClient.id)

            // Create routers
            for (i in 0 until numRouters) {
                val n = LocalNetwork()
                val routerClient = Client("ROUTER$i", ClientType.ROUTER)
                routerClient.attachNetwork(n, Network.Config(5))
                n.currentNetworkId = net.id

                // Add router connected to MASTER (guarantees connectivity)
                val masterPeer = Peer(masterClient.id, masterClient.id)
                net.addClient(routerClient, n, masterPeer)

                // Make sure this router knows it is connected to master
                routerClient.manuallyAddPeer(masterPeer, true)
                routerClient.manuallyListenToEvents()

                routerIds.add(routerClient.id)
            }

            // Ensure every router connects to every other router (both directions)
            for (a in routerIds.indices) {
                for (b in (a + 1) until routerIds.size) {
                    val aId = routerIds[a]
                    val bId = routerIds[b]

                    val aConn = net.connectedClients[aId] ?: continue
                    val bConn = net.connectedClients[bId] ?: continue

                    // Update adjacency sets
                    aConn.connectedTo.add(bId)
                    bConn.connectedTo.add(aId)

                    // Notify both network instances (so events fire + peer lists update)
                    aConn.network.onPeerConnect(Peer(bId, bId))
                    bConn.network.onPeerConnect(Peer(aId, aId))
                }
            }

            // Create leaves (EXACTLY numClients)
            for (i in 0 until numClients) {
                val n = LocalNetwork()
                val leafClient = Client("LEAF$i", ClientType.LEAF)
                leafClient.attachNetwork(n, Network.Config(5))
                n.currentNetworkId = net.id

                // Connect leaf to a random router
                val routerId = routerIds.random()
                val routerPeer = Peer(routerId, routerId)

                net.addClient(leafClient, n, routerPeer)

                // Leaf knows it is connected to that router
                leafClient.manuallyAddPeer(routerPeer)
                leafClient.manuallyListenToEvents()
            }
        }


    }

    // ------------------ In Memory Networks ------------------

    private object InMemoryNetworks {

        private val networks = mutableMapOf<String, InMemoryNetwork>()

        fun listNetworkIds(): List<String> = networks.keys.toList()

        fun getNetworkById(id: String): InMemoryNetwork? = networks[id]

        /**
         * Creates a network with the [id] and a [masterClient] as the user who created it
         *
         * @return the [InMemoryNetwork] instance with ONLY the [masterClient]
         */
        fun createNetwork(id: String, masterClient: Client, masterNetwork: LocalNetwork): InMemoryNetwork {
            if (networks.containsKey(id)) throw Error("Network '$id' already exists")
            val net = InMemoryNetwork(id, masterClient)
            networks[id] = net

            // Add master as connected client (self)
            net.connectedClients[masterClient.id] = InMemoryNetwork.ConnectedClient(masterClient, masterNetwork)
            return net
        }

        /**
         * Deletes the network with the [id]
         */
        fun deleteNetwork(id: String) {
            networks.remove(id)
        }

        /**
         * This method will destroy all instances of [InMemoryNetwork]. If other [Client]'s are
         * created, this will not purge them
         */
        fun purgeNetworks() {
            networks.clear()
        }

        class InMemoryNetwork(
            val id: String,
            val masterClient: Client
        ) {
            /**
             * List of users that are advertising this network
             */
            val advertisedBy = mutableListOf<Peer>()
            data class ConnectedClient(
                val client: Client,
                val network: LocalNetwork,
                val connectedTo: MutableSet<String> = mutableSetOf()
            )

            val connectedClients = mutableMapOf<String, ConnectedClient>()

            /**
             * Adds a [client] and attaches it to a [peer]
             */
            fun addClient(client: Client, network: LocalNetwork, peer: Peer) {
                if (connectedClients.containsKey(client.id)) return

                if (!connectedClients.containsKey(peer.endpointId)) {
                    throw Error("Peer does not exist on the network")
                }
                val peerConnected = connectedClients[peer.endpointId]

                val cc = ConnectedClient(client = client, network = network)
                connectedClients[client.id] = cc

                // Connect to peer
                peerConnected!!.connectedTo.add(cc.client.id)
                connectedClients[client.id]?.connectedTo?.add(peerConnected.client.id)

                // Call event on the connected client
                peerConnected.network.onPeerConnect(Peer(client.id, client.id))

            }

            /**
             * Removes a client via the [clientId] from the network
             *
             * This will emmit a [NetworkEvent.PeerDisconnected] on the client
             */
            fun removeClient(clientId: String) {
                val removed = connectedClients.remove(clientId) ?: return

                // Remove edges pointing to this client
                connectedClients.values.forEach { it.connectedTo.remove(clientId) }

                // Notify owner/client via events if desired
                removed.network._events.tryEmit(NetworkEvent.PeerDisconnected(clientId))
            }

            /**
             * Returns True if the [fromId] client is connected to the [toId] client
             */
            fun isConnected(fromId: String, toId: String): Boolean {
                val from = connectedClients[fromId] ?: return false
                return from.connectedTo.contains(toId) || fromId == toId
            }

            /**
             * Sends a [message] to the [toClientId] client.
             *
             * @exception Error when [isConnected] returns false
             */
            fun sendMessage(fromClientId: String, toClientId: String, message: Message) {
                // Only deliver if connected in this emulation model
                if (!isConnected(fromClientId, toClientId)) {
                    throw Error("$fromClientId is not connected to $toClientId")
                }

                val target = connectedClients[toClientId] ?: return
                target.network.onReceive(message)
            }

            /**
             * Starts advertising this network. This method will NOT throw an error if the user
             * is already advertising
             */
            fun startAdvertising(peer: Peer) {
                // Check if user is already advertising
                if(advertisedBy.any { p -> p == peer }) {
                    return;
                }

                advertisedBy.add(peer)
            }

            /**
             * Stops advertising this network. This method will NOT throw an error if the user
             * is not advertising
             */
            fun stopAdvertising(peer: Peer) {
                // Check if user is not advertising
                if(!advertisedBy.any { p -> p == peer }) {
                    return;
                }

                // Remove
                advertisedBy.removeIf { p -> p == peer }

            }
        }
    }
}
