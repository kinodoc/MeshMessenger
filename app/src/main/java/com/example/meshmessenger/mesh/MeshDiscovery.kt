package com.example.meshmessenger.mesh

import android.net.Network
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
    private val onStatus: (String) -> Unit,
    private val netBirdNetwork: () -> Network? = { null },
    private val netBirdTargets: () -> List<InetAddress> = { emptyList() }
) {
    companion object {
        const val PORT = 42425
        private const val MAGIC = "MESH_DISCOVERY_V2"
        private const val MULTICAST = "239.255.42.99"
        private const val ANNOUNCE_MS = 5000L
        // NetBird peers must be refreshed faster than their expiry window.
        // The old 15s/15s pair could expire a peer just before the next probe.
        private const val PROBE_MS = 5000L
        private const val PEER_TTL_MS = 30000L
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val peers = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val netBirdPeers = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sockets = java.util.Collections.synchronizedSet(mutableSetOf<DatagramSocket>())

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startReceiver(null)
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
        executor.execute {
            while (running.get()) {
                probeNetBird()
                Thread.sleep(PROBE_MS)
            }
        }
    }

    private fun startReceiver(network: Network?) {
        executor.execute {
            runCatching {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.bind(java.net.InetSocketAddress(PORT))
                    network?.bindSocket(s)
                    sockets.add(s)
                    onStatus(if (network == null) "IP discovery LAN готов" else "NetBird discovery готов")
                    val buffer = ByteArray(4096)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        s.receive(packet)
                        handle(packet, s)
                    }
                    sockets.remove(s)
                }
            }.onFailure {
                if (running.get()) {
                    val prefix = if (network == null) "IP discovery LAN: " else "NetBird discovery: "
                    onStatus(prefix + (it.message ?: "ошибка"))
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
        peers.clear()
        netBirdPeers.clear()
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
        for (target in targets) send(bytes, target, null)
    }

    private fun probeNetBird() {
        val targets = linkedSetOf<InetAddress>()
        targets.addAll(netBirdTargets())
        if (targets.isEmpty()) return
        val bytes = payload()
        val network = netBirdNetwork() ?: return
        for (target in targets) {
            if (target is Inet4Address && isNetBirdAddress(target)) {
                send(bytes, target, network)
            }
        }
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
                        if (target.hostAddress != address.hostAddress) {
                            send(payload(), target, null)
                        }
                    }
                }
            }
        }
    }

    private fun send(bytes: ByteArray, target: InetAddress, network: Network?) {
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                network?.bindSocket(s)
                s.send(DatagramPacket(bytes, bytes.size, target, PORT))
            }
        }
    }

    private fun handle(packet: DatagramPacket, replySocket: DatagramSocket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val parts = text.split("|", limit = 4)
        if (parts.size != 4 || parts[0] != MAGIC) return
        val id = parts[1].trim()
        if (id.isBlank() || id == localId) return
        val name = parts[2].trim().ifBlank { id.take(8) }
        val key = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return
        if (key.isEmpty()) return
        val viaNetBird = packet.address is Inet4Address && isNetBirdAddress(packet.address as Inet4Address)
        val now = System.currentTimeMillis()
        peers[id] = now
        if (viaNetBird) netBirdPeers[id] = now
        purgePeers()
        onCount(netBirdPeers.size)
        onPeer(id, name, key, packet.address.hostAddress.orEmpty())
        runCatching {
            val reply = payload()
            if (viaNetBird) {
                netBirdNetwork()?.let { network ->
                    send(reply, packet.address, network)
                }
            } else {
                replySocket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }
    }

    private fun purgePeers() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        peers.entries.removeIf { it.value < cutoff }
        netBirdPeers.entries.removeIf { it.value < cutoff }
        onCount(netBirdPeers.size)
    }

    private fun isNetBirdAddress(address: Inet4Address): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        return a == 100 && b in 64..127
    }
}
