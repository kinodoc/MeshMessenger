package com.example.meshmessenger.mesh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Binary mesh envelope. Payload is E2E encrypted; relay nodes only need the envelope. */
data class MeshPacket(
    val messageId: UUID,
    val sourceId: String,
    val destinationId: String,
    val ttl: Int,
    val senderPublicKey: ByteArray,
    val payload: ByteArray,
    val senderName: String = ""
) {
    fun encode(): ByteArray {
        require(sourceId.length <= 64 && destinationId.length <= 64)
        require(ttl in 0..16)
        require(senderPublicKey.size <= 512)
        require(payload.size <= 8 * 1024 * 1024)
        require(senderName.toByteArray(StandardCharsets.UTF_8).size <= 64)
        val source = sourceId.toByteArray(StandardCharsets.UTF_8)
        val destination = destinationId.toByteArray(StandardCharsets.UTF_8)
        val name = senderName.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(16 + 1 + 1 + source.size + 1 + destination.size + 2 + senderPublicKey.size + 4 + payload.size + 1 + name.size)
            .putLong(messageId.mostSignificantBits).putLong(messageId.leastSignificantBits)
            .put(ttl.toByte()).put(source.size.toByte()).put(source)
            .put(destination.size.toByte()).put(destination)
            .putShort(senderPublicKey.size.toShort()).put(senderPublicKey)
            .putInt(payload.size).put(payload).put(name.size.toByte()).put(name).array()
    }

    fun relay(): MeshPacket = copy(ttl = ttl - 1)

    /** Bytes authenticated by AES-GCM; TTL is deliberately excluded so relays can decrement it. */
    fun aad(): ByteArray = (messageId.toString() + "|" + sourceId + "|" + destinationId).toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): MeshPacket? = runCatching {
            val b = ByteBuffer.wrap(bytes)
            require(b.remaining() >= 19)
            val id = UUID(b.long, b.long)
            val ttl = b.get().toInt() and 0xff
            val sourceLen = b.get().toInt() and 0xff
            require(sourceLen <= 64 && b.remaining() >= sourceLen + 1)
            val source = String(ByteArray(sourceLen) { b.get() }, StandardCharsets.UTF_8)
            val destinationLen = b.get().toInt() and 0xff
            require(destinationLen <= 64 && b.remaining() >= destinationLen + 2)
            val destination = String(ByteArray(destinationLen) { b.get() }, StandardCharsets.UTF_8)
            val pubLen = b.short.toInt() and 0xffff
            require(pubLen in 1..512 && b.remaining() >= pubLen + 2)
            val publicKey = ByteArray(pubLen) { b.get() }
            val lengthPosition = b.position()
            var payloadLen: Int? = null
            var senderName = ""

            if (b.remaining() >= 4) {
                val candidate = b.int
                if (candidate in 1..8 * 1024 * 1024 && b.remaining() >= candidate) {
                    val trailing = b.remaining() - candidate
                    if (trailing == 0 || (trailing >= 1 && runCatching {
                        val nameLen = b.get(b.position() + candidate).toInt() and 0xff
                        nameLen <= 64 && trailing == 1 + nameLen
                    }.getOrDefault(false))) {
                        payloadLen = candidate
                    }
                }
            }

            if (payloadLen == null) {
                b.position(lengthPosition)
                val oldLength = b.short.toInt() and 0xffff
                require(oldLength in 1..65535 && b.remaining() == oldLength)
                payloadLen = oldLength
            }

            val finalPayloadLen = requireNotNull(payloadLen)
            require(finalPayloadLen in 1..8 * 1024 * 1024 && b.remaining() >= finalPayloadLen)
            val payload = ByteArray(finalPayloadLen) { b.get() }
            if (b.hasRemaining()) {
                val nameLen = b.get().toInt() and 0xff
                require(nameLen <= 64 && b.remaining() == nameLen)
                senderName = String(ByteArray(nameLen) { b.get() }, StandardCharsets.UTF_8)
            }
            MeshPacket(id, source, destination, ttl, publicKey, payload, senderName)
        }.getOrNull()
    }
}
