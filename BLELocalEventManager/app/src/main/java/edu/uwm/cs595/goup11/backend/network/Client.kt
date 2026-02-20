package edu.uwm.cs595.goup11.backend.network

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
    val type: ClientType,
    var network: Network? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val logger = KotlinLogging.logger {}

    private val replyWaiters =
        ConcurrentHashMap<String, CompletableDeferred<Message>>() // requestId -> deferred reply

    /**
     * The router peer this client joined through (if applicable).
     * This is returned by Network.join(sessionId).
     */
    private var attachedRouter: Peer? = null

    private var attachedPeers = mutableListOf<Peer>();

    /**
     * Used if [type] == [ClientType.ROUTER]
     */
    private var attachedRouters = mutableListOf<Peer>()

    /**
     * Attach a Network implementation (LocalNetwork or ConnectNetwork).
     * Client should call network.init(this, config) internally.
     */
    fun attachNetwork(network: Network, config: Network.Config) {

        this.network = network
        this.network!!.init(this, config)

        // Attach listeners
        this.network!!.addListener { message ->  onMessageReceived(message) }
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

        attachedRouter = p
        attachedPeers.add(p)

        // Listen to network events
        manuallyListenToEvents()
        // TODO: This should attempt to find best router, if our topology is a router mesh

        // Send a HELLO message
        sendMessageAndWait(p.endpointId, Message(
            to=p.endpointId,
            from=id,
            type= MessageType.HELLO,
            ttl=1 // Should only send direct
        ))

        return p
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
     * Sends a [message] to the [attachedRouter] client (if the router mesh topology is used)
     * otherwise this will look for the [Message.to] peer within the [attachedPeers] list.
     *
     * If this is a [ClientType.ROUTER] (if that topology is used) and the to field is not within the peer list, this
     * will send the message to all peers instead
     *
     * If neither are found this will throw a error
     */
    fun sendMessage(message: Message) {
        val net = requireNetwork()
        // Check if peer is in our list of peers
        val inPeerList = attachedPeers.any { peer -> peer.endpointId == message.to }

        if (inPeerList) {
            // Send the message to the peer
            net.sendMessage(message.to, message)
        }

        if(type == ClientType.ROUTER) {
            // send message to all connected routers

        }

        // Otherwise send to router
        val router = requireJoinedRouter()
        net.sendMessage(router.endpointId, message)
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

    private fun requireNetwork(): Network {
        return network ?: error("Network is not attached")
    }

    private fun requireJoinedRouter(): Peer {
        return attachedRouter ?: error("Client is not joined to a network")
    }

}
