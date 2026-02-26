package edu.uwm.cs595.goup11.backend.network

import SnakeTopology
import edu.uwm.cs595.goup11.backend.network.topology.HubAndSpokeTopology
import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.network.topology.TopologyContext
import edu.uwm.cs595.goup11.backend.network.topology.TopologyPeer
import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.InvalidParameterException
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.typeOf


/**
 * Represents a client on the network.
 *
 * IMPORTANT (per refactor):
 * - This class should NOT handle message logic here (no processing, no listeners, no send/receive).
 * - This class should react to network state/events for lifecycle setup (join/leave/scan/etc.).
 *
 * When passing the [network], DO NOT call init() externally; the client will handle that.
 */
class Client(
    val id: String,
    /**
     * TODO: This has been moved to the topology. The client should not know what type of node it is
     */
    val type: ClientType = ClientType.LEAF,
    var network: Network? = null,
    /**
     * Defines the topology of the client. By default this is snake, but it should update depending
     * on the network
     */
    private var topology: TopologyStrategy = SnakeTopology(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val logger = KotlinLogging.logger {}
    private var networkId: String? = null
    private val replyWaiters =
        ConcurrentHashMap<String, CompletableDeferred<Message>>() // requestId -> deferred reply

    /**
     * The router peer this client joined through (if applicable).
     * This is returned by Network.join(sessionId).
     * TODO: DEPRECATED. MOVED TO TOPOLOGY
     */
    private var attachedRouter: Peer? = null
    /**
    * TODO: DEPRECATED. MOVED TO TOPOLOGY
    */
    private var attachedPeers = mutableListOf<Peer>();

    /**
     * Used if [type] == [ClientType.ROUTER]
     *
     * TODO: DEPRECATED. MOVED TO TOPOLOGY
     */
    private var attachedRouters = mutableListOf<Peer>()


    /**
     * Topology setup
     */
    private val topologyContext by lazy {
        TopologyContext(
            localId = id,
            network = requireNetwork(),
            onAdvertisingChanged = { advertising ->
                if (advertising) requireNetwork().startAdvertising()
                else requireNetwork().stopAdvertising()
            },
            onScanChanged = { scanning ->
                scope.launch {
                    if (scanning) requireNetwork().startScan()
                    else requireNetwork().stopScan()
                }
            },
            onRoleChanged = { role ->
                logger.info { "Role changed to $role" }
            },
            coroutineScope = scope
        )
    }

    /**
     * Attach a Network implementation (LocalNetwork or ConnectNetwork).
     * Client should call network.init(this, config) internally.
     */
    fun attachNetwork(network: Network, config: Network.Config) {

        this.network = network
        this.network!!.init(this, config)

        // Attach listeners
        this.network!!.addListener { message ->  onMessageReceived(message) }

        topology.start(topologyContext)
    }

    /**
     * Expose network state for UI / orchestration.
     * (Idle, Scanning, Joining, Joined, Hosting, Error)
     */
    fun networkState(): StateFlow<NetworkState> {
        TODO("Not implemented")
    }

    /**
     * Expose network events for one-time reactions.
     * (Joined, PeerConnected, PeerDisconnected, etc.)
     */
    fun networkEvents(): SharedFlow<NetworkEvent> {
        TODO("Not implemented")
    }

    /**
     * Start scanning for networks and return the discovery flow
     */
    suspend fun scanNetworks(): Flow<String> {
        val n = requireNetwork()
        n.startScan()
        return n.discoveredNetworks
    }

    /**
     * Stop scanning for networks.
     */
    suspend fun stopScan() {
        val n = requireNetwork()
        n.stopScan()
    }

    /**
     * Join a network with the [sessionId] and return the router Peer.
     */
    suspend fun joinNetwork(sessionId: String): Peer {
        val n = requireNetwork()

        // Join Network
        val p = n.join(sessionId)

        listenToEvents()
        

        // Listen to network events
        manuallyListenToEvents()

        return p
    }

    private fun listenToEvents() {
        scope.launch {
            requireNetwork().events.collect { ev ->
                when (ev) {
                    is NetworkEvent.PeerConnected -> topology.onPeerConnected(topologyContext, ev.peer)
                    is NetworkEvent.PeerDisconnected -> topology.onPeerDisconnected(topologyContext, ev.endpointId)
                    else -> Unit
                }
            }
        }
    }

    /**
     * Leave the current network.
     */
    fun leaveNetwork() {
        TODO("Not implemented")
    }

    /**
     * Create/host a new network.
     */
    suspend fun createNetwork(networkName: String) {
        val n = requireNetwork()
        n.create(networkName);

        n.startAdvertising()
    }

    /**
     * Delete the currently hosted network.
     */
    suspend fun deleteNetwork() {
        TODO("Not implemented")
    }

    /**
     * Shutdown and cleanup.
     */
    fun shutdown() {
        topology.stop()
        TODO("Not implemented")
    }

    /** Manual additions (FOR UNIT TESTING ONLY) */

    /**
     * Sends a [message] to the [to] peer.
     *
     * If this user is not within the [attachedPeers] list, this will throw an error.
     * This method also bypasses the [attachedRouter]
     */
    fun sendMessage(to: String, message: Message) {
        val net = requireNetwork()
        val inPeerList = attachedPeers.any { peer -> peer.endpointId == message.to }

        if(!inPeerList) {
            throw Error("${to} is not within the peer list")
        }

        net.sendMessage(to, message)
    }

    fun manuallyAddPeer(peer: Peer, isRouter: Boolean = false) {
        if(isRouter) {
            attachedRouter = peer

            if(type == ClientType.ROUTER) {
                attachedRouters.add(peer)
            }
        }

        attachedPeers.add(peer)
    }

    fun manuallyRemovePeer(peer: Peer) {
        if(attachedRouter == peer) {
            attachedRouter = null
        }

        attachedRouters.removeIf {p -> p == peer}
        attachedPeers.removeIf {p -> p == peer}
    }

    fun manuallyListenToEvents() {
        scope.launch {
            requireNetwork().events.collect { ev ->
                when(ev) {
                    is NetworkEvent.Joined -> {
                        attachedPeers.add(ev.router)
                    }
                    is NetworkEvent.PeerConnected -> {
                        //TODO: A message should be sent to get the type of peer that this is
                        attachedPeers.add(ev.peer)
                    }
                    is NetworkEvent.PeerDisconnected -> {
                        attachedPeers.removeIf {p -> p.endpointId == ev.endpointId}
                    }
                    else -> Unit
                }
            }
        }
    }
    /**
     * Sends a [message] depending on the [topology] that has been selected for this
     * network
     */
    fun sendMessage(message: Message) {
        val net = requireNetwork()
        // Check if peer is in our list of peers
        val nextHops = topology.resolveNextHop(topologyContext, message)

        if (nextHops.isEmpty()) {
            logger.warn { "No route found for message to ${message.to}" }
            return
        }

        nextHops.forEach { hop -> net.sendMessage(hop, message) }
    }

    /**
     * Sends a [message] to the [to] peer and waits [timeoutMillis] for a response
     *
     * @see [sendMessage]
     */
    suspend fun sendMessageAndWait(
        to: String,
        message: Message,
        timeoutMillis: Long = 10_000
    ): Message? {
        // 1) create waiter and register BEFORE sending
        val deferred = CompletableDeferred<Message>()
        val previous = replyWaiters.put(message.id, deferred)
        require(previous == null) { "Duplicate message id registered: ${message.id}" }

        try {
            // 2) send the request
            sendMessage(to, message)

            // 3) wait for reply
            return withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            return null
        } finally {
            // 4) cleanup in all cases
            replyWaiters.remove(message.id, deferred)
        }
    }

    /**
     * Sends a message using the [sendMessage] (with only message) and waits [timeoutMillis] long
     * for a response.
     */
    suspend fun sendMessageAndWait(message: Message, timeoutMillis: Long = 10_000): Message? {
        // 1) create waiter and register BEFORE sending
        val deferred = CompletableDeferred<Message>()
        val previous = replyWaiters.put(message.id, deferred)
        require(previous == null) { "Duplicate message id registered: ${message.id}" }

        try {
            // 2) send the request
            sendMessage(message)

            // 3) wait for reply
            return withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            return null
        } finally {
            // 4) cleanup in all cases
            replyWaiters.remove(message.id, deferred)
        }
    }



    /**
     * Add a listener that runs whenever a message is received by this client.
     */
    fun addMessageListener(listener: (Message) -> Unit) {
        TODO("Not implemented")
    }

    /**
     * Called by the Network implementation (LocalNetwork / ConnectNetwork) when a message arrives.
     */
    fun onMessageReceived(message: Message) {
        // Check if this message is a reply to a message in the queue
        val replyTo = message.replyTo
        if(!replyTo.isNullOrBlank()) {
            replyWaiters.remove(replyTo)?.let { deferred ->
                if(!deferred.isCompleted) deferred.complete(message)
            }
        }
        handleMessage(message)
    }

    /**
     * Main message handling method. This method should handle all message routing and parsing.
     *
     * For UI events this should call listeners
     *
     * This method SHOULD NOT handle any responses for:
     * - [MessageType.TEXT_MESSAGE]

     */
    private fun handleMessage(message: Message) {

        val consumed = topology.onMessage(topologyContext, message)
        if (consumed) return;

        //TODO: Below is deprecated. All routing messages have been moved to the topology
        when(type) {
            ClientType.ROUTER -> {
                when(message.type){
                    MessageType.PING -> {
                        // Reply with pong
                        sendMessage(Message(
                            to=message.from,
                            from=id,
                            replyTo = message.id,
                            type = MessageType.PONG,
                            ttl = 5
                        ))
                    }

                    MessageType.PONG -> {
                        // Log this
                    }

                    else -> {

                    }
                }
            }
            ClientType.LEAF -> {
                when(message.type){
                    MessageType.PING -> {
                        // Reply with pong
                        sendMessage(Message(
                            to=message.from,
                            from=id,
                            replyTo = message.id,
                            type = MessageType.PONG,
                            ttl = 5
                        ))
                    }

                    MessageType.PONG -> {
                        // Log this
                    }

                    else -> {

                    }
                }
            }
        }
    }

    private fun modifyTopology(newTopology: TopologyStrategy) {
        // Update
        topology.stop()

        topology = newTopology

        topology.start(topologyContext)
    }
    private fun configureFromNetworkId(fromId: String) {
        val regex = Regex("""EVT:([^|]+)\|TOP:([^|]+)\|TYP:([^|]+)\|N:(.+)""")
        val match = regex.find(fromId)
        if (match != null) {
            val (eventName, topology, type, name) = match.destructured

            // Checks
            val validTopos: Array<String> = arrayOf("hub", "snk", "msh")
            if(!validTopos.contains(topology)) {
                throw InvalidParameterException("Invalid topology type. Expected one of" +
                        " $validTopos. Got: $topology")
            }

            val validType: Array<String> = arrayOf("l", "r", "a", "p")
            if(!validType.contains(type)) {
                throw InvalidParameterException("Invalid peer type. Expected one of" +
                        " $validType. Got: $type")
            }


            // Configure this client
            when(topology) {
                "hub" -> {
                    if(topology::class != HubAndSpokeTopology::class) {
                        modifyTopology(HubAndSpokeTopology(localRole =
                            TopologyStrategy.Role.LEAF))
                    }
                }
                "snk" -> {
                    if(topology::class != SnakeTopology::class) {
                        modifyTopology(SnakeTopology())
                    }
                }
                "msh" -> {
                    if(topology::class != MeshTopology::class) {
                        modifyTopology(MeshTopology(maxPeerCount = 5, TopologyStrategy.Role.PEER))
                    }
                }
            }



        } else {
            throw InvalidParameterException("Expected input to match regex: EVT:([^|]+)\\|TOP:([^|]+)\\|TYP:([^|]+)\\|N:(.+)")
        }
    }

    private fun generateNetworkId(eventName: String): String {
        return "EVT:$eventName|TOP:${
            when(topology::class) {
                SnakeTopology::class -> {
                    "snk"
                }

                MeshTopology::class -> {
                    "msh"
                }

                HubAndSpokeTopology::class -> {
                    "hub"
                }
                else -> {
                    throw IllegalStateException("Class not found for topology")
                }
            }
        }|TYP:${
            when(topology.localRole) {
                TopologyStrategy.Role.LEAF -> {
                    "l"
                }
                
                TopologyStrategy.Role.PEER -> {
                    "p"
                }
                
                TopologyStrategy.Role.ROUTER -> {
                    "r"
                }
            }
        }|N:${id}"
    }

    private fun requireNetwork(): Network {
        return network ?: error("Network is not attached")
    }

    private fun requireJoinedRouter(): Peer {
        return attachedRouter ?: error("Client is not joined to a network")
    }

}
