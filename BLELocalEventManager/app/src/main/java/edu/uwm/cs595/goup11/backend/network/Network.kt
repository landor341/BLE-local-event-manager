package edu.uwm.cs595.goup11.backend.network

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents the current state of the network transport layer.
 *
 * Note: There is no "Joined" or "Hosting" concept here — those are application-layer
 * ideas owned by Client. The network only knows about advertising, discovering,
 * and individual point-to-point connections.
 */
sealed class NetworkState {
    data object Idle : NetworkState()
    data object Advertising : NetworkState()
    data object Discovering : NetworkState()
    data class Error(val reason: String) : NetworkState()

    // Deprecated classes to allow methods to use it
    @Deprecated("Use Discovering")
    data object Scanning : NetworkState()
    @Deprecated("Use Discovering")
    data class Joining(val sessionId: String) : NetworkState()
    @Deprecated("Use Advertising")
    data class Joined(val sessionId: String) : NetworkState()
    @Deprecated("Use Advertising")
    data class Hosting(val sessionId: String) : NetworkState()
}

/**
 * Raw network events. These use only primitive types (String) — no Peer,
 * no AdvertisedName. Client is responsible for decoding encodedName strings
 * into AdvertisedName objects before passing anything to the topology.
 */
sealed class NetworkEvent {
    /**
     * A nearby device was found during discovery.
     * [encodedName] is the raw string the remote device passed to startAdvertising().
     */
    data class EndpointDiscovered(
        val endpointId: String,
        val encodedName: String
    ) : NetworkEvent()

    /**
     * A connection to an endpoint was fully established (both sides accepted).
     * [encodedName] is the advertised name of the remote endpoint.
     */
    data class EndpointConnected(
        val endpointId: String,
        val encodedName: String
    ) : NetworkEvent()

    /**
     * A previously connected endpoint disconnected, either gracefully or due to
     * a timeout/error. No further messages can be sent to this endpoint.
     */
    data class EndpointDisconnected(val endpointId: String) : NetworkEvent()

    /**
     * Our outgoing connection request was rejected by the remote endpoint.
     * Client/topology should decide whether to try another advertiser.
     */
    data class ConnectionRejected(val endpointId: String) : NetworkEvent()

    /**
     * A message arrived from a directly connected endpoint.
     */
    data class MessageReceived(val message: Message) : NetworkEvent()

    // Deprecated events
    @Deprecated("Use EndpointConnected")
    data class Joined(val sessionId: String) : NetworkEvent()
    @Deprecated("Use EndpointConnected")
    data class PeerConnected(val peer: DeprecatedPeer) : NetworkEvent()
    @Deprecated("Use EndpointDisconnected")
    data class PeerDisconnected(val endpointId: String) : NetworkEvent()
}

/** Compatibility shim for code that still references the old Peer type. */
@Deprecated("Peer is replaced by TopologyPeer and ConnectedPeer")
data class DeprecatedPeer(val endpointId: String)

/**
 * Defines the raw transport layer.
 * Any implementation of this class MUST NOT handle anything other than sending messages
 */
interface Network {

    val logger: KLogger

    data class Config(
        val defaultTtl: Int,
        val serviceId: String = "edu.uwm.cs595.group11"
    )

    /** Current transport state */
    val state: StateFlow<NetworkState>

    /** True while this node is actively advertising to nearby devices */
    val isAdvertising: StateFlow<Boolean>

    /** True while this node is actively scanning for nearby advertisers */
    val isDiscovering: StateFlow<Boolean>

    /** Stream of raw network events */
    val events: SharedFlow<NetworkEvent>

    /**
     * Resolve a peer's encoded name to the transport-layer hardware endpoint ID.
     */
    fun encodedNameToHardwareId(encodedName: String): String?


    /**
     * Initialise the network with the local endpoint's ID.
     * Called by Client whenever the local identity changes (e.g. role promotion).
     *
     * [localEndpointId] is the encoded advertised name string that represents
     * this node's current identity.
     */
    fun init(localEndpointId: String, config: Config)

    /**
     * Clean up all connections and reset to Idle state.
     */
    fun shutdown()


    /**
     * Start broadcasting this node as discoverable.
     * [encodedName] is the full encoded identity string built by [AdvertisedName.encode].
     * Network passes this string to the underlying transport verbatim — it never parses it.
     */
    fun startAdvertising(encodedName: String)

    /**
     * Stop broadcasting.
     */
    fun stopAdvertising()


    /**
     * Start scanning for nearby advertisers.
     * Discovered endpoints are emitted as [NetworkEvent.EndpointDiscovered] on [events].
     */
    suspend fun startDiscovery()

    /**
     * Stop scanning.
     */
    suspend fun stopDiscovery()


    /**
     * Initiate a connection request to a discovered endpoint.
     *
     * This does NOT block until the connection is established. The result
     * arrives asynchronously via [events]:
     *  - [NetworkEvent.EndpointConnected]  if both sides accepted
     *  - [NetworkEvent.ConnectionRejected] if the remote side rejected
     *
     * On the receiving side, [onConnectionRequest] is called to determine
     * whether to accept or reject.
     */
    suspend fun connect(endpointId: String)

    /**
     * Disconnect from a currently connected endpoint.
     * The remote side will receive [NetworkEvent.EndpointDisconnected].
     */
    fun disconnect(endpointId: String)

    /**
     * Callback set by Client. Called when a remote endpoint requests a connection to us.
     *
     * The network awaits the returned Boolean before accepting or rejecting:
     *  - true  → accept the connection
     *  - false → reject the connection
     *
     * This feature is delegated to the topology
     */
    var onConnectionRequest: suspend (endpointId: String, encodedName: String) -> Boolean


    /**
     * Send a message to a directly connected endpoint.
     * Throws if [endpointId] is not currently connected.
     */
    fun sendMessage(endpointId: String, message: Message)

    /**
     * Register a callback invoked whenever a message is received.
     * Used by Client to pipe messages into its own processing pipeline.
     */
    fun addListener(listener: (Message) -> Unit)

    /**
     * Unregister a previously added listener.
     * Called by Client on leaveNetwork to stop processing messages after teardown.
     */
    fun removeListener(listener: (Message) -> Unit)

    /**
     * Deliver a received message to all registered listeners.
     * Should only be called by the Network implementation itself.
     */
    fun notifyListeners(message: Message)
}