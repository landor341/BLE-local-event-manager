package edu.uwm.cs595.goup11

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.uwm.cs595.goup11.backend.network.ClientType
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.MockClient
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.core.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds


/**
 * MeshGatewayMockTest
 *
 * This test demonstrates how to use [MockClient] to simulate a remote peer
 * interacting with the application's [MeshGateway].
 *
 * It uses the [LocalNetwork] emulator, so no physical Bluetooth devices are required.
 */
@RunWith(AndroidJUnit4::class)
class MeshGatewayMockTest {

    @Before
    fun setup() {
        // Clear any previous network state to ensure a clean test environment
        LocalNetwork.purge()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Initialize the app's global mesh gateway
        AppContainer.init(context)
    }

    @Test
    fun testMessageFromMockClientToGateway() = runTest(timeout = 30.seconds) {
        val mesh = AppContainer.meshGateway
        mesh.start()

        // 1. Setup Mock Host (Must be ROUTER)
        val mockHost = MockClient(
            id = "MockHost",
            type = ClientType.ROUTER,
            role = UserRole.PRESENTER
        )
        val sessionId = "MockEvent123"
        mockHost.client.createNetwork(sessionId)

        // 2. Join Event
        mesh.startScanning()
        // Join tends to be slow due to internal HELLO handshakes
        val bundle = withTimeout(10.seconds) {
            mesh.joinEvent(sessionId)
        }
        assertEquals(sessionId, bundle.sessionId)

        // 3. Prepare to receive message (Start collecting BEFORE sending)
        val testMessage = "Hello from the mock peer!"
        val chatFuture = async {
            mesh.chat.first { it.text == testMessage }
        }

        // 4. Send Message
        mockHost.sendChat(testMessage, to = mesh.myId)

        // 5. Verify Receipt
        val receivedChat = withTimeout(5.seconds) {
            chatFuture.await()
        }

        assertEquals(testMessage, receivedChat.text)
        assertEquals("MockHost", receivedChat.sender)
    }

    @Test
    fun testDiscoveryOfMockNetwork() = runTest {
        val mesh = AppContainer.meshGateway
        mesh.start()

        // 1. Create a mock client and start an event.
        // Even for discovery, the network needs a host (Router).
        val mockHost = MockClient(id = "RemoteHost", type = ClientType.ROUTER)
        mockHost.client.createNetwork("VisibleEvent")

        // 2. Start scanning on the app's gateway
        mesh.startScanning()

        // 3. Verify that the event is discovered
        val discovered = mesh.discoveredEvents.first()
        assertEquals("VisibleEvent", discovered.sessionId)
    }
}
