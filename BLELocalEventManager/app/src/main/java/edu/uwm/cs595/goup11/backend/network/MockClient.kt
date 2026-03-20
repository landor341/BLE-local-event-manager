package edu.uwm.cs595.goup11.backend.network

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
    val id: String,
    val type: ClientType = ClientType.LEAF,
    val role: UserRole = UserRole.ATTENDEE
) {
    private val logger = KotlinLogging.logger {}

    /** The real backend client instance for this mock peer. */
    val client: Client = Client(id, type, role)

    /** The local network instance for this mock peer. */
    private val network: LocalNetwork = LocalNetwork()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    /** 
     * Flow of messages received by this mock client. 
     * It observes messages delivered via the underlying local network.
     */
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    /** Exposes the network events flow from the client's network. */
    val networkEvents: SharedFlow<NetworkEvent> get() = network.events

    init {
        // Initialize the client and attach it to the local network configuration.
        client.attachNetwork(network, Network.Config(defaultTtl = 5))

        // Ensure the client is listening to network events (like PeerConnected)
        client.manuallyListenToEvents()

        // Register a listener on the network to capture messages sent to this mock.
        network.addListener { message ->
            logger.debug { "MockClient($id) received message: type=${message.type} from=${message.from}" }
            _messages.tryEmit(message)
        }
    }

    /**
     * Joins a simulated session (network) by its ID.
     */
    suspend fun joinSession(sessionId: String) {
        logger.info { "MockClient($id) joining session: $sessionId" }
        client.joinNetwork(sessionId)
    }

    /**
     * Sends a text chat message to another peer.
     */
    fun sendChat(text: String, to: String) {
        val message = Message(
            to = to,
            from = id,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl = 5,
            senderRole = role
        )
        logger.info { "MockClient($id) sending chat to $to: $text" }
        client.sendMessage(to, message)
    }

    /**
     * Convenience method to reply to an incoming message.
     */
    fun respond(original: Message, text: String) {
        val response = original.createReply(
            from = id,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl = 5,
            role = role
        )
        client.sendMessage(response.to, response)
    }
}
