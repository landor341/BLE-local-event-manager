package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.flow.Flow
import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets

/**
 * A simplified implementation of the [Network] interface using Google Nearby Connections.
 * This class handles the low-level P2P communication logic.
 */
class LocalNetwork(private val context: Context) : Network {
    
    // The main entry point for Google Nearby Connections API
    private val connectionsClient = Nearby.getConnectionsClient(context)
    
    // Strategy for M-to-N connections (ideal for a cluster of users at an event)
    private val STRATEGY = Strategy.P2P_CLUSTER
    
    // Unique ID for this application's communication channel
    private val SERVICE_ID = "edu.uwm.cs595.goup11.SERVICE_ID"

    private lateinit var client: Client
    private var config: Network.Config? = null
    
    // Internal state to track discovered devices and active peers
    private val discoveredNetworks = MutableStateFlow<List<String>>(emptyList())
    private val activeConnections = mutableSetOf<String>()
    private val listeners = mutableListOf<(Message) -> Unit>()

    /**
     * Sets up the network with a reference to the processing client and configuration.
     */
    override fun init(client: Client, config: Network.Config) {
        this.client = client
        this.config = config
    }

    /**
     * Cleans up all active connections, stops advertising, and stops discovery.
     */
    override fun shutdown() {
        leave()
        connectionsClient.stopAllEndpoints()
        activeConnections.clear()
    }

    /**
     * Starts looking for other devices that are currently 'advertising' the same SERVICE_ID.
     */
    override suspend fun startScan() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
    }

    /**
     * Discontinues the search for new nearby devices.
     */
    override suspend fun stopScan() {
        connectionsClient.stopDiscovery()
    }

    /**
     * Returns a Flow that emits the list of currently visible endpoint IDs.
     */
    override fun observeDiscoveredNetworks() = discoveredNetworks.asStateFlow()

    /**
     * Attempts to establish a connection with a specific discovered device.
     */
    override fun join(sessionId: String) {
        // "User" is the local name shown to the peer during the connection request
        connectionsClient.requestConnection("User", sessionId, connectionLifecycleCallback)
    }

    /**
     * Gracefully disconnects from all currently connected peers.
     */
    override fun leave() {
        activeConnections.forEach { connectionsClient.disconnectFromEndpoint(it) }
        activeConnections.clear()
    }

    /**
     * Makes this device visible to others looking for the SERVICE_ID.
     */
    override suspend fun create() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising("Host", SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
    }

    /**
     * Stops making this device visible to scanners.
     */
    override suspend fun deleteNetwork() {
        connectionsClient.stopAdvertising()
    }

    /**
     * Broadcasts a simple test message to every connected peer in the network.
     */
    override fun sendMessage() {
        val payload = Payload.fromBytes("Default Message".toByteArray(StandardCharsets.UTF_8))
        activeConnections.forEach { connectionsClient.sendPayload(it, payload) }
    }

    /**
     * Registers a callback to be invoked when a message is received from a peer.
     */
    override fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Forwards a received message to all registered listeners (usually the Client).
     */
    override fun notifyListeners(message: Message) {
        listeners.forEach { it(message) }
    }

    /**
     * Callback for when nearby devices are found or lost during a scan.
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val current = discoveredNetworks.value.toMutableList()
            if (!current.contains(endpointId)) {
                current.add(endpointId)
                discoveredNetworks.value = current
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val current = discoveredNetworks.value.toMutableList()
            current.remove(endpointId)
            discoveredNetworks.value = current
        }
    }

    /**
     * Callback for the connection handshake process (initiating, accepting, disconnecting).
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // For simplicity, we automatically accept all connection requests
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                activeConnections.add(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            activeConnections.remove(endpointId)
        }
    }

    /**
     * Callback for handling the actual data (payloads) sent between devices.
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                val data = String(it, StandardCharsets.UTF_8)
                // Wrap the raw bytes in our Message object and notify listeners
                notifyListeners(Message(data))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can be used to track progress of large file transfers
        }
    }
}
