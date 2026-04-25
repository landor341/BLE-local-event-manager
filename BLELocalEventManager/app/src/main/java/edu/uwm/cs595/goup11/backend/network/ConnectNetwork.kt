package edu.uwm.cs595.goup11.backend.network

import android.content.Context
import android.util.Log
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

    // Direct Logcat helpers — KotlinLogging routes through SLF4J which has no
    // Android backend, so all logger.* calls are silently dropped on device.
    // Use cn()/cne() instead; filter Logcat by tag "CN".
    private fun cn(msg: String) = Log.w("CN", msg)
    private fun cne(msg: String) = Log.e("CN", msg)

    // -------------------------------------------------------------------------
    // Service ID
    // -------------------------------------------------------------------------

    private val serviceId = "edu.uwm.cs595.group11"

    // -------------------------------------------------------------------------
    // Reactive state
    // -------------------------------------------------------------------------

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Idle)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    // Independent flags — _state can only hold ONE value at a time so it loses
    // advertising status the moment discovery starts. These track them separately.
    private val _isAdvertising = MutableStateFlow(false)
    override val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, replay = 1)
    override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private lateinit var connectionsClient: ConnectionsClient

    /** Our local endpoint ID — the encoded advertised name string set by init() */
    private var localEndpointId: String? = null

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

        if (!wasAlreadyInit) {
            // First init — wipe any lingering Nearby state from a previous app session
            // so ghost networks don't keep appearing on discovering devices.
            connectionsClient.stopAllEndpoints()
            _isAdvertising.value = false
            _isDiscovering.value = false
            _state.value = NetworkState.Idle
            cn("[CN] init() localId='$localEndpointId' (fresh)")
        } else {
            // Re-init (e.g. joiner updating identity after discovering the host).
            // Do NOT call stopAllEndpoints here — it would wipe Nearby's knowledge
            // of the endpoint we just discovered, causing STATUS_ENDPOINT_IO_ERROR
            // when we immediately call connect() after this.
            cn("[CN] init() localId='$localEndpointId' (re-init, skipping stopAllEndpoints)")
        }
    }

    override fun shutdown() {
        if (::connectionsClient.isInitialized) connectionsClient.stopAllEndpoints()
        _isAdvertising.value = false
        _isDiscovering.value = false
        knownEndpoints.clear()
        listeners.clear()
        localEndpointId = null
        _state.value = NetworkState.Idle
        cn("[CN] shutdown()")
    }

    /** knownEndpoints maps hardwareId -> encodedName; we need the reverse. */
    override fun encodedNameToHardwareId(encodedName: String): String? =
        knownEndpoints.entries.firstOrNull { it.value == encodedName }?.key


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
        if (_isAdvertising.value) {
            cn("[CN] startAdvertising() already advertising — skipping")
            return
        }

        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startAdvertising(
            encodedName,
            serviceId,
            connectionLifecycleCallback,
            options
        )
            .addOnSuccessListener {
                _isAdvertising.value = true
                _state.value = NetworkState.Advertising
                cn("[CN] startAdvertising SUCCESS")
            }
            .addOnFailureListener { e ->
                val msg = e.message ?: ""
                if ("8002" in msg) {
                    // Already discovering — not a real failure, leave _isDiscovering as-is
                    cn("[CN] startDiscovery() already running — ignoring STATUS_ALREADY_DISCOVERING")
                    return@addOnFailureListener
                }
                _isDiscovering.value = false
                cne("[CN] startDiscovery FAILED: $msg")
            }
    }

    override fun stopAdvertising() {
        if (!::connectionsClient.isInitialized) return
        _isAdvertising.value = false
        connectionsClient.stopAdvertising()
        if (_state.value is NetworkState.Advertising) _state.value = NetworkState.Idle
        cn("[CN] stopAdvertising()")
    }

    // -------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------

    override suspend fun startDiscovery() {
        requireClient()
        if (_isDiscovering.value) {
            cn("[CN] startDiscovery() already discovering — skipping")
            return
        }
        cn("[CN] startDiscovery() serviceId='$serviceId' localId='$localEndpointId'")

        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                _isDiscovering.value = true
                _state.value = NetworkState.Discovering
                cn("[CN] startDiscovery SUCCESS")
            }
            .addOnFailureListener { e ->
                val msg = e.message ?: ""
                if ("8001" in msg) {
                    cn("[CN] startAdvertising() already running — ignoring STATUS_ALREADY_ADVERTISING")
                    return@addOnFailureListener
                }
                _isAdvertising.value = false
                cne("[CN] startAdvertising FAILED: $msg")
            }
    }

    override suspend fun stopDiscovery() {
        if (!::connectionsClient.isInitialized) return
        if (!_isDiscovering.value) return
        _isDiscovering.value = false
        connectionsClient.stopDiscovery()
        if (_state.value is NetworkState.Discovering) _state.value = NetworkState.Idle
        cn("[CN] stopDiscovery()")
    }

    // -------------------------------------------------------------------------
    // Connections
    // -------------------------------------------------------------------------

    override suspend fun connect(endpointId: String) {
        val localId = requireLocalEndpointId()
        cn("[CN] connect() localId='$localId' target='$endpointId'")

        connectionsClient.requestConnection(localId, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { cn("[CN] requestConnection sent to $endpointId") }
            .addOnFailureListener { e ->
                val msg = e.message ?: ""
                // STATUS_ALREADY_CONNECTED_TO_ENDPOINT is not an error — ignore it
                if ("8003" in msg) {
                    cn("[CN] connect() to $endpointId skipped — already connected")
                    return@addOnFailureListener
                }
                cne("[CN] requestConnection to $endpointId FAILED: $msg")
                _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
            }
    }

    override fun disconnect(endpointId: String) {
        if (!::connectionsClient.isInitialized) return
        connectionsClient.disconnectFromEndpoint(endpointId)
        knownEndpoints.remove(endpointId)
        cn("[CN] disconnect() endpointId='$endpointId'")
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    override fun sendMessage(endpointId: String, message: Message) {
        requireClient()
        val bytes = message.toBytes()
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
        cn("[CN] sendMessage() to='$endpointId' type=${message.type} id=${message.id}")
    }

    override fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: (Message) -> Unit) {
        listeners.remove(listener)
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
            cn("[CN] onConnectionInitiated from='$endpointId' encodedName='$encodedName' incoming=${info.isIncomingConnection}")

            scope.launch {
                val accept = onConnectionRequest(endpointId, encodedName)
                cn("[CN] onConnectionRequest result for '$endpointId': accept=$accept")
                if (accept) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    cn("[CN] acceptConnection sent for $endpointId")
                } else {
                    connectionsClient.rejectConnection(endpointId)
                    cn("[CN] rejectConnection sent for $endpointId")
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val code = result.status.statusCode
            val msg = result.status.statusMessage
            cn("[CN] onConnectionResult endpointId='$endpointId' statusCode=$code msg='$msg'")

            when (code) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val encodedName = knownEndpoints[endpointId] ?: endpointId
                    cn("[CN] CONNECTED with $endpointId encodedName='$encodedName'")
                    _events.tryEmit(NetworkEvent.EndpointConnected(endpointId, encodedName))
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    knownEndpoints.remove(endpointId)
                    cn("[CN] REJECTED by $endpointId")
                    _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    knownEndpoints.remove(endpointId)
                    cne("[CN] ERROR with $endpointId: $msg")
                    _events.tryEmit(NetworkEvent.ConnectionRejected(endpointId))
                }

                else -> cne("[CN] onConnectionResult unknown statusCode=$code for $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            knownEndpoints.remove(endpointId)
            cn("[CN] onDisconnected endpointId='$endpointId'")
            _events.tryEmit(NetworkEvent.EndpointDisconnected(endpointId))
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val encodedName = info.endpointName

            // Ignore self
            if (encodedName == localEndpointId) {
                cn("[CN] onEndpointFound ignoring self (endpointId=$endpointId)")
                return
            }
            // Ignore transient scanner/joiner identities, these are used for seeing what networks exist
            if (encodedName.startsWith("SCANNER:") || encodedName.startsWith("JOINING:")) {
                cn("[CN] onEndpointFound ignoring transient '$encodedName'")
                return
            }
            // Ignore anything we can't decode
            if (AdvertisedName.decode(encodedName) == null) {
                cn("[CN] onEndpointFound ignoring undecodable '$encodedName'")
                return
            }

            knownEndpoints[endpointId] = encodedName
            cn("[CN] onEndpointFound endpointId='$endpointId' encodedName='$encodedName'")
            _events.tryEmit(NetworkEvent.EndpointDiscovered(endpointId, encodedName))
        }

        override fun onEndpointLost(endpointId: String) {
            knownEndpoints.remove(endpointId)
            cn("[CN] onEndpointLost endpointId='$endpointId'")
        }
    }

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val message = Message.fromBytes(bytes)
            cn("[CN] onPayloadReceived from='$endpointId' type=${message.type} id=${message.id}")
            notifyListeners(message)
            _events.tryEmit(NetworkEvent.MessageReceived(message))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op for BYTES payloads
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