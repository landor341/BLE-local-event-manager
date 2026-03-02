package edu.uwm.cs595.goup11.backend.network

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents the state of the network
 */
sealed class NetworkState {
    data object Idle : NetworkState()
    data object Scanning : NetworkState()
    data class Joining(val sessionId: String) : NetworkState()
    data class Joined(val sessionId: String, val router: Peer) : NetworkState()
    data class Hosting(val sessionId: String) : NetworkState()
    data class Error(val reason: String) : NetworkState()
}

/**
 * Represents an event on the network (i.e. Peer joining or leaving)
 */
sealed class NetworkEvent {
    data class Joined(val sessionId: String, val router: Peer) : NetworkEvent()
    data class PeerConnected(val peer: Peer) : NetworkEvent()
    data class PeerDisconnected(val endpointId: String) : NetworkEvent()
    data class MessageReceived(val message: Message) : NetworkEvent()

    data class PeerRequestConnection(val endpointId: String) : NetworkEvent()

    data class PeerRequestRejectedConnection(val endpointId: String) : NetworkEvent()
}

/**
 * Basic interface defining a network.
 *
 * Classes that derive from this class SHOULD NOT handle anything else besides basic message
 * sending and receiving. All message processing should be done by the client
 */
interface Network {

    val logger: KLogger

    data class Config(
        val defaultTtl: Int,
        val directoryServiceId: String = "edu.uwm.cs595.group11"
    )

    /** Reactive state for UI/client setup logic */
    val state: StateFlow<NetworkState>

    /** One-time events */
    val events: SharedFlow<NetworkEvent>

    /** id */
    val currentSessionId: StateFlow<String?>


    /**
     * Initializes the network
     */
    fun init(client: Client, config: Config)

    /**
     * Clears any cache data and local keys.
     * Should be called on shutdown
     * This method will call the [leave] method if user is currently in a network
     */
    fun shutdown()

    /**
     * Scans for active networks
     */
    suspend fun startScan()

    /**
     * Stops the active scan, if there is one
     */
    suspend fun stopScan()

    /**
     * Returns a flow with discovered networks
     * TODO: Should this be a string?
     */
    val discoveredNetworks: Flow<String>

    /**
     * Joins a network with the given id, after connection it will call this callback
     * TODO: Need to also add user class
     */
    suspend fun join(sessionId: String): Peer

    /**
     * Leaves the active network. Will throw error if user is not on network
     * If the user owns the network, this will send a leave message to all users
     */
    fun leave()

    /**
     * Creates a network with the given configuration
     * TODO: Create configuration
     */
    suspend fun create(eventName: String)

    /**
     * Deletes the network. Fails if user is not owner
     */
    suspend fun deleteNetwork()

    /**
     * Sends message on the network
     */
    fun sendMessage(to: String, message: Message)

    /**
     * Sends a message on a network and will wait for a message that has the "replyTo" field
     * with the passed message's id
     */
    suspend fun sendMessageAndWait(to:String, message: Message, timeoutMillis: Long = 10_000): Message?


    /**
     * Adds a listener for messages received.
     */
    fun addListener(listener: (Message) -> Unit)

    /**
     * Should be called whenever a message is received. This should ONLY be called for messages
     * that are intended for this client
     */
    fun notifyListeners(message: Message)

    fun startAdvertising()

    fun stopAdvertising()

    fun onPeerConnect(peer: Peer)

    fun onPeerDisconnect(peer: Peer)


}