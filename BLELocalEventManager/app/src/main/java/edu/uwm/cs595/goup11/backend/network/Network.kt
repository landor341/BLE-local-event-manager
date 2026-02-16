package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.flow.Flow

/**
 * Basic interface defining a network.
 *
 * Classes that derive from this class SHOULD NOT handle anything else besides basic message
 * sending and receiving. All message processing should be done by the client
 */
interface Network {

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
    fun observeDiscoveredNetworks(): Flow<String>

    /**
     * Joins a network with the given id, after connection it will call this callback
     * TODO: Need to also add user class
     */
    fun join(sessionId: String, callback: (success: Boolean, routerId: String) -> Unit)

    /**
     * Leaves the active network. Will throw error if user is not on network
     * If the user owns the network, this will send a leave message to all users
     */
    fun leave()

    /**
     * Creates a network with the given configuration
     * TODO: Create configuration
     */
    suspend fun create()

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



    data class Config(var maxTTL: Int)
}