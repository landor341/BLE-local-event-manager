package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.MeshTopology
import edu.uwm.cs595.goup11.backend.security.Manager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LocalNetworkSecurityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private fun makeScope(): CoroutineScope {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes.add(s)
        return s
    }

    @After
    fun cleanup() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        LocalNetwork.purge()
        Manager.reset()
    }

    @Test
    fun hostAndJoiner_exchangeKey_andCommunicateSecurely() = runBlocking {
        // 1. Setup Host
        val hostScope = makeScope()
        val hostClient = Client("HOST", UserRole.ADMIN, scope = hostScope)
        val hostNet = LocalNetwork(scope = hostScope)
        hostClient.attachNetwork(hostNet, Network.Config(5))

        // Host initializes Manager during createNetwork
        hostClient.createNetwork(
            "SECURE_NET", MeshTopology(
                keepaliveIntervalMs = 500,
                keepaliveTimeoutMs = 2000,
                discoveryIntervalMs = 500
            )
        )

        val hostId = hostClient.endpointId!!
        val hostKey = Manager.getKey()

        // 2. Setup Joiner
        val joinerScope = makeScope()
        val joinerClient = Client("JOINER", UserRole.ATTENDEE, scope = joinerScope)
        val joinerNet = LocalNetwork(scope = joinerScope)
        joinerClient.attachNetwork(joinerNet, Network.Config(5))

        // Monitor Joiner's network for KEY_EXCHANGE
        // Since Manager is a singleton, we verify the message arrives rather than checking Manager state
        val keyExchangeReceived = CompletableDeferred<Message>()
        joinerNet.addListener { msg ->
            if (msg.type == MessageType.KEY_EXCHANGE) {
                keyExchangeReceived.complete(msg)
            }
        }

        // 3. Joiner joins in a separate coroutine so it doesn't block this one
        joinerScope.launch {
            joinerClient.joinNetwork("SECURE_NET")
        }

        // 4. Wait for connection to be established at the network layer
        var connected = false
        val deadline = System.currentTimeMillis() + 10_000
        var joinerId: String? = null
        while (!connected && System.currentTimeMillis() < deadline) {
            joinerId = joinerClient.endpointId
            if (joinerId != null && joinerId.contains("SECURE_NET")) {
                val hostNode = LocalNetwork.InMemoryNetworkHolder.getNode(hostId)
                if (hostNode != null && hostNode.connections.contains(joinerId)) {
                    connected = true
                }
            }
            if (!connected) delay(100)
        }

        assertTrue("Connection should be established within timeout", connected)
        assertNotNull("Joiner should have an endpointId", joinerId)

        // 5. Verify KEY_EXCHANGE message arrived at Joiner
        val keyMsg = withTimeoutOrNull(5000) { keyExchangeReceived.await() }
        assertNotNull("Joiner should receive KEY_EXCHANGE message", keyMsg)
        assertArrayEquals("Key received should match Host key", hostKey, keyMsg!!.data)

        // 6. Communication Test (Encryption/Decryption)
        // Note: Because Manager is a singleton, it's already initialized with hostKey.
        // We verify that a message sent from Joiner (which will be encrypted using hostKey)
        // is successfully received and decrypted by Host.

        val receivedOnHost = CompletableDeferred<Message>()
        hostClient.addMessageListener { msg ->
            if (msg.type == MessageType.TEXT_MESSAGE) {
                receivedOnHost.complete(msg)
            }
        }

        val chatText = "Hello Secure World!"
        joinerClient.sendMessage(
            Message(
                to = hostId,
                from = joinerId!!,
                type = MessageType.TEXT_MESSAGE,
                data = chatText.toByteArray(StandardCharsets.UTF_8),
                ttl = 5
            )
        )

        val decryptedMsg = withTimeoutOrNull(5000) { receivedOnHost.await() }
        assertNotNull("Host should receive the message", decryptedMsg)
        assertEquals(
            "Decrypted text should match original",
            chatText,
            decryptedMsg!!.data?.toString(StandardCharsets.UTF_8)
        )
    }
}
