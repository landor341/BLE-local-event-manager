package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.flow.Flow

/**
 * Represents a client on the network. This will handle all of the message handling including
 * pre-processing before sending and processing incoming messages.
 *
 * When passing the [network], DO NOT call the init() function, this class will handle that
 */
class Client(val network: Network) {

    /**
     * Scans for local networks and returns the ID of that network
     */
    suspend fun scanNetworks(): Flow<List<String>> {
        network.startScan()

        return network.observeDiscoveredNetworks()
    }
}