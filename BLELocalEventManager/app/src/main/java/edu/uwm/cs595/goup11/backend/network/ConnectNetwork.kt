package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.flow.Flow

class ConnectNetwork: Network {
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

    override fun join(sessionId: String) {
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

    override fun sendMessage(message: Message) {
        TODO("Not yet implemented")
    }

    override fun addListener(listener: (Message) -> Unit) {
        TODO("Not yet implemented")
    }

    override fun notifyListeners(message: Message) {
        TODO("Not yet implemented")
    }
}