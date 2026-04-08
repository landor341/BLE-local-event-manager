package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.charset.StandardCharsets

/**
 * MockClient is a testing utility that encapsulates a [Client] and its own [LocalNetwork].
 * It allows simulation of mesh peers for UI testing and verification of message routing
 * without needing physical devices.
 *
 * This client is designed to work within the local emulated network.
 */
class MockClient(
    val displayName: String,
    val role: UserRole = UserRole.ATTENDEE,
    val presentationId: String? = null
) {
    private val logger = KotlinLogging.logger {}

    /** The real backend client instance for this mock peer. */
    val client: Client = Client(
        displayName = displayName,
        role = role,
        presentationId = presentationId
    )

    /** The local network instance for this mock peer. */
    val network: LocalNetwork = LocalNetwork()

    private val _messages = MutableSharedFlow<Message>(replay = 1, extraBufferCapacity = 64)
    /** 
     * Flow of messages received by this mock client at the application layer.
     * Uses replay = 1 to ensure that tests collecting this flow don't miss messages 
     * delivered before the collection starts.
     */
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    /** Exposes the network events flow from the client's network. */
    val networkEvents: SharedFlow<NetworkEvent> get() = network.events

    /** The endpoint ID of this client, available once it has joined or created a network. */
    val endpointId: String? get() = client.endpointId

    init {
        // Initialize the client and attach it to the local network configuration.
        client.attachNetwork(network, Network.Config(defaultTtl = 5))

        // Register a listener on the client to capture messages delivered to the application layer.
        client.addMessageListener { message ->
            logger.debug { "MockClient($displayName) application received message: type=${message.type} from=${message.from}" }
            _messages.tryEmit(message)
        }
    }

    /**
     * Creates a new simulated session (network).
     */
    suspend fun createSession(eventName: String, topo: TopologyStrategy = SnakeTopology()) {
        logger.info { "MockClient($displayName) creating session: $eventName" }
        client.createNetwork(eventName, topo)
    }

    /**
     * Joins a simulated session (network) by its event name.
     */
    suspend fun joinSession(eventName: String) {
        logger.info { "MockClient($displayName) joining session: $eventName" }
        client.joinNetwork(eventName)
    }

    /**
     * Sends a text chat message to another peer's endpoint ID.
     */
    fun sendChat(text: String, to: String) {
        val message = Message(
            to = to,
            from = client.endpointId ?: displayName,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl = 5,
            senderRole = role,
            presentationId = presentationId
        )
        logger.info { "MockClient($displayName) sending chat to $to: $text" }
        client.sendMessage(message)
    }

    /**
     * Convenience method to reply to an incoming message.
     */
    fun respond(original: Message, text: String) {
        val response = original.createReply(
            from = client.endpointId ?: displayName,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl = 5,
            role = role,
            presentationId = presentationId
        )
        client.sendMessage(response)
    }

    /**
     * Gracefully leaves the network and shuts down transport.
     */
    suspend fun leaveSession() {
        client.leaveNetwork()
    }

    /**
     * Forcefully shuts down the network instance.
     */
    fun shutdown() {
        network.shutdown()
    }

    /**
     * Returns whether the client is currently connected to a network.
     */
    fun isConnected(): Boolean = client.isConnected()

    companion object {
        /**
         * Clears all simulated nodes from the global in-memory network.
         * Call this in @Before or @After of your tests.
         */
        fun purgeNetwork() {
            LocalNetwork.purge()
        }
    }
}
