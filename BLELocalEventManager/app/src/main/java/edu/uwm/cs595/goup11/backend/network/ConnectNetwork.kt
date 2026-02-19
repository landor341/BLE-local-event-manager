package edu.uwm.cs595.goup11.backend.network



import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import kotlinx.coroutines.flow.Flow


import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Nearby Connections implementation
 *
 */
class ConnectNetwork(
    private val context: Context,
    /** All "network beacons" advertise here so clients can browse events/networks. */
    private val directoryServiceId: String = "edu.uwm.cs595.DIRECTORY",
    override val state: StateFlow<NetworkState>,
    override val events: SharedFlow<NetworkEvent>,
    override val currentSessionId: StateFlow<String?>,
    override val discoveredNetworks: Flow<String>
) : Network {
    override val logger: KLogger = KotlinLogging.logger {  }

    // --- Provided by init() ---
    private lateinit var client: Client
    private lateinit var config: Network.Config

    // --- Nearby client ---
    private lateinit var mConnectionsClient: ConnectionsClient

    // --- State ---
    private var isScanning = false
    private var isAdvertising = false
    private var currentServiceId: String? = null

    private val listeners = mutableListOf<(Message) -> Unit>()

    // endpointId -> endpointName
    private val discovered = ConcurrentHashMap<String, String>()
    private val connected = ConcurrentHashMap<String, String>()

    // --- Join flow state ---
    private var joinCallback: ((Boolean, String) -> Unit)? = null
    private var pendingJoinEndpointId: String? = null
    private var pendingJoinRouterName: String? = null

    // --- Scan results ---
    private val discoveredNetworksSet = mutableSetOf<String>()
    private val discoveredNetworksFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 128
    )

    // --- Reply waiters (keyed by "message id we are waiting to be replied to") ---
    private val replyWaiters = ConcurrentHashMap<String, CompletableDeferred<Message>>()

    /**
     * Required by Nearby Connections API.
     * Receives bytes, parses Message.fromBytes, then:
     * - completes any sendMessageAndWait waiter using msg.replyTo
     * - notifies listeners
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return

            val msg = Message.fromBytes(bytes)

            // Complete any waiter if this is a reply to a known message id
            val replyToId = msg.replyTo
            if (!replyToId.isNullOrBlank()) {
                replyWaiters.remove(replyToId)?.let { deferred ->
                    if (!deferred.isCompleted) deferred.complete(msg)
                }
            }

            notifyListeners(msg)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Optional: implement progress reporting if you add FILE/STREAM payload types
        }
    }

    /**
     * Required by Nearby Connections API.
     * Accepts connections and tracks connected endpoints.
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // Track the advertiser name
            discovered[endpointId] = connectionInfo.endpointName
            pendingJoinRouterName = connectionInfo.endpointName

            // Accept the connection so payloads can flow
            mConnectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val name = pendingJoinRouterName ?: discovered[endpointId] ?: endpointId
                    connected[endpointId] = name

                    // If this was part of join(), complete join callback
                    joinCallback?.let { cb ->
                        cb(true, name)
                        joinCallback = null
                        pendingJoinEndpointId = null
                        pendingJoinRouterName = null
                    }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED,
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    joinCallback?.let { cb ->
                        cb(false, "")
                        joinCallback = null
                    }
                    pendingJoinEndpointId = null
                    pendingJoinRouterName = null
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            discovered.remove(endpointId)
            connected.remove(endpointId)
            // Any outstanding waits will timeout naturally; if you prefer, you can fail them here.
        }
    }

    /**
     * Used by join(sessionId): discovers routers advertising under serviceId=sessionId,
     * then requests a connection to the first endpoint found.
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discovered[endpointId] = info.endpointName

            // Join-first behavior: ignore additional endpoints once we picked one.
            if (pendingJoinEndpointId != null) return

            pendingJoinEndpointId = endpointId
            pendingJoinRouterName = info.endpointName

            // Stop discovery once we pick one router
            mConnectionsClient.stopDiscovery()

            mConnectionsClient.requestConnection(
                client.id.ifBlank { "PHONE_${android.os.Build.MODEL}" },
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                joinCallback?.invoke(false, "")
                joinCallback = null
                pendingJoinEndpointId = null
                pendingJoinRouterName = null
            }
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
            if (pendingJoinEndpointId == endpointId) {
                joinCallback?.invoke(false, "")
                joinCallback = null
                pendingJoinEndpointId = null
                pendingJoinRouterName = null
            }
        }
    }

    /**
     * Used by startScan(): discovers "directory beacons" advertising on directoryServiceId,
     * parses event/network name from endpointName (your standardized string).
     */
    private val directoryDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Parses by event name in the endpointName
            val eventName = parseFieldPipeColon(info.endpointName, "EVT")
                ?: return

            if (discoveredNetworksSet.add(eventName)) {
                discoveredNetworksFlow.tryEmit(eventName)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Optional: if you want removals, track endpointId -> eventName and emit removals
        }
    }

    // ------------------- Network interface methods -------------------

    override fun init(client: Client, config: Network.Config) {
        this.client = client
        this.config = config
        this.mConnectionsClient = Nearby.getConnectionsClient(context)
    }

    override fun shutdown() {
        if (::mConnectionsClient.isInitialized) {
            // stops advertising, discovery, and disconnects all endpoints
            mConnectionsClient.stopAllEndpoints()
        }

        isScanning = false
        isAdvertising = false
        currentServiceId = null

        joinCallback = null
        pendingJoinEndpointId = null
        pendingJoinRouterName = null

        discovered.clear()
        connected.clear()

        discoveredNetworksSet.clear()

        // Complete all waiters and pass a RuntimeException
        replyWaiters.values.forEach { deferred ->
            if (!deferred.isCompleted) deferred.completeExceptionally(RuntimeException("Network shutdown"))
        }
        replyWaiters.clear()

        listeners.clear()
    }

    /**
     * Scan for active networks/events (directory beacons).
     * Routers (or hosts) should advertise on directoryServiceId with endpointName containing EVT:<name>.
     */
    override suspend fun startScan() {
        if (!::mConnectionsClient.isInitialized) throw Error("Connections client not created")
        if (isScanning) return
        isScanning = true
        discoveredNetworksSet.clear()

        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        mConnectionsClient.startDiscovery(
            directoryServiceId,
            directoryDiscoveryCallback,
            options
        ).addOnFailureListener {
            isScanning = false
        }
    }

    override suspend fun stopScan() {
        if (!::mConnectionsClient.isInitialized) return
        if (!isScanning) return
        isScanning = false
        mConnectionsClient.stopDiscovery()
    }


    fun observeDiscoveredNetworks(): Flow<String> {
        return discoveredNetworksFlow.asSharedFlow()
    }

    /**
     * Join a session/network identified by sessionId (serviceId).
     */
    override suspend fun join(sessionId: String): Peer {
//        if (!::mConnectionsClient.isInitialized) {
//
//            return
//        }
//
//        joinCallback = callback
//        pendingJoinEndpointId = null
//        pendingJoinRouterName = null
//
//        val discoveryOptions = DiscoveryOptions.Builder()
//            .setStrategy(Strategy.P2P_CLUSTER)
//            .build()
//
//        mConnectionsClient.startDiscovery(
//            sessionId,
//            endpointDiscoveryCallback,
//            discoveryOptions
//        ).addOnFailureListener {
//            joinCallback?.invoke(false, "")
//            joinCallback = null
//        }

        return Peer("test", "test")
    }

    /**
     * Leave: disconnect from all peers and stop discovery.
     * (Does not necessarily stop advertising; call deleteNetwork() for that.)
     */
    override fun leave() {
        if (!::mConnectionsClient.isInitialized) return

        // Disconnect from all connected endpoints
        connected.keys.forEach { endpointId ->
            mConnectionsClient.disconnectFromEndpoint(endpointId)
        }
        connected.clear()

        // Stop any ongoing discovery/join attempt
        mConnectionsClient.stopDiscovery()

        joinCallback = null
        pendingJoinEndpointId = null
        pendingJoinRouterName = null
    }

    /**
     * Create a network: in Nearby terms, start advertising.
     * Your interface doesn't pass a sessionId here, so we use:
     * - currentServiceId if set previously
     * - else client.id as a fallback
     */
    override suspend fun create(eventName: String) {
//        val sid = currentServiceId ?: client.id
//        startAdvertising(sid)
    }

    /**
     * Delete network: stop advertising and optionally disconnect peers.
     */
    override suspend fun deleteNetwork() {
        stopAdvertising()

        connected.keys.forEach { endpointId ->
            mConnectionsClient.disconnectFromEndpoint(endpointId)
        }
        connected.clear()

        currentServiceId = null
    }

    /**
     * Send a message.
     * "to" may be an endpointId OR an endpointName (router name). We attempt to resolve.
     */
    override fun sendMessage(to: String, message: Message) {
        if (!::mConnectionsClient.isInitialized) throw Error("Connections client not created")

        val endpointId = resolveToEndpointId(to) ?: to
        val bytes = message.toBytes()

        mConnectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    /**
     * Send a message and wait for a reply correlated by replyTo:
     * - We send a request message with id = message.id
     * - The reply must have replyTo = request.id
     */
    override suspend fun sendMessageAndWait(
        to: String,
        message: Message,
        timeoutMillis: Long
    ): Message? {
        if (!::mConnectionsClient.isInitialized) throw Error("Connections client not created")

        // Register waiter BEFORE sending
        val deferred = CompletableDeferred<Message>()
        replyWaiters[message.id] = deferred

        // Send
        sendMessage(to, message)

        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            replyWaiters.remove(message.id)
            null
        } catch (e: Exception) {
            replyWaiters.remove(message.id)
            throw e
        }
    }

    override fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    override fun notifyListeners(message: Message) {
        listeners.forEach { it.invoke(message) }
    }



    /**
     * Start advertising on a serviceId (session/network id).
     *
     * IMPORTANT: To support your browse UI, you likely want routers to advertise
     * a *directory beacon* on directoryServiceId with endpointName like:
     *   "EVT:UWM CS Networking Event|TYP:R|N:${client.id}"
     *
     * This method starts advertising on the provided serviceId directly.
     * (Use this for "inside the network" discovery/join.)
     */
    override fun startAdvertising() {
//        if (!::mConnectionsClient.isInitialized) throw Error("Connections client not created")
//
//        currentServiceId = serviceId
//
//        val advOptions = AdvertisingOptions.Builder()
//            .setStrategy(Strategy.P2P_CLUSTER)
//            .build()
//
//        // Use something meaningful as endpointName (router id/client id)
//        val endpointName = client.id.ifBlank { "PHONE_${android.os.Build.MODEL}" }
//
//        mConnectionsClient.startAdvertising(
//            endpointName,
//            TODO("IMPLEMENT"),
//            connectionLifecycleCallback,
//            advOptions
//        ).addOnSuccessListener {
//            isAdvertising = true
//        }.addOnFailureListener {
//            isAdvertising = false
//        }
    }

    override fun stopAdvertising() {
        if (!::mConnectionsClient.isInitialized) return
        isAdvertising = false
        mConnectionsClient.stopAdvertising()
    }

    override fun onPeerConnect(peer: Peer) {
        TODO("Not yet implemented")
    }

    override fun onPeerDisconnect(peer: Peer) {
        TODO("Not yet implemented")
    }

    // ------------------- Helpers -------------------

    /**
     * Resolve "to" if the caller passed endpointName instead of endpointId.
     */
    private fun resolveToEndpointId(to: String): String? {
        // Prefer connected endpoints
        connected.entries.firstOrNull { it.value == to }?.let { return it.key }
        // Fall back to discovered endpoints
        discovered.entries.firstOrNull { it.value == to }?.let { return it.key }
        return null
    }

    /**
     * Parse fields from your standardized endpointName format:
     *   "EVT:Event Name|TYP:R|N:Router1"
     */
    private fun parseFieldPipeColon(raw: String, key: String): String? {
        //TODO: This should probably be in its own Object so all methods and classes can use it
        val token = "$key:"
        val start = raw.indexOf(token)
        if (start == -1) return null

        val after = start + token.length
        val end = raw.indexOf('|', after).let { if (it == -1) raw.length else it }

        val value = raw.substring(after, end).trim()
        return value.takeIf { it.isNotEmpty() }
    }

    /**
     * Utility: compute endpointName byte size (UTF-8) if you want to enforce limits.
     */
    fun endpointNameBytes(name: String): Int = name.toByteArray(StandardCharsets.UTF_8).size
}