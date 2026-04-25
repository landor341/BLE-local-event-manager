package edu.uwm.cs595.goup11.backend.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.uwm.cs595.goup11.backend.network.topology.SnakeTopology
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockClientIntegrationTest {

    private val logger = KotlinLogging.logger {}

    @Before
    fun setup() {
        MockClient.purgeNetwork()
    }

    @After
    fun teardown() {
        MockClient.purgeNetwork()
    }

    @Test
    fun testMockClientCommunication() = runBlocking {
        logger.info { "Starting testMockClientCommunication" }

        // 1. Create two mock clients
        val alice = MockClient("Alice", UserRole.ADMIN)
        val bob = MockClient("Bob", UserRole.ATTENDEE)

        try {
            // 2. Alice creates a session
            logger.info { "Alice creating session 'TestEvent'" }
            alice.createSession("TestEvent", SnakeTopology())

            // Start collecting connection events BEFORE Bob joins to avoid race conditions
            val aliceConnectedDeferred = async {
                logger.info { "Alice waiting for connection..." }
                alice.networkEvents.filterIsInstance<NetworkEvent.EndpointConnected>().first()
            }
            val bobConnectedDeferred = async {
                logger.info { "Bob waiting for connection..." }
                bob.networkEvents.filterIsInstance<NetworkEvent.EndpointConnected>().first()
            }

            // 3. Bob joins the session
            logger.info { "Bob joining session 'TestEvent'" }
            bob.joinSession("TestEvent")

            // 4. Wait for connection to be fully established on both sides.
            logger.info { "Waiting for Alice and Bob to connect (timeout 30s)..." }
            withTimeout(30000) {
                val aEvent = aliceConnectedDeferred.await()
                val bEvent = bobConnectedDeferred.await()
                logger.info { "Alice connected to: ${aEvent.endpointId}" }
                logger.info { "Bob connected to: ${bEvent.endpointId}" }
            }
            logger.info { "Alice and Bob connected successfully" }

            // Give the topology a moment to process the connection event and update internal peer lists
            delay(2000)

            // 5. Alice sends a message to Bob.
            // We use Bob's endpointId (encoded name) which is Alice's hardware ID for him in LocalNetwork.
            val bobId = bob.endpointId
            assertNotNull("Bob should have an endpointId after joining", bobId)

            val messageText = "Hello Bob!"
            logger.info { "Alice sending message to $bobId" }
            alice.sendChat(messageText, bobId!!)

            // 6. Bob receives the message
            logger.info { "Waiting for Bob to receive TEXT_MESSAGE..." }
            val receivedMessage = withTimeout(10000) {
                bob.messages.filter { it.type == MessageType.TEXT_MESSAGE }.first()
            }

            logger.info { "Bob received message: ${String(receivedMessage.data!!)}" }
            assertEquals(messageText, String(receivedMessage.data!!, Charsets.UTF_8))
            assertEquals(UserRole.ADMIN, receivedMessage.senderRole)
            assertEquals(alice.endpointId, receivedMessage.from)

        } catch (e: TimeoutCancellationException) {
            logger.error { "Test timed out! Current Network State:" }
            LocalNetwork.displayNetworkGraph()
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Test failed with exception" }
            throw e
        } finally {
            alice.leaveSession()
            bob.leaveSession()
            alice.shutdown()
            bob.shutdown()
        }
    }
}
