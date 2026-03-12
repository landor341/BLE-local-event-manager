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
        val wasAlreadyInit = this.localEndpointId != null
        this.localEndpointId = localEndpointId
        this.connectionsClient = Nearby.getConnectionsClient(context)
        _state.value = NetworkState.Idle
        logger.warn { "[CN] init() — localId='$localEndpointId' (re-init=$wasAlreadyInit)" }
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
            encodedName,
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            isAdvertising = true
            _state.value  = NetworkState.Advertising
            logger.warn { "[CN] startAdvertising SUCCESS — serviceId='$serviceId' encodedName='$encodedName'" }
        }.addOnFailureListener { e ->
            isAdvertising = false
            _events.tryEmit(NetworkEvent.ConnectionRejected("ADVERTISING_FAILED"))
            logger.error { "[CN] startAdvertising FAILED: ${e.message}" }
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
        if (isDiscovering) {
            logger.warn { "[CN] startDiscovery() called but already discovering — skipping" }
            return
        }

        logger.warn { "[CN] startDiscovery() — serviceId='$serviceId' localId='$localEndpointId'" }

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
            logger.warn { "[CN] startDiscovery SUCCESS" }
        }.addOnFailureListener { e ->
            isDiscovering = false
            logger.error { "[CN] startDiscovery FAILED: ${e.message}" }
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
        logger.warn { "[CN] stopDiscovery()" }
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
        logger.warn { "[CN] connect() — localId='$localId' → target='$endpointId'" }

        connectionsClient.requestConnection(
            localId,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            logger.warn { "[CN] requestConnection sent successfully to $endpointId" }
        }.addOnFailureListener { e ->
            logger.error { "[CN] requestConnection to $endpointId FAILED: ${e.message}" }
            _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
        }
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
            val encodedName = info.endpointName
            knownEndpoints[endpointId] = encodedName

            logger.warn { "[CN] onConnectionInitiated — from='$endpointId' encodedName='$encodedName' incoming=${info.isIncomingConnection}" }

            scope.launch {
                val accept = onConnectionRequest(endpointId, encodedName)
                logger.warn { "[CN] onConnectionRequest result for '$endpointId': accept=$accept" }

                if (accept) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    logger.warn { "[CN] acceptConnection sent for $endpointId" }
                } else {
                    connectionsClient.rejectConnection(endpointId)
                    logger.warn { "[CN] rejectConnection sent for $endpointId" }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val code = result.status.statusCode
            val msg  = result.status.statusMessage
            logger.warn { "[CN] onConnectionResult — endpointId='$endpointId' statusCode=$code message='$msg'" }

            when (code) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val encodedName = knownEndpoints[endpointId] ?: endpointId
                    logger.warn { "[CN] CONNECTION ESTABLISHED with $endpointId (encodedName='$encodedName')" }
                    _events.tryEmit(NetworkEvent.EndpointConnected(endpointId, encodedName))
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    knownEndpoints.remove(endpointId)
                    logger.warn { "[CN] CONNECTION REJECTED by $endpointId" }
                    _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    knownEndpoints.remove(endpointId)
                    logger.error { "[CN] CONNECTION ERROR with $endpointId: $msg" }
                    _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
                }

                else -> {
                    logger.error { "[CN] onConnectionResult unknown statusCode=$code for $endpointId" }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            knownEndpoints.remove(endpointId)
            logger.warn { "[CN] onDisconnected — endpointId='$endpointId'" }
            _events.tryEmit(NetworkEvent.EndpointDisconnected(endpointId))
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val encodedName = info.endpointName

            if (encodedName == localEndpointId) {
                logger.warn { "[CN] onEndpointFound — ignoring self-discovery (endpointId=$endpointId)" }
                return
            }

            knownEndpoints[endpointId] = encodedName
            logger.warn { "[CN] onEndpointFound — endpointId='$endpointId' encodedName='$encodedName'" }
            _events.tryEmit(NetworkEvent.EndpointDiscovered(endpointId, encodedName))
        }

        override fun onEndpointLost(endpointId: String) {
            knownEndpoints.remove(endpointId)
            logger.warn { "[CN] onEndpointLost — endpointId='$endpointId'" }
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