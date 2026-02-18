package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.flow.Flow

/**
 * Represents a client on the network. This class provides a high-level API
 * for managing network connections and message exchange.
 */
class Client(private val network: Network) {

    private val onMessageReceivedCallbacks = mutableListOf<(Message) -> Unit>()

    init {
        // Initialize the network with this client and a default configuration
        network.init(this, Network.Config(maxTTL = 3))
        
        // Forward messages from the network layer to registered callbacks
        network.addListener { message ->
            onMessageReceivedCallbacks.forEach { it(message) }
        }
    }

    /**
     * Starts scanning and returns a flow of discovered network IDs.
     */
    suspend fun getAvailableNetworks(): Flow<List<String>> {
        network.startScan()
        return network.observeDiscoveredNetworks()
    }

    /**
     * Stops the active network scan.
     */
    suspend fun stopScanning() {
        network.stopScan()
    }

    /**
     * Connects to a specific network using its session ID.
     */
    fun connect(networkId: String) {
        network.join(networkId)
    }

    /**
     * Disconnects from the current network.
     */
    fun disconnect() {
        network.leave()
    }

    /**
     * Sends a message on the current network.
     */
    fun sendMessage() {
        network.sendMessage()
    }

    /**
     * Registers a callback to be invoked whenever a message is received.
     */
    fun registerMessageCallback(callback: (Message) -> Unit) {
        onMessageReceivedCallbacks.add(callback)
    }

    /**
     * Shuts down the client and cleans up network resources.
     */
    fun shutdown() {
        network.shutdown()
    }
}
