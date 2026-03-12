package edu.uwm.cs595.goup11.backend.network

import android.content.Context
import com.google.android.gms.nearby.Nearby
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
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Google Nearby Connections implementation of [Network].
 *
 * Maps the Nearby Connections callback-based API onto the coroutine/flow-based
 * [Network] interface. All application logic (routing, topology, sessions) stays
 * in Client and topology — this class only moves bytes.
 *
 * Key Nearby Connections concepts and how they map here:
 *
 *  startAdvertising(encodedName) → Nearby.startAdvertising(encodedName, serviceId, ...)
 *      The encodedName IS the Nearby "endpointName" string. Discovering devices read it
 *      to learn the topology, role, event, and display name before deciding to connect.
 *
 *  startDiscovery() → Nearby.startDiscovery(serviceId, ...)
 *      Found endpoints are emitted as NetworkEvent.EndpointDiscovered with both the
 *      hardware endpointId (assigned by Nearby) and the encodedName.
 *
 *  connect(endpointId) → Nearby.requestConnection(localEndpointId, endpointId, ...)
 *      Fires onConnectionInitiated on BOTH sides. Each side independently decides
 *      to accept or reject via [onConnectionRequest]. Only if both sides accept does
 *      Nearby fire onConnectionResult(STATUS_OK) → NetworkEvent.EndpointConnected.
 *
 *  onConnectionRequest callback → called in onConnectionInitiated on the receiving side.
 *      Client sets this and delegates to the topology's shouldAcceptConnection().
 */
