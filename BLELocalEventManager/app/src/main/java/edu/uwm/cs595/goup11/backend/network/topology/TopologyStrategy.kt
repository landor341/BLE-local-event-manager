package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Defines the structural layer of the mesh network stack.
 *
 * A topology strategy is responsible for:
 *  - Deciding who to connect to and how many connections to maintain
 *  - Deciding whether to accept incoming connection requests
 *  - Routing outbound messages to the correct next hop(s)
 *  - Running background jobs (keepalive, discovery loops)
 *  - Healing the network when peers disconnect
 *
 * A topology strategy is NOT responsible for:
 *  - Raw message transport (Network's job)
 *  - Application message meaning (Client's job)
 *  - Encoding or decoding AdvertisedName strings (Client does that at the boundary)
 */
interface TopologyStrategy {

    enum class Role {
        ROUTER,
        LEAF,
        PEER;

        companion object // needed for the fromChar() extension function in AdvertisedName.kt
    }

    /**
     * This node's current role in the topology.
     * May change at runtime (e.g. a LEAF promotes to ROUTER).
     * When it changes, Client re-encodes the advertised name and re-advertises.
     */
    val localRole: Role

    /**
     * Short code identifying this topology type.
     * Must match one of the valid codes in AdvertisedName: "snk", "hub", "msh".
     */
    val topologyCode: String

    /**
     * Maximum number of direct peer connections this node will maintain.
     */
    val maxPeerCount: Int

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Called once when this strategy is activated.
     * Start all background coroutines here (keepalive, discovery, etc.).
     */
    fun start(context: TopologyContext)

    /**
     * Called when the node leaves the network or the strategy is replaced.
     * Cancel all background coroutines and clear peer state.
     */
    fun stop()

    // -------------------------------------------------------------------------
    // Connection events
    // -------------------------------------------------------------------------

    /**
     * Called when an incoming connection request arrives from a remote endpoint.
     *
     * Return true to accept, false to reject.
     * The topology uses this to enforce capacity rules, role filters, etc.
     */
    suspend fun shouldAcceptConnection(
        context: TopologyContext,
        endpointId: String,
        advertisedName: AdvertisedName
    ): Boolean

    /**
     * Called after a connection is fully established (both sides accepted).
     * Store the peer and re-evaluate advertising/discovery state.
     */
    fun onPeerConnected(
        context: TopologyContext,
        endpointId: String,
        advertisedName: AdvertisedName
    )

    /**
     * Called when a peer disconnects — either by the network layer or
     * by a missed keepalive timeout detected by the topology itself.
     * Remove the peer and begin healing if needed.
     */
    fun onPeerDisconnected(context: TopologyContext, endpointId: String)

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    /**
     * Called for every inbound message before Client processes it.
     * Return true if consumed (topology message like PING/PONG).
     * Return false to pass the message up to Client's application layer.
     */
    fun onMessage(context: TopologyContext, message: Message): Boolean

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    /**
     * Given an outbound message, return the list of endpoint IDs to send it to.
     * Return an empty list if no route exists.
     */
    fun resolveNextHop(context: TopologyContext, message: Message): List<String>

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    /**
     * Whether this node should currently be advertising itself.
     * Client calls this after connection/disconnection events to decide
     * whether to call startAdvertising() or stopAdvertising() on the network.
     */
    fun shouldAdvertise(context: TopologyContext): Boolean
}

///**
// * Provides the topology strategy with controlled access to Client and Network
// * capabilities, without exposing the full Client or Network objects.
// *
// * This is the only bridge between the topology layer and the layers above/below it.
// * Topology never holds a direct reference to Client or Network.
// */
//class TopologyContext(
//    /**
//     * Returns this node's current endpoint ID.
//     * Lambda because the endpoint ID can change (e.g. on role promotion).
//     */
//    private val localEndpointId: () -> String,
//
//    /**
//     * Returns this node's current fully encoded advertised name string.
//     * Lambda because it changes whenever role or identity changes.
//     * Topologies call context.encodedName() when they need to pass a name
//     * to startAdvertising() — they never build the encoded string themselves.
//     */
//    private val localEncodedName: () -> String,
//
//    private val network: Network,
//
//    /**
//     * Called when the topology wants to start or stop advertising.
//     * [encodedName] is only non-null when starting.
//     */
//    private val onAdvertisingChanged: (advertising: Boolean, encodedName: String?) -> Unit,
//
//    /** Called when the topology wants to start or stop discovery */
//    private val onScanChanged: (scanning: Boolean) -> Unit,
//
//    /** Called when the topology promotes or demotes this node's role */
//    private val onRoleChanged: (TopologyStrategy.Role) -> Unit,
//
//    val coroutineScope: CoroutineScope
//) {
//    /** This node's current endpoint ID */
//    val endpointId: String get() = localEndpointId()
//
//    /**
//     * Returns this node's current fully encoded advertised name string.
//     * Topology implementations should pass this to startAdvertising().
//     */
//    fun encodedName(): String = localEncodedName()
//
//    fun sendMessage(to: String, message: Message) =
//        network.sendMessage(to, message)
//
//    fun startAdvertising(encodedName: String) =
//        onAdvertisingChanged(true, encodedName)
//
//    fun stopAdvertising() =
//        onAdvertisingChanged(false, null)
//
//    fun startScan() = onScanChanged(true)
//    fun stopScan()  = onScanChanged(false)
//
//    fun notifyRoleChanged(role: TopologyStrategy.Role) = onRoleChanged(role)
//
//    fun launchJob(block: suspend CoroutineScope.() -> Unit): Job =
//        coroutineScope.launch(block = block)
//}
