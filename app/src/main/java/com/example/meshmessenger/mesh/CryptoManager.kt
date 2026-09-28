package com.example.meshmessenger.mesh

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** P-256 ECDH + AES-256-GCM. Identity keys are stored by the app in v0.3. */
object CryptoManager {
    private const val NONCE_SIZE = 12
    private const val KEY_SIZE = 32

    fun generateIdentity(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun publicKeyBase64(publicKey: PublicKey): String = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    fun publicKeyFromBase64(value: String): PublicKey {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun privateKeyFromBytes(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun keyId(publicKey: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey.encoded).take(8).joinToString("") { "%02x".format(it) }

    fun encrypt(plaintext: ByteArray, senderPrivate: PrivateKey, recipientPublic: PublicKey, aad: ByteArray = ByteArray(0)): ByteArray {
        val shared = ecdh(senderPrivate, recipientPublic)
        val key = MessageDigest.getInstance("SHA-256").digest(shared)
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(KEY_SIZE), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + nonce.size + ciphertext.size).put(nonce.size.toByte()).put(nonce).put(ciphertext).array()
    }

    fun decrypt(blob: ByteArray, recipientPrivate: PrivateKey, senderPublic: PublicKey, aad: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteBuffer.wrap(blob)
        val nonceLen = b.get().toInt() and 0xff
        require(nonceLen == NONCE_SIZE && b.remaining() > nonceLen)
        val nonce = ByteArray(nonceLen) { b.get() }
        val ciphertext = ByteArray(b.remaining()) { b.get() }
        val shared = ecdh(recipientPrivate, senderPublic)
        val key = MessageDigest.getInstance("SHA-256").digest(shared)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(KEY_SIZE), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        return KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(publicKey, true)
        }.generateSecret()
    }
}
