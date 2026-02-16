package edu.uwm.cs595.goup11.backend.network

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * TODO: This class currently handles router logic, which it should not. Refactor to move message handling and logic to Client class
 */
class LocalNetwork: Network {
    /*
     * CONFIG
     */
    private final val ROUTER_COUNT = 2
    private final val CLIENT_COUNT = 4



    /*
        Network Lists
     */

    /*
    * To create a mock network, this class will store every client instance in a list, along with every
    * node that it is connected to
    */
    data class ConnectedClient(var client: Client, var connectedTo: MutableList<String>)
    /*
     * Represents all clients
     */
    private var allClients = mutableListOf<Client>();

    private var NETWORK_IS_SCANNING = false;
    private var listeners = mutableListOf<(Message) -> Unit>()


    private var client: Client? = null
    private var id: String = ""



    override fun init(
        client: Client,
        config: Network.Config
    ) {
        this.client = client
        id = client.id
        allClients.add(client)


        var i = 0;
        while(i < ROUTER_COUNT) {
            val routerName = "ROUTER${i}"
            val c = Client(routerName, ClientType.ROUTER)
            val n = LocalNetwork()
            n.attachClient(c)
            c.attachNetwork(n)
            c.joinNetwork("")
            i++
        }

        i = 0
        while(i < CLIENT_COUNT) {
            val clientName = "ROUTER${i}"
            val c = Client(clientName, ClientType.LEAF)
            val n = LocalNetwork()
            n.attachClient(c)
            c.attachNetwork(n)
            c.joinNetwork("")
        }


    }

    fun attachClient(client: Client) {
        this.client = client
        id = client.id

        if(InNetworkClientList.allClients.contains(client)) {
            throw Error("Client is already a part of the network")
        }
    }

    override fun shutdown() {
        TODO("Not yet implemented")
    }

    override suspend fun startScan() {
        NETWORK_IS_SCANNING = true;
        Log.d("NETWORK", "Scanning started")
    }

    override suspend fun stopScan() {
        NETWORK_IS_SCANNING = true;
        Log.d("NETWORK", "Scanning started")
    }

    override fun observeDiscoveredNetworks(): Flow<String> = flow {
        while (NETWORK_IS_SCANNING) {
            emit("MOCK_NETWORK_1")
            delay(1000)
        }
    }

    /**
     * ]
     */
    override fun join(sessionId: String, callback: (success: Boolean, routerId: String) -> Unit) {
        // Ignore sessionId


        // Connect to random router
        val routers = InNetworkClientList.connectedClients.filter { client ->
            client.client.type === ClientType.ROUTER
        }

        val randomIndex = routers.indices.random()

        val clientRouter = routers[randomIndex];


        // Add this to connectedClients
        InNetworkClientList.connectedClients.add(
            ConnectedClient(
                client!!,
                mutableListOf<String>(clientRouter.client.id)
            )
        )
        clientRouter.connectedTo.add(client!!.id)

        //Send callback correct
        callback(true, clientRouter.client.id)
    }

    override fun leave() {

    }

    override suspend fun create() {
        // Ignore for now
    }

    override suspend fun deleteNetwork() {
        // Ignore for now
    }

    override suspend fun sendMessageAndWait(
        to: String,
        message: Message,
        timeoutMillis: Long
    ): Message? {
        TODO("Not yet implemented")
    }

    override fun sendMessage(to:String, message: Message) {
        if(client == null) {
            throw Error("Client not instantiated")
        }

        InNetworkClientList.sendMessage(client!!, to, message);
    }

    fun onReceive(message: Message) {
        // Just pass to client
        client?.processMessage(message);

        // Pass to listeners
        notifyListeners(message)
    }

    override fun addListener(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    override fun notifyListeners(message: Message) {
        for(l in listeners) {
            l(message)
        }
    }

    private class InNetworkClientList {

        companion object {

            /**
             * Represents the "Master Client" which is the technically the user who made the network
             */
            private var masterClient = Client("ADMIN", ClientType.ROUTER)

            var connectedClients = mutableListOf<ConnectedClient>(ConnectedClient(masterClient, mutableListOf<String>()))
            var allClients = mutableListOf<Client>(masterClient)

            fun isConnected(self: String, to: String): Boolean {
                val self = connectedClients.find {c ->
                    c.client.id === self
                }
                if(self == null) return false

                return self.connectedTo.contains(to)
            }

            fun getConnectedClient(id: String): ConnectedClient? {
                return connectedClients.find {c ->
                    c.client.id == id
                }
            }

            /**
             * Sends a [message] to the [to] client using the [Client.processMessage] function.
             * This will throw an error if [isConnected] returns false (i.e. the client it is trying
             * to send the message to is not connected to the [from] client)
             */
            fun sendMessage(from: Client, to: String, message: Message) {
                if(!isConnected(from.id, to)) {
                    return;
                }

                // Get Client
                val client = getConnectedClient(to) ?: return

                client.client.processMessage(message);
            }

        }
    }
}

