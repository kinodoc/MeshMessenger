package com.example.meshmessenger.mesh

import android.net.Network
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single NetBird application transport.
 *
 * TCP/42424 carries both Mesh packets and a tiny peer hello handshake. There
 * is deliberately no second discovery port: peer discovery is multiplexed
 * into the same TCP transport used for messages.
 */
class MeshIpTransport(
    private val localId: String,
    private val localName: String,
    private val publicKey: ByteArray,
    private val router: MeshRouter,
    private val queue: PendingMessageStore,
    private val guard: NetBirdGuard,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String, String, MeshPacket, String) -> Unit,
    private val onDeliveryAck: (String) -> Unit = {},
    private val knownTargets: () -> List<InetAddress> = { emptyList() },
    private val onPeer: (nodeId: String, name: String, publicKey: ByteArray, ip: String) -> Unit = { _, _, _, _ -> },
    private val onPeerCount: (Int) -> Unit = {}
) {

    companion object {
        const val PORT = 42424
        private const val CONNECT_TIMEOUT_MS = 1200
        private const val READ_TIMEOUT_MS = 1800
        private const val DISCOVERY_MS = 15000L
        private const val PEER_TTL_MS = 45000L
        private const val MAX_FRAME_SIZE = 8 * 1024 * 1024
        private const val HELLO_PREFIX = "MESH_HELLO_V3|"
        private const val MAX_TARGETS = 256
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val peers = ConcurrentHashMap<String, Long>()
    private val peerIps = ConcurrentHashMap<String, String>()

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute { runServer() }
        executor.execute { runDiscovery() }
    }

    private fun runServer() {
        try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(PORT))
                server = socket
                onStatus("IP: TCP/$PORT готов")

                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        if (running.get()) onStatus("IP: ошибка входящего соединения")
                        break
                    }
                    executor.execute { handleIncoming(client) }
                }
            }
        } catch (e: Exception) {
            if (running.get()) onStatus("IP: listener: ${e.message ?: "ошибка"}")
        } finally {
            server = null
        }
    }

    private fun runDiscovery() {
        while (running.get()) {
            runCatching { discoverPeers() }
                .onFailure { onStatus("IP discovery: ${it.message ?: "ошибка"}") }
            purgePeers()
            if (!running.get()) break
            try {
                Thread.sleep(DISCOVERY_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun discoverPeers() {
        if (guard.localNetBirdIp().isNullOrBlank()) return
        val targets = LinkedHashSet<InetAddress>()
        targets.addAll(knownTargets())
        targets.addAll(guard.netBirdDiscoveryTargets())

        targets.asSequence()
            .filter { it is Inet4Address }
            .filter { guard.isNetBirdAddress(it) }
            .filter { it.hostAddress != guard.localNetBirdIp() }
            .take(MAX_TARGETS)
            .forEach { target ->
                executor.execute { probeHello(target) }
            }
    }

    private fun probeHello(target: InetAddress) {
        runCatching {
            val network = guard.netBirdNetwork()
                ?: throw IllegalStateException("NetBird network unavailable")
            Socket().use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(target, PORT), CONNECT_TIMEOUT_MS)
                writeFrame(socket, helloPayload())
                val reply = readFrame(socket) ?: return
                handleHello(reply, socket.inetAddress)
            }
        }
    }

    private fun helloPayload(): ByteArray {
        val key = Base64.getEncoder().encodeToString(publicKey)
        return listOf(HELLO_PREFIX.removeSuffix("|"), localId, localName.take(64), key)
            .joinToString("|")
            .toByteArray(Charsets.UTF_8)
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        peers.clear()
        peerIps.clear()
        onPeerCount(0)
    }

    /**
     * Drain the transport-independent durable queue through currently known
     * NetBird peers. A successful TCP write is not a delivery confirmation;
     * the queue is cleared only after the recipient's delivery ACK.
     */
    fun retryPending() {
        if (!running.get() || guard.localNetBirdIp().isNullOrBlank()) return
        purgePeers()
        val entries = queue.snapshot()
        for (entry in entries) {
            val packet = MeshPacket.decode(entry.bytes) ?: continue
            val targetIp = peerIps[packet.destinationId] ?: continue
            send(packet, targetIp)
        }
    }

    fun send(packet: MeshPacket, host: String): Boolean {
        if (!running.get()) {
            onStatus("IP: транспорт не запущен")
            return false
        }
        if (guard.localNetBirdIp().isNullOrBlank()) {
            onStatus("IP: внутренняя сеть NetBird не подключена")
            return false
        }

        val address = runCatching { InetAddress.getByName(host) }.getOrNull()
        if (address !is Inet4Address || !guard.isNetBirdAddress(address)) {
            onStatus("IP: адрес не является NetBird IPv4")
            return false
        }

        val bytes = packet.encode()
        if (bytes.size > MAX_FRAME_SIZE) {
            onStatus("IP: пакет слишком большой")
            return false
        }

        return runCatching {
            val network = guard.netBirdNetwork()
                ?: throw IllegalStateException("NetBird network unavailable")
            Socket().use { socket ->
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(address, PORT), 5000)
                writeFrame(socket, bytes)
            }
            onStatus("IP: отправлено через NetBird")
            true
        }.getOrElse {
            onStatus("IP: ошибка отправки: ${it.message ?: "соединение"}")
            false
        }
    }

    private fun handleIncoming(socket: Socket) {
        socket.use {
            val remote = it.inetAddress
            if (!guard.isNetBirdAddress(remote)) {
                onStatus("IP: отклонено соединение не из NetBird")
                return
            }

            it.soTimeout = READ_TIMEOUT_MS
            val bytes = readFrame(it) ?: return
            if (isHello(bytes)) {
                handleHello(bytes, remote)
                writeFrame(it, helloPayload())
                return
            }

            val packet = MeshPacket.decode(bytes) ?: run {
                onStatus("IP: повреждённый пакет")
                return
            }
            val next = router.onReceive(packet)

            if (packet.destinationId == localId) {
                val text = router.decryptForLocal(packet)
                    ?: "[не удалось расшифровать]"
                if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                    onDeliveryAck(text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX))
                } else {
                    onMessage(text, packet.sourceId, packet, remote.hostAddress)
                    val ack = runCatching { router.createDeliveryAck(packet) }.getOrNull()
                    if (ack != null) send(ack, remote.hostAddress)
                }
                queue.remove(packet.messageId)
                onStatus("IP: получено сообщение")
            } else if (next != null) {
                queue.enqueue(next)
                onStatus("IP: получен транзитный пакет")
            }
        }
    }

    private fun isHello(bytes: ByteArray): Boolean =
        String(bytes, Charsets.UTF_8).startsWith(HELLO_PREFIX)

    private fun handleHello(bytes: ByteArray, remote: InetAddress) {
        val parts = String(bytes, Charsets.UTF_8).split("|", limit = 4)
        if (parts.size != 4 || parts[0] != HELLO_PREFIX.removeSuffix("|")) return
        val id = parts[1].trim()
        if (id.isBlank() || id == localId) return
        val name = parts[2].trim().ifBlank { id.take(8) }
        val key = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return
        if (key.isEmpty()) return
        peers[id] = System.currentTimeMillis()
        peerIps[id] = remote.hostAddress.orEmpty()
        onPeerCount(peers.size)
        onPeer(id, name, key, remote.hostAddress.orEmpty())
    }

    private fun purgePeers() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        peers.entries.removeIf {
            if (it.value < cutoff) {
                peerIps.remove(it.key)
                true
            } else false
        }
        onPeerCount(peers.size)
    }

    private fun writeFrame(socket: Socket, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_SIZE) {
            throw IllegalArgumentException("invalid frame size")
        }
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readFrame(socket: Socket): ByteArray? {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val length = runCatching { input.readInt() }.getOrNull() ?: return null
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            onStatus("IP: недопустимый размер пакета")
            return null
        }
        return ByteArray(length).also { input.readFully(it) }
    }
}
