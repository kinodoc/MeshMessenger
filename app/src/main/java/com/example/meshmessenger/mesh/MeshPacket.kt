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
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        require(sourceId.length <= 64 && destinationId.length <= 64)
        require(ttl in 0..16)
        require(senderPublicKey.size <= 512)
        require(payload.size <= 8 * 1024 * 1024)
        val source = sourceId.toByteArray(StandardCharsets.UTF_8)
        val destination = destinationId.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(16 + 1 + 1 + source.size + 1 + destination.size + 2 + senderPublicKey.size + 4 + payload.size)
            .putLong(messageId.mostSignificantBits).putLong(messageId.leastSignificantBits)
            .put(ttl.toByte()).put(source.size.toByte()).put(source)
            .put(destination.size.toByte()).put(destination)
            .putShort(senderPublicKey.size.toShort()).put(senderPublicKey)
            .putInt(payload.size).put(payload).array()
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
            val lengthHi = b.short.toInt() and 0xffff
            val payloadLen = if (b.remaining() == lengthHi) {
                lengthHi
            } else {
                val lengthLo = b.short.toInt() and 0xffff
                (lengthHi shl 16) or lengthLo
            }
            require(payloadLen in 1..8 * 1024 * 1024 && b.remaining() == payloadLen)
            val payload = ByteArray(payloadLen) { b.get() }
            MeshPacket(id, source, destination, ttl, publicKey, payload)
        }.getOrNull()
    }
}
