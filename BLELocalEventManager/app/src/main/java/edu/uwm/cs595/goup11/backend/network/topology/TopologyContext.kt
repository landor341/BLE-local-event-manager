package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.Network
import edu.uwm.cs595.goup11.backend.network.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch


class TopologyContext(
    /**
     * Returns this node's current endpoint ID.
     * Lambda so it always reflects the latest value even after role changes.
     */
    private val localEndpointId: () -> String,

    /**
     * Returns this node's current fully encoded advertised name string.
     * Lambda so it always reflects the latest value after role/identity changes.
     * Topologies pass this to startAdvertising() — they never build it themselves.
     */
    private val localEncodedName: () -> String,

    private val network: Network,

    /**
     * [advertising] = true  → start advertising with [encodedName]
     * [advertising] = false → stop advertising ([encodedName] will be null)
     */
    private val onAdvertisingChanged: (advertising: Boolean, encodedName: String?) -> Unit,

    private val onScanChanged: (Boolean) -> Unit,

    private val onRoleChanged: (TopologyStrategy.Role) -> Unit,

    private val coroutineScope: CoroutineScope,

    private val onConnect: suspend (endpointId: String) -> Unit,

    private val disconnectFromEndpoint: suspend (endpointId: String) -> Unit,

    private val networkEvents: SharedFlow<NetworkEvent>
) {
    /** This node's current endpoint ID */
    val endpointId: String get() = localEndpointId()

    /**
     * This node's current fully encoded advertised name string.
     * Pass this to startAdvertising() so topology doesn't build the name itself.
     */
    fun encodedName(): String = localEncodedName()

    /** Send to a raw hardware endpoint ID — for internal/topology use. */
    fun sendMessage(to: String, message: Message) = network.sendMessage(to, message)

    /** Send to a peer using its hardware ID — preferred overload for topology code. */
    fun sendMessage(peer: TopologyPeer, message: Message) = network.sendMessage(peer.hardwareId, message)

    /**
     * Translate a peer's encoded name (logical address) to the transport-layer
     * hardware endpoint ID needed by [sendMessage]. Returns null if unknown.
     */
    fun encodedNameToHardwareId(encodedName: String): String? =
        network.encodedNameToHardwareId(encodedName)

    fun startAdvertising(encodedName: String) = onAdvertisingChanged(true, encodedName)
    fun stopAdvertising()                     = onAdvertisingChanged(false, null)

    fun startScan() = onScanChanged(true)
    fun stopScan()  = onScanChanged(false)

    fun notifyRoleChanged(role: TopologyStrategy.Role) = onRoleChanged(role)

    fun launchJob(block: suspend CoroutineScope.() -> Unit): Job =
        coroutineScope.launch(block = block)

    suspend fun connect(endpointId: String) = onConnect(endpointId)
    suspend fun disconnect(endpointId: String) = disconnectFromEndpoint(endpointId)
    val events: SharedFlow<NetworkEvent> get() = networkEvents


}