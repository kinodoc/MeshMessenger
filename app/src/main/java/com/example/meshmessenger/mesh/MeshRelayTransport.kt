package com.example.meshmessenger.mesh

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Internet rendezvous and store-and-forward for end-to-end encrypted MeshPacket envelopes. */
class MeshRelayTransport(
    private val localId: String,
    private val localName: String,
    private val publicKey: ByteArray,
    private val router: MeshRouter,
    private val queue: PendingMessageStore,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String, String, MeshPacket, String) -> Unit,
    private val onDeliveryAck: (String) -> Unit = {},
    private val onPeer: (nodeId: String, name: String, publicKey: ByteArray, ip: String) -> Unit = { _, _, _, _ -> },
    private val onPeerCount: (Int) -> Unit = {}
) {
    companion object {
        private const val HOST = "194.87.186.159"
        private const val PORT = 42425
        private const val CERT_SHA256 = "a5e9474d9d65ff91fbc628e2759cf4796ec9bb28aaa88e4dd13765a6d5d12f2a"
        private const val MAX_LINE = 12 * 1024 * 1024
        private const val TAG = "MeshRelay"
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val peers = ConcurrentHashMap<String, String>()
    private val writeLock = Any()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var lastPingAt = 0L

    private val sslContext: SSLContext by lazy {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "relay certificate missing" }
                val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                require(digest == CERT_SHA256) { "relay certificate pin mismatch" }
            }
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<javax.net.ssl.TrustManager>(trust), java.security.SecureRandom()) }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute { connectionLoop() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        writer = null
        reader = null
        peers.clear()
        onPeerCount(0)
        executor.shutdownNow()
    }

    fun send(packet: MeshPacket, ignoredAddress: String = ""): Boolean {
        if (!running.get()) return false
        return sendJson(JSONObject()
            .put("type", "packet")
            .put("to", packet.destinationId)
            .put("messageId", packet.messageId.toString())
            .put("payload", Base64.encodeToString(packet.encode(), Base64.NO_WRAP)))
    }

    fun retryPending() {
        if (!running.get() || writer == null) return
        val now = System.currentTimeMillis()
        if (now - lastPingAt >= 20_000L) {
            if (sendJson(JSONObject().put("type", "ping"))) lastPingAt = now
        }
        queue.snapshot().take(32).forEach { entry ->
            val packet = MeshPacket.decode(entry.bytes) ?: return@forEach
            send(packet)
        }
    }

    private fun connectionLoop() {
        var delay = 1500L
        while (running.get()) {
            try {
                val s = sslContext.socketFactory.createSocket() as SSLSocket
                s.connect(InetSocketAddress(HOST, PORT), 8000)
                s.soTimeout = 0
                s.startHandshake()
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
                reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                sendJson(JSONObject().put("type", "hello").put("nodeId", localId)
                    .put("name", localName.take(64))
                    .put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP)))
                onStatus("Relay: подключение устанавливается")
                delay = 1500L
                val input = reader ?: throw IllegalStateException("relay reader missing")
                while (running.get()) {
                    val line = input.readLine() ?: break
                    if (line.length > MAX_LINE) throw IllegalStateException("relay frame too large")
                    handleServerMessage(JSONObject(line))
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "connection lost: ${e.javaClass.simpleName}: ${e.message}")
                    onStatus("Relay: ожидание соединения")
                }
            } finally {
                runCatching { socket?.close() }
                socket = null
                writer = null
                reader = null
                peers.clear()
                onPeerCount(0)
            }
            if (running.get()) {
                try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
                delay = (delay * 2).coerceAtMost(30000L)
            }
        }
    }

    private fun handleServerMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello_required" -> Unit
            "welcome" -> {
                val list = msg.optJSONArray("peers")
                if (list != null) for (i in 0 until list.length()) {
                    val p = list.optJSONObject(i) ?: continue
                    rememberPeer(p.optString("nodeId"), p.optString("name"), p.optString("publicKey"))
                }
                onStatus("Relay: подключено")
            }
            "peer_online" -> rememberPeer(msg.optString("nodeId"), msg.optString("name"), msg.optString("publicKey"))
            "peer_offline" -> {
                peers.remove(msg.optString("nodeId"))
                onPeerCount(peers.size)
            }
            "packet" -> {
                val bytes = runCatching { Base64.decode(msg.getString("payload"), Base64.DEFAULT) }.getOrNull() ?: return
                val packet = MeshPacket.decode(bytes) ?: return
                val next = router.onReceive(packet)
                if (packet.destinationId == localId) {
                    val text = router.decryptForLocal(packet) ?: return
                    // Tell the relay it is safe to delete its queued copy only after decryption succeeds.
                    sendJson(JSONObject().put("type", "delivered").put("messageId", packet.messageId.toString()))
                    if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                        val deliveredId = text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                        onDeliveryAck(deliveredId)
                    } else {
                        onMessage(text, packet.sourceId, packet, "")
                        val ack = runCatching { router.createDeliveryAck(packet) }.getOrNull()
                        if (ack != null) send(ack)
                    }
                    queue.remove(packet.messageId)
                } else if (next != null) {
                    queue.enqueue(next)
                    send(next)
                }
            }
            "accepted" -> Log.d(TAG, "relay accepted id=${msg.optString("messageId")} online=${msg.optBoolean("online")}")
            "error" -> Log.w(TAG, "relay error: ${msg.optString("message")}")
            "pong" -> Unit
        }
    }

    private fun rememberPeer(id: String, name: String, keyText: String) {
        if (id.isBlank() || id == localId) return
        val key = runCatching { Base64.decode(keyText, Base64.DEFAULT) }.getOrNull() ?: return
        if (key.isEmpty()) return
        peers[id] = name.ifBlank { id.take(8) }
        onPeer(id, name, key, "")
        onPeerCount(peers.size)
    }

    private fun sendJson(obj: JSONObject): Boolean = synchronized(writeLock) {
        val out = writer ?: return false
        return try {
            out.write(obj.toString())
            out.newLine()
            out.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
