package edu.uwm.cs595.goup11.backend.network.handlers

import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageHandler
import edu.uwm.cs595.goup11.backend.network.MessageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Used to sync a a collection of data types [T] across all nodes on the network. The type of this
 * class does not need to be a [Collection]
 *
 * Multiple instances can be made of this class. Each class instance must contain a unique
 * [identifier] to determine if this class should process this message.
 *
 * The type passed to this class should implement its own [equals] to determine if a update
 * should be made
 */
class CollectionDataSyncHandler<T>
    (val identifier: String) : MessageHandler {

    val data = mutableListOf<T>();

    private var messageTypes: List<MessageType> = listOf(
        MessageType.DATA_SYNC,
        MessageType.DATA_SYNC_ACK,
        MessageType.DATA_UPDATE_ADD,
        MessageType.DATA_UPDATE_MOD,
        MessageType.DATA_UPDATE_REMOVE
    )

    override fun processMessage(message: Message): Boolean {
        if(!messageTypes.contains(message.type)) return false;

        try {
            message.decodePayload<DataSyncObject<T>>()
        } catch(e: Exception) {
            return false;
        }

        return false;
    }


    /**
     * Helper class to store active handlers
     */
    companion object {
        private val collectionHandlers = mutableListOf<CollectionDataSyncHandler<Any>>()

        fun registerCollection(handler: CollectionDataSyncHandler<Any>) {
            collectionHandlers.add(handler);
        }
    }




    @Serializable
    data class DataSyncObject<T>(val id: String, val body: T)

}