package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.TopologyContext
import edu.uwm.cs595.goup11.backend.network.topology.TopologyFactory
import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy
import edu.uwm.cs595.goup11.backend.security.Crypto
import edu.uwm.cs595.goup11.backend.security.Manager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * The application layer of the mesh network stack.
 *
 * Client is responsible for:
 *  - Network lifecycle (creating/joining/leaving a network)
 *  - Decoding raw AdvertisedName strings from the network boundary
 *  - Delegating structural decisions to the topology strategy
 *  - Handling application-layer messages (TEXT_MESSAGE, HELLO, etc.)
 *  - Managing the reply-waiter queue for request/response flows
 *  - Re-advertising when this node's role changes
 *
 * Client is NOT responsible for:
 *  - Peer lists or connection tracking (topology owns those)
 *  - Routing decisions (topology owns those)
 *  - Keepalive / PING-PONG (topology owns those)
 *  - Raw transport (network owns that)
 *
 * OSI analogy: Network ≈ layers 1-2, Topology ≈ layer 3, Client ≈ layers 4-7.
 */
class Client(
    /** Human-readable display name for this user e.g. "Alice" */
    val displayName: String,

    //val id: String,
    // val type: ClientType,
    var role: UserRole = UserRole.ATTENDEE,
    var presentationId: String? = null, // Differentiates which "booth" or "panel" this client belongs to
    var network: Network? = null,

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val logger = KotlinLogging.logger {}

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * The current topology strategy. Null when not on a network.
     * Set by createNetwork() (caller chooses topology) or joinNetwork()
     * (topology is inferred from the discovered advertised name).
     */
    private var topology: TopologyStrategy? = null

    /**
     * The encoded advertised name this node is currently using.
     * Null when not on a network.
     *
     * This changes when:
     *  - We join or create a network (new event/topology/role)
     *  - Our role changes (topology promotes/demotes us)
     *  - We leave a network (set to null)
     */
    private var currentAdvertisedName: AdvertisedName? = null

    /**
     * This node's current endpoint ID — the encoded advertised name string.
     *
     * In LocalNetwork this also doubles as our address.
     * In real Nearby Connections the OS assigns a separate opaque hardware ID,
     * but we still use the encoded name as our logical identity in message headers.
     */
    val endpointId: String?
        get() = currentAdvertisedName?.encode()

    // -------------------------------------------------------------------------
    // Reply-waiter queue
    // -------------------------------------------------------------------------

    /**
     * Pending request/response waiters.
     * Key = message ID of the outbound request.
     * Value = deferred that completes when the reply arrives.
     */
    private val replyWaiters = ConcurrentHashMap<String, CompletableDeferred<Message>>()

    // -------------------------------------------------------------------------
    // Message listeners (application layer callbacks)
    // -------------------------------------------------------------------------

    private val messageListeners = mutableListOf<(Message) -> Unit>()

    /** Stored so we can unregister it from the network on leaveNetwork(). */
    private val networkMessageListener: (Message) -> Unit = { message -> onMessageReceived(message) }

    fun addMessageListener(listener: (Message) -> Unit) {
        messageListeners.add(listener)
    }

    // -------------------------------------------------------------------------
    // TopologyContext — the bridge between Client and TopologyStrategy
    // -------------------------------------------------------------------------

    private val topologyContext by lazy {
        TopologyContext(
            localEndpointId      = { endpointId ?: error("Not on a network") },
            localEncodedName     = { currentAdvertisedName?.encode() ?: error("Not on a network") },
            network              = requireNetwork(),
            onAdvertisingChanged = { advertising, encodedName ->
                if (advertising && encodedName != null)
                    requireNetwork().startAdvertising(encodedName)
                else
                    requireNetwork().stopAdvertising()
            },
            onScanChanged        = { scanning ->
                scope.launch {
                    if (scanning) requireNetwork().startDiscovery()
                    else          requireNetwork().stopDiscovery()
                }
            },
            onRoleChanged        = { role -> handleRoleChange(role) },
            coroutineScope       = scope,
            onConnect = {endpointId -> requireNetwork().connect(endpointId)},
            networkEvents = requireNetwork().events,
            disconnectFromEndpoint = {endpointId -> requireNetwork().disconnect(endpointId)}
        )
    }

    // -------------------------------------------------------------------------
    // Network attachment
    // -------------------------------------------------------------------------

    /**
     * Attach a Network implementation and wire up listeners.
     * Must be called before any other method.
     */
    fun attachNetwork(network: Network, config: Network.Config) {
        this.network = network

        // Wire up the connection-request callback — topology decides accept/reject
        network.onConnectionRequest = { endpointId, encodedName ->
            //TODO: Should reject based off of topo type and event name (if currently connected)
            val advertisedName = AdvertisedName.decode(encodedName)
            if (advertisedName == null) {
                logger.warn { "Rejecting $endpointId — unparseable name: $encodedName" }
                false
            } else if (advertisedName.eventName != currentAdvertisedName?.eventName) {
                logger.info { "Rejecting $endpointId — wrong event: ${advertisedName.eventName}" }
                false
            } else if (advertisedName.topologyCode != currentAdvertisedName?.topologyCode) {
                logger.info { "Rejecting $endpointId — wrong topology: ${advertisedName.topologyCode}" }
                false
            } else {
                topology?.shouldAcceptConnection(topologyContext, endpointId, advertisedName)
                    ?: false
            }
        }

        network.addListener(networkMessageListener)
        listenToNetworkEvents()
    }

    // -------------------------------------------------------------------------
    // Network lifecycle
    // -------------------------------------------------------------------------

    /**
     * Create a new network with this node as the initial advertiser.
     *
     * [eventName] — human-readable event identifier e.g. "TechConf2024"
     * [topo]      — the topology strategy for this network (creator chooses)
     */
    suspend fun createNetwork(eventName: String, topo: TopologyStrategy) {
        topology = topo

        currentAdvertisedName = AdvertisedName(
            eventName    = eventName,
            topologyCode = topo.topologyCode,
            role         = topo.localRole,
            displayName  = displayName
        )

        // Initialise the network with our identity. Advertising is started by the
        // topology itself inside topo.start() — don't call startAdvertising() here
        // or Nearby will see STATUS_ALREADY_ADVERTISING when the topology does it.
        requireNetwork().init(currentAdvertisedName!!.encode(), Network.Config(defaultTtl = 5))

        // Restore: Automatically initialize Manager if we are creating the network
        if (!Manager.isInitialized()) {
            Manager.init()
        }

        topo.start(topologyContext)

        logger.info { "$displayName created network '$eventName' with topology ${topo.topologyCode}" }
    }

    /**
     * Join an existing network by event name.
     *
     * Starts discovery, waits for an endpoint advertising the matching event name,
     * infers the topology from its advertised name, then requests a connection.
     *
     * The connection result (accepted or rejected) arrives asynchronously via
     * NetworkEvent.EndpointConnected or NetworkEvent.ConnectionRejected on the
     * events flow, which listenToNetworkEvents() handles.
     */
    suspend fun joinNetwork(eventName: String) {
        val net = requireNetwork()
        // Re-register after leaveNetwork() removed it and shutdown() cleared listeners.
        net.removeListener(networkMessageListener)
        net.addListener(networkMessageListener)
        net.init("JOINING:$displayName", Network.Config(defaultTtl = 5))
        val discoveryJob = scope.launch { net.startDiscovery() }

        // Wait for exactly one matching endpoint then stop — using collect here
        // causes the loop to re-fire on every Nearby re-announcement of the same
        // endpoint, triggering repeated init()/topo.start()/connect() calls.
        val ev = net.events
            .filterIsInstance<NetworkEvent.EndpointDiscovered>()
            .first { ev ->
                val advertisedName = AdvertisedName.decode(ev.encodedName) ?: return@first false
                advertisedName.eventName == eventName
            }

        discoveryJob.cancel()
        net.stopDiscovery()

        val advertisedName = AdvertisedName.decode(ev.encodedName)!!
        currentAdvertisedName = AdvertisedName(
            eventName    = eventName,
            topologyCode = advertisedName.topologyCode,
            role         = TopologyStrategy.Role.PEER,
            displayName  = displayName
        )

        val topo = TopologyFactory.create(advertisedName)
        topology = topo
        net.init(currentAdvertisedName!!.encode(), Network.Config(defaultTtl = 5))
        topo.start(topologyContext)
        // Do NOT call net.connect() — topo.start() → evaluateHealth() → startDiscovery()
        // will rediscover the host and connect via the topology's own logic.
    }
    /**
     * Leave the current network gracefully.
     * Stops advertising, stops topology background jobs, and resets identity.
     */
    suspend fun leaveNetwork() {
        val net  = network
        val topo = topology

        // Unregister message listener first — stops PING/PONG processing immediately.
        net?.removeListener(networkMessageListener)

        // Null out topology so disconnect events don't re-trigger evaluateHealth
        // → startDiscovery and spawn new keepalive jobs.
        topology = null
        currentAdvertisedName = null

        topo?.stop()                            // cancel keepalive/discovery jobs
        topo?.disconnectFromAllNodes(topologyContext) // notify peers we're gone

        // shutdown() calls stopAllEndpoints + stopAdvertising and resets state.
        net?.shutdown()

        logger.info { "$displayName left the network" }
    }

    // -------------------------------------------------------------------------
    // Role change — re-encode identity and re-advertise
    // -------------------------------------------------------------------------

    /**
     * Called by TopologyContext when the topology promotes or demotes this node.
     * Updates the encoded advertised name and re-advertise so future joiners
     * see the correct role.
     */
    private fun handleRoleChange(newRole: TopologyStrategy.Role) {
        val current = currentAdvertisedName ?: return
        currentAdvertisedName = current.copy(role = newRole)

        val net = requireNetwork()
        net.stopAdvertising()

        if (topology?.shouldAdvertise(topologyContext) == true) {
            net.startAdvertising(currentAdvertisedName!!.encode())
        }

        logger.info { "$displayName changed role to $newRole, re-advertising as ${currentAdvertisedName!!.encode()}" }
    }

    // -------------------------------------------------------------------------
    // Network event listener
    // -------------------------------------------------------------------------

    private fun listenToNetworkEvents() {
        scope.launch {
            requireNetwork().events.collect { ev ->
                when (ev) {
                    is NetworkEvent.EndpointConnected -> {
                        val advertisedName = AdvertisedName.decode(ev.encodedName)
                        if (advertisedName != null) {
                            topology?.onPeerConnected(topologyContext, ev.endpointId, advertisedName)
                            
                            // Send a HELLO message to announce our presence and metadata
                            sendMessage(Message(
                                to = ev.endpointId,
                                from = endpointId ?: "UNKNOWN",
                                type = MessageType.HELLO,
                                ttl = 1
                            ))

                            // Restore: initiate key exchange if we have a key (we are likely the host/router)
                            if (Manager.isInitialized()) {
                                sendMessage(Message(
                                    to = ev.endpointId,
                                    from = endpointId ?: "UNKNOWN",
                                    type = MessageType.KEY_EXCHANGE,
                                    data = Manager.getKey(),
                                    ttl = 1
                                ))
                            }
                        } else {
                            logger.warn { "Connected to ${ ev.endpointId} but could not decode name: ${ev.encodedName}" }
                        }
                    }

                    is NetworkEvent.EndpointDisconnected -> {
                        topology?.onPeerDisconnected(topologyContext, ev.endpointId)
                    }

                    is NetworkEvent.ConnectionRejected -> {
                        // Topology may want to try a different advertiser
                        logger.info { "Connection to ${ev.endpointId} was rejected" }
                        // TODO: expose this to topology so it can retry or adjust
                    }

                    else -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    /**
     * Send a message, routing it through the topology.
     * The topology resolves which endpoint(s) to actually deliver to.
     */
    fun sendMessage(message: Message) {
        val net  = requireNetwork()

        // Automatically populate metadata if missing
        val enriched = message.copy(
            senderRole = role,
            presentationId = message.presentationId ?: presentationId
        )

        // Apply encryption for application messages if a key is available
        val finalMessage = if (enriched.type == MessageType.TEXT_MESSAGE && enriched.data != null && Manager.isInitialized()) {
            try {
                val encryptedData = Crypto.encryptMessage(enriched.data, Manager.getKey())
                enriched.copy(data = encryptedData)
            } catch (e: Exception) {
                logger.error(e) { "Failed to encrypt message ${enriched.id}" }
                enriched
            }
        } else {
            enriched
        }

        val hops = requireTopology().resolveNextHop(topologyContext, finalMessage)

        if (hops.isEmpty()) {
            logger.warn { "No route found for message to ${finalMessage.to}" }
            return
        }

        hops.forEach { hop -> net.sendMessage(hop, finalMessage) }
    }

    /**
     * Send a message and suspend until a reply arrives or [timeoutMillis] elapses.
     * Returns null on timeout.
     */
    suspend fun sendMessageAndWait(
        message: Message,
        timeoutMillis: Long = 10_000
    ): Message? {
        val deferred = CompletableDeferred<Message>()
        val previous = replyWaiters.put(message.id, deferred)
        require(previous == null) { "Duplicate message id registered: ${message.id}" }

        return try {
            sendMessage(message)
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            replyWaiters.remove(message.id, deferred)
        }
    }

    /**
     * Called by the Network implementation when a message arrives.
     * Completes any reply waiters, then routes to topology or application handlers.
     */
    fun onMessageReceived(message: Message) {
        // Unblock any sendMessageAndWait() calls waiting for this reply
        val replyTo = message.replyTo
        if (!replyTo.isNullOrBlank()) {
            replyWaiters.remove(replyTo)?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(message)
            }
        }
        handleMessage(message)
    }

    /**
     * Route an inbound message to the topology or application layer.
     * Topology gets first refusal — if it returns true the message is consumed.
     */
    private fun handleMessage(message: Message) {
        val consumed = topology?.onMessage(topologyContext, message) ?: false
        if (consumed) return

        // Application-layer messages

        when (message.type) {
            MessageType.TEXT_MESSAGE -> {
                // Decrypt if necessary
                val processedMessage = if (message.data != null && Manager.isInitialized()) {
                    try {
                        val decryptedData = Crypto.decryptMessage(message.data, Manager.getKey())
                        message.copy(data = decryptedData)
                    } catch (e: Exception) {
                        logger.warn { "Failed to decrypt message ${message.id} — possibly wrong key or corrupted" }
                        message
                    }
                } else {
                    message
                }

                val isForMe = processedMessage.to == endpointId || processedMessage.to == "ALL" // ALL for broadcast

                if (isForMe) {
                    // Check presentation differentiation & admin visibility
                    val isSamePresentation = processedMessage.presentationId == presentationId
                    val isAdmin = role == UserRole.ADMIN

                    if (isAdmin || isSamePresentation) {
                        // Deliver to application listeners
                        messageListeners.forEach { it(processedMessage) }
                    } else {
                        logger.debug { "Ignoring message ${processedMessage.id} — different presentation (${processedMessage.presentationId})" }
                    }
                } else if (processedMessage.ttl > 0) {
                    // Not for us — forward it, decrementing TTL to prevent loops
                    val forwarded = processedMessage.copy(ttl = processedMessage.ttl - 1)
                    sendMessage(forwarded)
                } else {
                    logger.warn { "Dropping message ${processedMessage.id} — TTL exhausted" }
                }
            }
            MessageType.HELLO -> logger.info { "HELLO from ${message.from} (${message.senderRole})" }
            MessageType.KEY_EXCHANGE -> {
                if (message.data != null) {
                    Manager.init(message.data)
                    logger.info { "Security Manager initialized with key from ${message.from}" }
                }
            }
            else -> logger.warn { "Unhandled message type: ${message.type}" }
        }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    fun isConnected(): Boolean = currentAdvertisedName != null

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    private fun requireNetwork()  = network  ?: error("Network is not attached")
    private fun requireTopology() = topology ?: error("Topology is not configured — join or create a network first")
}
