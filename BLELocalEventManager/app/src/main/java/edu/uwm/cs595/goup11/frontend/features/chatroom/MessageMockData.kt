package edu.uwm.cs595.goup11.frontend.features.chatroom

import edu.uwm.cs595.goup11.frontend.domain.models.Message
import java.util.UUID

object MessageMockData {
    fun messages(): List<Message> {
        return listOf(
            Message(
                id = UUID.randomUUID().toString(),
                name = "Amy",
                text = "Hi! Is the presentation starting soon?",
                isFromMe = false
            ),
            Message(
                id = UUID.randomUUID().toString(),
                name = "Bob",
                text = "Yes, it should start in about 5 minutes.",
                isFromMe = true
            ),
            Message(
                id = UUID.randomUUID().toString(),
                name = "Amy",
                text = "Great, thanks!",
                isFromMe = false
            )
        )
    }
}