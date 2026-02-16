package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Represents a client on the network. This will handle all of the message handling including
 * pre-processing before sending and processing incoming messages.
 *
 * When passing the [network], DO NOT call the init() function, this class will handle that
 */
class Client(val id: String, val type: ClientType, private val scope: CoroutineScope) {
    //TODO: This class currently does NOT handle any message logic, refactor this and local class
    var listeners = mutableListOf<(Message) -> Unit>()
    private var network: Network? = null;
    private var attachedRouter: String? = null;


    /**
     * Router Data. This will only be used if client is a router
     */
    private var routers = mutableListOf<String>()
    private var clients = mutableListOf<String>()

    fun attachNetwork(network: Network) {
        this.network = network;
    }

    /**
     * Scans for local networks and returns the ID of that network
     */
    suspend fun scanNetworks(): Flow<String> {
        if(network == null) {
            throw Error("Network is not attached to client")
        }
        network!!.startScan()

        return network!!.observeDiscoveredNetworks()
    }

    /**
     * Processes the message. This happens before notifying any listeners of a message being
     * received.
     *
     * All message propagation is handled here
     */
    fun processMessage(message: Message) {
        notifyListeners(message)
    }

    fun sendMessage(message: Message) {
        if(network == null || attachedRouter == null) {
            throw Error("Network is not attached to client")
        }
        network!!.sendMessage(attachedRouter!!, message);
    }

    suspend fun joinNetwork(networkId: String) {
        if(network == null) {
            throw Error("Network is not attached to client")
        }
        network!!.join(networkId, {success, endpointId ->
            if (!success) return@join


           scope.launch {
                onConnect(endpointId)
           }
        })
    }

    suspend private fun onConnect(endpointId: String) {
        // Send HELLO message to router
        val status = network!!.sendMessageAndWait(
            attachedRouter!!,

            Message(
            endpointId,
            id,
            MessageType.HELLO,
            "".toByteArray(),
            1
        ))

        // Receive ROUTER status

        // Send ATTACH

        // Receive ATTACH_OK

        // Return status
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