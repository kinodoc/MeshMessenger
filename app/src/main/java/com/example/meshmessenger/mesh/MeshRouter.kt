package com.example.meshmessenger.mesh

import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID

class MeshRouter(
    private val localId: String,
    private val identityPrivateKey: PrivateKey
) {
    private val seen = SeenMessageStore()

    companion object {
        const val DELIVERY_ACK_PREFIX = "__MESH_DELIVERY_ACK__:"
    }

    fun createEncryptedMessage(destinationId: String, recipientPublicKey: PublicKey, text: String, senderName: String = "", ttl: Int = 8): MeshPacket {
        val id = UUID.randomUUID()
        val senderPublic = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.X509EncodedKeySpec(identityPublicBytes)
        )
        val draft = MeshPacket(id, localId, destinationId, ttl, senderPublic.encoded, ByteArray(1), senderName)
        val encrypted = CryptoManager.encrypt(text.toByteArray(Charsets.UTF_8), identityPrivateKey, recipientPublicKey, draft.aad())
        return draft.copy(payload = encrypted).also { seen.acceptFirstTime(it.messageId) }
    }

    fun createDeliveryAck(received: MeshPacket, senderName: String = ""): MeshPacket {
        val senderPublic = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(received.senderPublicKey))
        return createEncryptedMessage(
            received.sourceId,
            senderPublic,
            DELIVERY_ACK_PREFIX + received.messageId,
            senderName
        )
    }

    fun onReceive(packet: MeshPacket): MeshPacket? {
        if (!seen.acceptFirstTime(packet.messageId)) return null
        if (packet.destinationId == localId) return packet
        if (packet.ttl <= 0) return null
        return packet.relay()
    }

    fun decryptForLocal(packet: MeshPacket): String? = runCatching {
        val senderPublic = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(packet.senderPublicKey))
        String(CryptoManager.decrypt(packet.payload, identityPrivateKey, senderPublic, packet.aad()), Charsets.UTF_8)
    }.getOrNull()

    // Set by MainActivity after identity creation; kept here to avoid putting private key material in packets.
    lateinit var identityPublicBytes: ByteArray
}
