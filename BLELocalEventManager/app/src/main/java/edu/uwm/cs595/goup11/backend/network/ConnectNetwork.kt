package edu.uwm.cs595.goup11.backend.network

import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import kotlinx.coroutines.flow.Flow


class ConnectNetwork: Network {

    private val mConnectionsClient: ConnectionsClient? = null

    override fun init(
        client: Client,
        config: Network.Config
    ) {
        TODO("Not yet implemented")
    }

    override fun shutdown() {
        TODO("Not yet implemented")
    }

    override suspend fun startScan() {
        TODO("Not yet implemented")
    }

    override suspend fun stopScan() {
        TODO("Not yet implemented")
    }

    override fun observeDiscoveredNetworks(): Flow<String> {
        TODO("Not yet implemented")
    }

    override fun join(
        sessionId: String,
        callback: (success: Boolean, routerId: String) -> Unit
    ) {
        TODO("Not yet implemented")
    }


    override fun leave() {
        TODO("Not yet implemented")
    }

    override suspend fun create() {
        TODO("Not yet implemented")
    }

    override suspend fun deleteNetwork() {
        TODO("Not yet implemented")
    }

    override fun sendMessage(to: String, message: Message) {
        TODO("Not yet implemented")
    }

    override suspend fun sendMessageAndWait(
        to: String,
        message: Message,
        timeoutMillis: Long
    ): Message? {
        TODO("Not yet implemented")
    }

    override fun addListener(listener: (Message) -> Unit) {
        TODO("Not yet implemented")
    }

    override fun notifyListeners(message: Message) {
        TODO("Not yet implemented")
    }

    override fun startAdvertising(serviceId: String) {
        if(mConnectionsClient == null) {
            throw Error("Connections client not created")
        }
        val advOptions: AdvertisingOptions =
            AdvertisingOptions
                .Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()
        mConnectionsClient.startAdvertising(
                "TEST", serviceId, connectionLifecycleCallback, advOptions
            )
            .addOnSuccessListener(
                OnSuccessListener { unused: Void? -> })
            .addOnFailureListener(
                OnFailureListener { e: Exception -> })

    }

    override fun stopAdvertising() {
        TODO("Not yet implemented")
    }
}