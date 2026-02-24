package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TopologyContext(
    val localId: String,
    private val network: Network,
    private val onAdvertisingChanged: (Boolean) -> Unit,
    private val onRoleChanged: (TopologyStrategy.Role) -> Unit,
    private val coroutineScope: CoroutineScope
) {
    fun sendMessage(to: String, message: Message) = network.sendMessage(to, message)

    fun startAdvertising() = onAdvertisingChanged(true)
    fun stopAdvertising() = onAdvertisingChanged(false)

    fun notifyRoleChanged(role: TopologyStrategy.Role) = onRoleChanged(role)

    fun launchJob(block: suspend CoroutineScope.() -> Unit): Job =
        coroutineScope.launch(block = block)
}