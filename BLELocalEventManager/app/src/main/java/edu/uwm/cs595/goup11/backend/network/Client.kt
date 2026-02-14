package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.flow.Flow

/**
 * Represents a client on the network. This will handle all of the message handling including
 * pre-processing before sending and processing incoming messages.
 *
 * When passing the [network], DO NOT call the init() function, this class will handle that
 */
class Client(val network: Network, val id: String) {

    var listeners = mutableListOf<(Message) -> Unit>()
    /**
     * Scans for local networks and returns the ID of that network
     */
    suspend fun scanNetworks(): Flow<String> {
        network.startScan()

        return network.observeDiscoveredNetworks()
    }

    fun processMessage(message: Message) {
        notifyListeners(message)
    }

    fun sendMessage(message: Message) {
        network.sendMessage(message);
    }

    fun joinNetwork(networkId: String) {
        network.join(networkId)
    }

    fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    fun notifyListeners(message: Message) {
        for(listener in listeners) {
            listener(message)
        }
    }
}