class ConnectNetwork(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : Network {

    override val logger: KLogger = KotlinLogging.logger {}

    // -------------------------------------------------------------------------
    // Service ID — the "channel" all nodes on this app share
    // -------------------------------------------------------------------------

    /**
     * All nodes in the same app must use the same serviceId so they can discover
     * each other. This matches the applicationId in your build.gradle.
     */
    private val serviceId = "edu.uwm.cs595.group11"

    // -------------------------------------------------------------------------
    // Reactive state
    // -------------------------------------------------------------------------

    private val _state  = MutableStateFlow<NetworkState>(NetworkState.Idle)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, replay = 1)
    override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private lateinit var connectionsClient: ConnectionsClient

    /** Our local endpoint ID — the encoded advertised name string set by init() */
    private var localEndpointId: String? = null

    private var isAdvertising = false
    private var isDiscovering = false

    private val listeners = mutableListOf<(Message) -> Unit>()

    /**
     * endpointId (hardware Nearby ID) → encodedName (our advertised name string)
     * Populated when an endpoint is discovered or connects.
     */
    private val knownEndpoints = ConcurrentHashMap<String, String>()

    /**
     * Set by Client. Called when a remote endpoint wants to connect to us
     * (i.e. inside onConnectionInitiated on the receiving side).
     * Returns true to accept, false to reject.
     */
    override var onConnectionRequest: suspend (endpointId: String, encodedName: String) -> Boolean =
        { _, _ -> false }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun init(localEndpointId: String, config: Network.Config) {
        this.localEndpointId = localEndpointId
        this.connectionsClient = Nearby.getConnectionsClient(context)
        _state.value = NetworkState.Idle
        logger.debug { "ConnectNetwork initialised as $localEndpointId" }
    }

    override fun shutdown() {
        if (::connectionsClient.isInitialized) {
            connectionsClient.stopAllEndpoints()
        }
        isAdvertising = false
        isDiscovering = false
        knownEndpoints.clear()
        listeners.clear()
        localEndpointId = null
        _state.value = NetworkState.Idle
        logger.debug { "ConnectNetwork shut down" }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    /**
     * Start advertising with [encodedName] as the Nearby endpointName.
     * Any discovering device will receive this string in EndpointDiscovered,
     * allowing it to decode our identity (role, topology, event, display name)
     * before deciding to connect.
     */
    override fun startAdvertising(encodedName: String) {
        requireClient()

        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            encodedName,        // ← this becomes the Nearby "endpointName"
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            isAdvertising = true
            _state.value  = NetworkState.Advertising
            logger.debug { "$localEndpointId started advertising as '$encodedName'" }
        }.addOnFailureListener { e ->
            isAdvertising = false
            _events.tryEmit(NetworkEvent.ConnectionRejected("ADVERTISING_FAILED"))
            logger.error { "startAdvertising failed: ${e.message}" }
        }
    }

    override fun stopAdvertising() {
        if (!::connectionsClient.isInitialized) return
        isAdvertising = false
        connectionsClient.stopAdvertising()
        if (_state.value is NetworkState.Advertising) {
            _state.value = NetworkState.Idle
        }
        logger.debug { "$localEndpointId stopped advertising" }
    }

    // -------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------

    /**
     * Start scanning. Every discovered endpoint is emitted as
     * [NetworkEvent.EndpointDiscovered] with the hardware endpointId and the
     * encodedName string the remote node passed to startAdvertising().
     */
    override suspend fun startDiscovery() {
        requireClient()
        if (isDiscovering) return

        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            isDiscovering = true
            _state.value  = NetworkState.Discovering
            logger.debug { "$localEndpointId started discovery" }
        }.addOnFailureListener { e ->
            isDiscovering = false
            logger.error { "startDiscovery failed: ${e.message}" }
        }
    }

    override suspend fun stopDiscovery() {
        if (!::connectionsClient.isInitialized) return
        if (!isDiscovering) return
        isDiscovering = false
        connectionsClient.stopDiscovery()
        if (_state.value is NetworkState.Discovering) {
            _state.value = NetworkState.Idle
        }
        logger.debug { "$localEndpointId stopped discovery" }
    }

    // -------------------------------------------------------------------------
    // Connections
    // -------------------------------------------------------------------------

    /**
     * Request a connection to [endpointId].
     *
     * Does NOT block — the result arrives asynchronously:
     *  - [NetworkEvent.EndpointConnected]  if both sides accepted
     *  - [NetworkEvent.ConnectionRejected] if either side rejected or an error occurred
     *
     * [localEndpointId] is passed as our "endpointName" to Nearby so the remote
     * side receives our encoded identity in onConnectionInitiated.
     */
    override suspend fun connect(endpointId: String) {
        val localId = requireLocalEndpointId()

        connectionsClient.requestConnection(
            localId,    // our encoded name — the remote side sees this in ConnectionInfo.endpointName
            endpointId,
            connectionLifecycleCallback
        ).addOnFailureListener { e ->
            logger.error { "requestConnection to $endpointId failed: ${e.message}" }
            _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
        }

        logger.debug { "$localId requested connection to $endpointId" }
    }

    override fun disconnect(endpointId: String) {
        if (!::connectionsClient.isInitialized) return
        connectionsClient.disconnectFromEndpoint(endpointId)
        knownEndpoints.remove(endpointId)
        // Nearby will fire onDisconnected which emits EndpointDisconnected
        logger.debug { "$localEndpointId disconnected from $endpointId" }
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    override fun sendMessage(endpointId: String, message: Message) {
        requireClient()
        val bytes = message.toBytes()
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
        logger.debug { "$localEndpointId sent ${message.type} to $endpointId (id=${message.id})" }
    }

    override fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    override fun notifyListeners(message: Message) {
        listeners.forEach { it.invoke(message) }
    }

    // -------------------------------------------------------------------------
    // Nearby Connections callbacks
    // -------------------------------------------------------------------------

    /**
     * Handles the two-step Nearby connection handshake:
     *
     *  onConnectionInitiated — fires on BOTH sides when requestConnection() is called.
     *      We call [onConnectionRequest] (set by Client) to decide accept/reject,
     *      then call acceptConnection() or rejectConnection() accordingly.
     *
     *  onConnectionResult — fires on both sides once both have responded.
     *      STATUS_OK → emit EndpointConnected.
     *      STATUS_CONNECTION_REJECTED / STATUS_ERROR → emit ConnectionRejected.
     *
     *  onDisconnected — fires on both sides when a connection is lost.
     *      Emits EndpointDisconnected.
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // info.endpointName is the encoded name the remote node advertised with
            val encodedName = info.endpointName
            knownEndpoints[endpointId] = encodedName

            logger.debug { "$localEndpointId: connection initiated from $endpointId ('$encodedName')" }

            // Ask Client/topology whether to accept — must run in a coroutine since
            // onConnectionRequest is a suspend function
            scope.launch {
                val accept = onConnectionRequest(endpointId, encodedName)

                if (accept) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    logger.debug { "$localEndpointId accepted connection from $endpointId" }
                } else {
                    connectionsClient.rejectConnection(endpointId)
                    logger.debug { "$localEndpointId rejected connection from $endpointId" }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val encodedName = knownEndpoints[endpointId] ?: endpointId
                    logger.debug { "$localEndpointId: connection established with $endpointId" }
                    _events.tryEmit(NetworkEvent.EndpointConnected(endpointId, encodedName))
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    knownEndpoints.remove(endpointId)
                    logger.debug { "$localEndpointId: connection rejected by $endpointId" }
                    _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    knownEndpoints.remove(endpointId)
                    logger.error { "$localEndpointId: connection error with $endpointId (${result.status.statusMessage})" }
                    _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            knownEndpoints.remove(endpointId)
            logger.debug { "$localEndpointId: disconnected from $endpointId" }
            _events.tryEmit(NetworkEvent.EndpointDisconnected(endpointId))
        }
    }

    /**
     * Fires during active discovery whenever a remote node is found or lost.
     *
     * onEndpointFound — emits EndpointDiscovered with the hardware endpointId and
     *   the encodedName string (the remote node's advertised identity).
     *   Client decodes this to decide whether to connect.
     *
     * onEndpointLost — the endpoint went out of range or stopped advertising.
     *   We don't emit an event here because we were never connected to it —
     *   it was only "discovered". If it was connected, onDisconnected fires instead.
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val encodedName = info.endpointName

            // Nearby Connections can surface our own advertisement back to us.
            // Drop it — we never want to connect to ourselves.
            if (encodedName == localEndpointId) {
                logger.debug { "Ignoring self-discovery (endpointId=$endpointId)" }
                return
            }

            knownEndpoints[endpointId] = encodedName

            logger.debug { "$localEndpointId discovered $endpointId ('$encodedName')" }

            _events.tryEmit(NetworkEvent.EndpointDiscovered(endpointId, encodedName))
        }

        override fun onEndpointLost(endpointId: String) {
            // Only remove from known if we never established a full connection
            knownEndpoints.remove(endpointId)
            logger.debug { "$localEndpointId lost sight of $endpointId" }
            // No event emitted — Client only acts on discovered endpoints it chose to connect to
        }
    }

    /**
     * Handles incoming byte payloads from connected endpoints.
     * Parses the bytes into a [Message] and notifies all listeners.
     */
    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return

            val message = Message.fromBytes(bytes)
            logger.debug { "$localEndpointId received ${message.type} from $endpointId" }

            notifyListeners(message)
            _events.tryEmit(NetworkEvent.MessageReceived(message))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op for BYTES payloads — only relevant for FILE/STREAM types
        }
    }

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    private fun requireLocalEndpointId(): String =
        localEndpointId ?: error("ConnectNetwork has not been initialised — call init() first")

    private fun requireClient() {
        if (!::connectionsClient.isInitialized) {
            error("ConnectNetwork has not been initialised — call init() first")
        }
    }
}