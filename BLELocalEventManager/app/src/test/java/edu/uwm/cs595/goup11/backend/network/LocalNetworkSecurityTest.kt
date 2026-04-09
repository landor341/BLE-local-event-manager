package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.security.Manager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkSecurityTest {

//    @After
//    fun cleanup() {
//        LocalNetwork.purge()
//        Manager.reset()
//    }
//
//    @Test
//    fun hostAndJoiner_exchangeKey_andCommunicateSecurely() = runTest {
//        // 1. Setup Host
//        val hostClient = Client("HOST", ClientType.ROUTER)
//        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
//        hostNet.create("SECURE_NET")
//
//        // 2. Setup Joiner
//        val joinerClient = Client("JOINER", ClientType.LEAF)
//        val joinerNet = LocalNetwork().apply { init(joinerClient, Network.Config(5)) }
//
//        // 3. Joiner joins - this should trigger the KEY_EXCHANGE in LocalNetwork.onPeerConnect
//        joinerNet.join("SECURE_NET")
//
//        // 4. Verify Joiner received the key (Manager should be initialized via the KEY_EXCHANGE handler)
//        // Note: In runTest, we might need a small delay or to wait for the message event
//        // But LocalNetwork emulator is largely synchronous for deliveries.
//
//        assertTrue("Manager should be initialized on Joiner", Manager.isInitialized())
//
//        // 5. Send a chat message from Joiner to Host
//        val receivedOnHost = CompletableDeferred<Message>()
//        hostNet.addListener { msg ->
//            if (msg.type == MessageType.TEXT_MESSAGE) {
//                receivedOnHost.complete(msg)
//            }
//        }
//
//        val chatText = "Hello Secure World!"
//        val chatMsg = Message(
//            to = "HOST",
//            from = "JOINER",
//            type = MessageType.TEXT_MESSAGE,
//            data = chatText.toByteArray(StandardCharsets.UTF_8),
//            ttl = 5
//        )
//
//        joinerNet.sendMessage("HOST", chatMsg)
//
//        // 6. Verify Host received and decrypted the message
//        val decryptedMsg = receivedOnHost.await()
//        assertEquals("HOST", decryptedMsg.to)
//        assertEquals("JOINER", decryptedMsg.from)
//        assertEquals(chatText, decryptedMsg.data?.toString(StandardCharsets.UTF_8))
//
//        // 7. (Optional) Verify the message was actually encrypted during transit
//        // We can do this by inspecting the message inside the sendMessage logic
//        // but since we've already tested the round-trip, we know the crypto logic was invoked.
//    }
}
