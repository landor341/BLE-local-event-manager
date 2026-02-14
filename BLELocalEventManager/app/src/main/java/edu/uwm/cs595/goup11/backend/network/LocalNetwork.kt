package edu.uwm.cs595.goup11.backend.network

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalNetwork: Network {

    private var NETWORK_IS_SCANNING = false;
    private var listeners = mutableListOf<(Message) -> Unit>()
    private var mockExposedRouter: MockClient = MockClient(ClientType.ROUTER, "ROUTER1");
    private var bridgeClient: MockClient? = null
    private var client: Client? = null
    private var id: String = ""

    class MockClient(val type: ClientType, val id: String, val isClient: Boolean = false) {
        var onReceive: (Message) -> Unit = {_ -> }
        val routerConnections = mutableListOf<MockClient>()
        val leafConnections = mutableListOf<MockClient>()

        val recentMessages = ArrayDeque<Int>()

        private final val RECENT_ARRAY_MAX_LENGTH = 10;

        fun setCallback(callback: (Message) -> Unit) {
            onReceive = callback
        }
        fun receiveMessage(message: Message) {
            // If this is for the actual client, ignore this and send to client
            if(isClient && message.to == id) {
                onReceive(message)
                return;
            }

            if (recentMessages.contains(message.hashCode())) {
                // Message already received, ignore
                return;
            }

            if (message.ttl <= 0) {
                // TTL Reached, delete
                return;
            }

            // Add hash to message
            recentMessages.add(message.hashCode())

            // Fix size if message list is too big
            if(recentMessages.size > RECENT_ARRAY_MAX_LENGTH) {
                recentMessages.removeLastOrNull()
            }

            if(message.to == id) {
                processMessage(message);
            }

            // If we are a router, process this and propagate it
            if(type == ClientType.ROUTER) {
                propagateMessage(message);
            }


        }

        fun processMessage(message: Message) {
            //TODO: Handle encryption/decryption
            when (message.type) {
                MessageType.HELLO -> {
                    //Log.d("CLIENT_MESSAGE_RECEIVED", "Received message from ${message.from}")
                    // Send ACK
                    val m = Message(
                        message.from,
                        id,
                        MessageType.ACK,
                        "ACK".toByteArray(),
                        5
                    )

                    // Transmit that message
                    sendMessageToRouters(m);
                }
                MessageType.JOIN -> {
                    if (type == ClientType.ROUTER) {
                        //Log.d("CLIENT_MESSAGE_RECEIVED", "Node joined: ${message.from}")
                        // Return a hello message
                        val m = Message(
                            message.from,
                            id,
                            MessageType.HELLO,
                            "ACK".toByteArray(),
                            5
                        )

                        for (c in leafConnections) {
                            if (c.id == message.from) {
                                sendMessage(m, c)
                            }
                        }
                    }
                }
                else -> {
                    // INVALID OR NOT MADE
                }
            }
        }

        fun propagateMessage(message: Message) {
            // Check to see if a leaf is the reciever
            for (c in leafConnections) {
                if(c.id == message.to) {
                    sendMessage(message, c)
                    return;
                }
            }

            sendMessageToRouters(message)
        }


        fun sendMessageToRouters(message: Message) {
            // Send message to all routers
            for (c in routerConnections) {
                sendMessage(message, c);
            }

        }

        fun sendMessage(message: Message, client: LocalNetwork.MockClient) {
            client.receiveMessage(message);
        }
    }



    override fun init(
        client: Client,
        config: Network.Config
    ) {
        this.client = client
        id = client.id
        // Create network
        /*
         * Layout:
         *
         * NETWORK 1:
         *   ROUTER1 <---> ROUTER2
         *     / \          / \
         * MOCK1  MOCK2  MOCK3  MOCK4
         *
         */

        val router2 = MockClient(ClientType.ROUTER, "ROUTER2")
        val mock1 = MockClient(ClientType.LEAF, "MOCK1")
        val mock2 = MockClient(ClientType.LEAF, "MOCK2")
        val mock3 = MockClient(ClientType.LEAF, "MOCK3")
        val mock4 = MockClient(ClientType.LEAF, "MOCK4")

        mockExposedRouter.leafConnections.add(mock1)
        mockExposedRouter.leafConnections.add(mock2)
        mockExposedRouter.routerConnections.add(router2)

        mock1.routerConnections.add(mockExposedRouter)
        mock2.routerConnections.add(mockExposedRouter)

        router2.leafConnections.add(mock3)
        router2.leafConnections.add(mock4)
        router2.routerConnections.add(mockExposedRouter);

        mock3.routerConnections.add(router2)
        mock4.routerConnections.add(mock4)

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

    override fun join(sessionId: String) {
        // Ignore sessionId
        // Connect to router1

        bridgeClient = MockClient(ClientType.LEAF, id, true);
        bridgeClient!!.setCallback({ message ->
            onReceive(message)
        })

        // TODO: Fix this maybe?
        // Instead of requesting to join, just send a JOIN message after connecting to router

        mockExposedRouter.leafConnections.add(bridgeClient!!)
        bridgeClient!!.routerConnections.add(mockExposedRouter);

        // Send JOIN message
        bridgeClient!!.sendMessageToRouters(
            Message(
                mockExposedRouter.id,
                bridgeClient!!.id,
                type = MessageType.JOIN,
                data = "Hello".toByteArray(),
                1
            )
        )
    }

    override fun leave() {
        // Send leave message
        if (bridgeClient == null) {
           throw Error("Not in active network")
        }

        bridgeClient!!.sendMessageToRouters(
            Message(
                mockExposedRouter.id,
                bridgeClient!!.id,
                MessageType.DISCONNECT,
                "Leaving".toByteArray(),
                1
            )
        )

        // TODO: Maybe wait for disconnect message?
        bridgeClient = null
    }

    override suspend fun create() {
        // Ignore for now
    }

    override suspend fun deleteNetwork() {
        // Ignore for now
    }

    override fun sendMessage(message: Message) {
        if (bridgeClient == null) {
            throw Error("Not in active network")
        }

        bridgeClient!!.sendMessageToRouters(message);
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
}