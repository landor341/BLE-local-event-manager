import edu.uwm.cs595.goup11.backend.network.Client
import edu.uwm.cs595.goup11.backend.network.ClientType
import edu.uwm.cs595.goup11.backend.network.LocalNetwork
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkUnitTest_TransportOnly {

    @After
    fun purgeNetworks() {
        LocalNetwork.purge()
    }


    @Test
    fun createNetwork_thenScanDiscoversIt() = runTest {
        val hostClient = Client("MASTER", ClientType.ROUTER)
        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }

        val scanClient = Client("SCANNER", ClientType.LEAF)
        val scanNet = LocalNetwork().apply { init(scanClient, Network.Config(5)) }

        // Start scanning in background (it loops forever until stopScan)
        val scanJob = launch { scanNet.startScan() }

        // Create network
        hostNet.create("TEST_NETWORK")
        hostNet.startAdvertising()

        // Wait until the flow emits TEST_NETWORK (timeout prevents hanging)
        val discovered = withTimeout(2_000) {
            scanNet.discoveredNetworks.first { it == "TEST_NETWORK" }
        }
        assertEquals("TEST_NETWORK", discovered)

        // Cleanup: stop scan loop and cancel the job
        scanNet.stopScan()
        scanJob.cancel()


    }

    @Test
    fun joinNetwork_connectsClient_toRouter() = runTest {
        val hostClient = Client("MASTER", ClientType.ROUTER)
        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
        hostNet.create("TEST_NETWORK")
        hostNet.startAdvertising()

        val leafClient = Client("LEAF0", ClientType.LEAF)
        val leafNet = LocalNetwork().apply { init(leafClient, Network.Config(5)) }

        val routerPeer = leafNet.join("TEST_NETWORK")   // uses LocalNetwork.join directly
        assertNotNull(routerPeer)
        assertEquals("MASTER", routerPeer.endpointId)
    }

    @Test
    fun sendMessage_deliversToTargetListener() = runTest {
        // Host network (MASTER)
        val hostClient = Client("MASTER", ClientType.ROUTER)
        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
        hostNet.create("TEST_NETWORK")
        hostNet.startAdvertising()

        // Leaf A joins
        val aClient = Client("A", ClientType.LEAF)
        val aNet = LocalNetwork().apply { init(aClient, Network.Config(5)) }
        aNet.join("TEST_NETWORK")

        val received = CompletableDeferred<Message>()
        hostNet.addListener { msg ->
            if (!received.isCompleted) received.complete(msg)
        }

        val out = Message(
            to = "MASTER",
            from = "A",
            type = MessageType.HELLO,
            data = "hi".toByteArray(),
            ttl = 5
        )

        // A -> MASTER
        aNet.sendMessage("MASTER", out)

        val msg = received.await()
        assertEquals("MASTER", msg.to)
        assertEquals("A", msg.from)
        assertEquals(MessageType.HELLO, msg.type)
        assertEquals("hi", msg.data?.toString(Charsets.UTF_8))
    }


    @Test
    fun sendMessageAndWait_completesWhenReplyArrives() = runTest {
        // Host network (MASTER)
        val hostClient = Client("MASTER", ClientType.ROUTER)
        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
        hostNet.create("TEST_NETWORK")
        hostNet.startAdvertising()

        // Leaf A joins
        val aClient = Client("A", ClientType.LEAF)
        val aNet = LocalNetwork().apply { init(aClient, Network.Config(5)) }
        aNet.join("TEST_NETWORK")

        // When MASTER receives PING, reply with replyTo=request.id
        hostNet.addListener { req ->
            if (req.type == MessageType.PING && req.from == "A") {
                val reply = Message(
                    to = req.from,         // back to A
                    from = "MASTER",
                    type = MessageType.PONG,
                    data = null,
                    ttl = 5,
                    replyTo = req.id
                )
                hostNet.sendMessage(req.from, reply)
            }
        }

        val request = Message(
            to = "MASTER",
            from = "A",
            type = MessageType.PING,
            data = null,
            ttl = 5
        )

        val response = aNet.sendMessageAndWait("MASTER", request, timeoutMillis = 2_000)

        assertNotNull("Expected a reply", response)
        assertEquals("MASTER", response!!.from)
        assertEquals("A", response.to)
        assertEquals(MessageType.PONG, response.type)
        assertEquals(request.id, response.replyTo)
    }


    /*
     * Edge Cases:
     * - If network called "A" is created, no other user should be allowed to make a network "A"
     */

    @Test(expected = Error::class)
    fun onMessageCreate_duplicatesShouldThrowError() = runTest{
        val hostClient = Client("MASTER", ClientType.ROUTER)
        val hostNet = LocalNetwork().apply { init(hostClient, Network.Config(5)) }
        hostNet.create("TEST_NETWORK")
        hostNet.startAdvertising()

        // Create 2nd network
        val hostClient2 = Client("MASTER2", ClientType.ROUTER)
        val hostNet2 = LocalNetwork().apply { init(hostClient2, Network.Config(5)) }
        hostNet2.create("TEST_NETWORK")
        hostNet2.startAdvertising()
    }
}
