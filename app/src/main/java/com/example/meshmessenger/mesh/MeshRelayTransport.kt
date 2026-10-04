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
    private val onPeerCount: (Int) -> Unit = {},
    private val onDiagnostic: (String, String) -> Unit = { _, _ -> }
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
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private data class PeerInfo(val name: String, val publicKey: ByteArray)
    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val writeLock = Any()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var lastPingAt = 0L
    private data class RetryState(val attempt: Int, val lastAttemptAt: Long)
    private val retryStates = ConcurrentHashMap<String, RetryState>()
    @Volatile private var lastAcceptedDiagnosticAt = 0L
    private var acceptedSinceDiagnostic = 0

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
        onDiagnostic("RELAY_START", "state=starting")
        executor.execute { connectionLoop() }
    }

    fun stop() {
        running.set(false)
        onDiagnostic("RELAY_STOP", "state=stopping")
        runCatching { socket?.close() }
        socket = null
        writer = null
        reader = null
        peers.clear()
        onPeerCount(0)
        executor.shutdownNow()
        writeExecutor.shutdownNow()
    }

    fun send(packet: MeshPacket, ignoredAddress: String = ""): Boolean {
        if (!running.get()) {
            onDiagnostic("RELAY_SEND_SKIPPED", "reason=not_running")
            return false
        }
        onDiagnostic("RELAY_SEND", "packet_bytes=${packet.encode().size}")
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
        // The relay's peer_online event is edge-triggered, not a heartbeat.
        // Refresh lastSeen for peers that are still in the relay's live peer set.
        peers.forEach { (id, peer) ->
            onPeer(id, peer.name, peer.publicKey, "")
        }
        val entries = queue.snapshot().take(32)
        val queuedIds = entries.mapTo(HashSet()) { it.id.toString() }
        retryStates.keys.removeAll { it !in queuedIds }
        entries.forEach { entry ->
            val id = entry.id.toString()
            val state = retryStates.putIfAbsent(id, RetryState(0, now)) ?: RetryState(0, now)
            val delay = (5_000L * (1L shl state.attempt.coerceAtMost(6))).coerceAtMost(300_000L)
            if (now - state.lastAttemptAt < delay) return@forEach
            val packet = MeshPacket.decode(entry.bytes) ?: return@forEach
            if (send(packet)) retryStates[id] = RetryState(state.attempt + 1, now)
            else retryStates[id] = RetryState(state.attempt + 1, now)
        }
    }

    private fun connectionLoop() {
        var delay = 1500L
        while (running.get()) {
            try {
                onDiagnostic("RELAY_CONNECT", "state=attempt")
                val s = sslContext.socketFactory.createSocket() as SSLSocket
                s.connect(InetSocketAddress(HOST, PORT), 8000)
                s.soTimeout = 0
                s.startHandshake()
                onDiagnostic("RELAY_CONNECT", "state=connected tls=ok")
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
                    Log.w(TAG, "connection lost: ${e.javaClass.simpleName}")
                    onDiagnostic("RELAY_ERROR", "stage=connection type=${e.javaClass.simpleName}")
                    onStatus("Relay: ожидание соединения")
                }
            } finally {
                onDiagnostic("RELAY_CONNECT", "state=disconnected")
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
                onDiagnostic("RELAY_WELCOME", "state=received")
                val list = msg.optJSONArray("peers")
                if (list != null) for (i in 0 until list.length()) {
                    val p = list.optJSONObject(i) ?: continue
                    rememberPeer(p.optString("nodeId"), p.optString("name"), p.optString("publicKey"))
                }
                onStatus("Relay: подключено")
            }
            "peer_online" -> rememberPeer(msg.optString("nodeId"), msg.optString("name"), msg.optString("publicKey"))
            "peer_offline" -> {
                onDiagnostic("RELAY_PEER", "state=offline")
                peers.remove(msg.optString("nodeId"))
                onPeerCount(peers.size)
            }
            "packet" -> {
                onDiagnostic("RELAY_PACKET", "stage=received")
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
            "accepted" -> {
                acceptedSinceDiagnostic++
                val now = System.currentTimeMillis()
                if (lastAcceptedDiagnosticAt == 0L || now - lastAcceptedDiagnosticAt >= 60_000L) {
                    onDiagnostic("RELAY_PACKET", "stage=accepted online=${msg.optBoolean("online")} count=${acceptedSinceDiagnostic}")
                    acceptedSinceDiagnostic = 0
                    lastAcceptedDiagnosticAt = now
                }
            }
            "error" -> onDiagnostic("RELAY_SERVER_ERROR", "code=${msg.optString("code", "unspecified").take(40)}")
            "pong" -> Unit
        }
    }

    private fun rememberPeer(id: String, name: String, keyText: String) {
        if (id.isBlank() || id == localId) {
            onDiagnostic("RELAY_PEER_REJECTED", "reason=invalid_or_self")
            return
        }
        val key = runCatching { Base64.decode(keyText, Base64.DEFAULT) }.getOrNull() ?: return
        if (key.isEmpty()) {
            onDiagnostic("RELAY_PEER_REJECTED", "reason=empty_public_key")
            return
        }
        val safeName = name.ifBlank { id.take(8) }
        peers[id] = PeerInfo(safeName, key)
        onPeer(id, safeName, key, "")
        onPeerCount(peers.size)
    }

    private fun sendJson(obj: JSONObject): Boolean {
        val out = writer ?: return false
        return runCatching {
            writeExecutor.execute {
                synchronized(writeLock) {
                    if (writer !== out) return@synchronized
                    try {
                        out.write(obj.toString())
                        out.newLine()
                        out.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "write failed: ${e.javaClass.simpleName}")
                        runCatching { socket?.close() }
                    }
                }
            }
        }.isSuccess
    }
}
