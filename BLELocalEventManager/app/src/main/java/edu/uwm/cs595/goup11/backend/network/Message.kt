package edu.uwm.cs595.goup11.backend.network

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

data class Message(
    val to: String,
    val from: String,
    val type: MessageType,
    val data: ByteArray? = null,
    var ttl: Int,
    val replyTo: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val senderRole: UserRole = UserRole.ATTENDEE,
    val presentationId: String? = null,
    val signature: ByteArray? = null,
    val senderPublicKey: ByteArray? = null
) {

    /**
     * Serialize message into bytes for Nearby Payload
     */
    fun toBytes(): ByteArray {
        val toBytes = to.toByteArray(StandardCharsets.UTF_8)
        val fromBytes = from.toByteArray(StandardCharsets.UTF_8)
        val typeBytes = type.name.toByteArray(StandardCharsets.UTF_8)
        val idBytes = id.toByteArray(StandardCharsets.UTF_8)
        val replyBytes = replyTo?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val roleBytes = senderRole.name.toByteArray(StandardCharsets.UTF_8)
        val presentationBytes = presentationId?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val payload = data ?: ByteArray(0)
        val sigBytes = signature ?: ByteArray(0)
        val pubKeyBytes = senderPublicKey ?: ByteArray(0)

        val buffer = ByteBuffer.allocate(
            4 + toBytes.size +
                    4 + fromBytes.size +
                    4 + typeBytes.size +
                    4 + idBytes.size +
                    4 + replyBytes.size +
                    4 + roleBytes.size +
                    4 + presentationBytes.size +
                    4 + payload.size +
                    4 + sigBytes.size +
                    4 + pubKeyBytes.size +
                    4 // ttl
        )

        putWithLength(buffer, toBytes)
        putWithLength(buffer, fromBytes)
        putWithLength(buffer, typeBytes)
        putWithLength(buffer, idBytes)
        putWithLength(buffer, replyBytes)
        putWithLength(buffer, roleBytes)
        putWithLength(buffer, presentationBytes)
        putWithLength(buffer, payload)
        putWithLength(buffer, sigBytes)
        putWithLength(buffer, pubKeyBytes)

        buffer.putInt(ttl)

        return buffer.array()
    }

    companion object {

        /**
         * Deserialize from Nearby payload
         */
        fun fromBytes(bytes: ByteArray): Message {
            val buffer = ByteBuffer.wrap(bytes)

            val to = readString(buffer)
            val from = readString(buffer)
            val type = MessageType.valueOf(readString(buffer))
            val id = readString(buffer)
            val replyToRaw = readString(buffer)
            val roleName = readString(buffer)
            val presentationIdRaw = readString(buffer)
            val data = readBytes(buffer)
            val signature = readBytes(buffer)
            val senderPublicKey = readBytes(buffer)
            val ttl = buffer.int

            return Message(
                to = to,
                from = from,
                type = type,
                data = if (data.isEmpty()) null else data,
                ttl = ttl,
                replyTo = replyToRaw.ifEmpty { null },
                id = id,
                senderRole = try { UserRole.valueOf(roleName) } catch (e: Exception) { UserRole.ATTENDEE },
                presentationId = presentationIdRaw.ifEmpty { null },
                signature = if (signature.isEmpty()) null else signature,
                senderPublicKey = if (senderPublicKey.isEmpty()) null else senderPublicKey
            )
        }

        private fun putWithLength(buffer: ByteBuffer, bytes: ByteArray) {
            buffer.putInt(bytes.size)
            buffer.put(bytes)
        }

        private fun readString(buffer: ByteBuffer): String {
            val length = buffer.int
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun readBytes(buffer: ByteBuffer): ByteArray {
            val length = buffer.int
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return bytes
        }
    }

    // ------------------- Helpers -------------------

    /**
     * Creates a reply message preserving correlation
     */
    fun createReply(
        from: String,
        type: MessageType,
        data: ByteArray?,
        ttl: Int,
        role: UserRole = UserRole.ATTENDEE,
        presentationId: String? = null
    ): Message {
        return Message(
            to = this.from,
            from = from,
            type = type,
            data = data,
            ttl = ttl,
            replyTo = this.id,
            senderRole = role,
            presentationId = presentationId
        )
    }

    /**
     * Decrement TTL safely (for mesh forwarding)
     */
    fun decrementTtl(): Boolean {
        ttl -= 1
        return ttl > 0
    }
}
