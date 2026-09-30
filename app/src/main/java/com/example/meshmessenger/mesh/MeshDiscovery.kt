package com.example.meshmessenger.mesh

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MeshDiscovery(
    private val localId: String,
    private val localName: String,
    private val publicKey: ByteArray,
    private val onPeer: (nodeId: String, name: String, publicKey: ByteArray, ip: String) -> Unit,
    private val onCount: (Int) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val PORT = 42425
        private const val MAGIC = "MESH_DISCOVERY_V1"
        private const val MULTICAST = "239.255.42.99"
        private const val ANNOUNCE_MS = 5000L
        private const val PROBE_MS = 15000L
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val peers = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var socket: DatagramSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            runCatching {
                DatagramSocket(PORT).also { socket = it }.use { s ->
                    s.broadcast = true
                    onStatus("IP discovery готов")
                    val buffer = ByteArray(4096)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        s.receive(packet)
                        handle(packet)
                    }
                }
            }.onFailure {
                if (running.get()) onStatus("IP discovery: ${it.message ?: "ошибка"}")
            }
        }
        executor.execute {
            while (running.get()) {
                announce()
                Thread.sleep(ANNOUNCE_MS)
            }
        }
        executor.execute {
            while (running.get()) {
                probeRoutedSubnets()
                Thread.sleep(PROBE_MS)
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        peers.clear()
        onCount(0)
    }

    private fun payload(): ByteArray {
        val key = Base64.getEncoder().encodeToString(publicKey)
        return listOf(MAGIC, localId, localName.take(64), key)
            .joinToString("|").toByteArray(Charsets.UTF_8)
    }

    private fun announce() {
        val bytes = payload()
        val targets = LinkedHashSet<InetAddress>()
        runCatching { targets.add(InetAddress.getByName(MULTICAST)) }
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        for (iface in interfaces) {
            runCatching {
                if (!iface.isUp || iface.isLoopback) return@runCatching
                iface.interfaceAddresses.forEach { ia ->
                    ia.broadcast?.let { targets.add(it) }
                }
            }
        }
        for (target in targets) send(bytes, target)
    }

    private fun probeRoutedSubnets() {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        for (iface in interfaces) {
            runCatching {
                if (!iface.isUp || iface.isLoopback) return@runCatching
                for (ia in iface.interfaceAddresses) {
                    val address = ia.address as? Inet4Address ?: continue
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix < 24 || prefix > 30) continue
                    val hostBits = 32 - prefix
                    val hostCount = 1 shl hostBits
                    val base = ByteBuffer.wrap(address.address).int
                    val mask = (-1 shl hostBits)
                    val network = base and mask
                    for (n in 1 until hostCount - 1) {
                        val target = InetAddress.getByAddress(
                            ByteBuffer.allocate(4).putInt(network or n).array()
                        )
                        if (target.hostAddress != address.hostAddress) send(payload(), target)
                    }
                }
            }
        }
    }

    private fun send(bytes: ByteArray, target: InetAddress) {
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.send(DatagramPacket(bytes, bytes.size, target, PORT))
            }
        }
    }

    private fun handle(packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val parts = text.split("|", limit = 4)
        if (parts.size != 4 || parts[0] != MAGIC) return
        val id = parts[1].trim()
        if (id.isBlank() || id == localId) return
        val name = parts[2].trim().ifBlank { id.take(8) }
        val key = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return
        if (key.isEmpty()) return
        peers[id] = System.currentTimeMillis()
        purgePeers()
        onCount(peers.size)
        onPeer(id, name, key, packet.address.hostAddress.orEmpty())
        val reply = payload()
        runCatching {
            DatagramSocket().use { s ->
                s.send(DatagramPacket(reply, reply.size, packet.address, PORT))
            }
        }
    }

    private fun purgePeers() {
        val cutoff = System.currentTimeMillis() - ANNOUNCE_MS * 3
        peers.entries.removeIf { it.value < cutoff }
        onCount(peers.size)
    }
}
