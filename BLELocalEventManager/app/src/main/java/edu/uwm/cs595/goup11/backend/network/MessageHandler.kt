package edu.uwm.cs595.goup11.backend.network

/**
 * Represents a class that handles messages on behalf of the Client.
 */
interface MessageHandler {


    /**
     *  Function is called during the [Client.handleMessage] function after both
     *  the topology and [DirectoryManager] handle messages.
     *
     *  @param [message] The message that the client received
     *
     *  @return True if this message should be consumed (i.e. dropped) or False if the client should keep processing it
     */
    fun processMessage(message: Message): Boolean
}