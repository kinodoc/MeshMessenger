package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Simple Bluetooth Classic chat node. RFCOMM is the only transport. */
class MeshGattNode(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val localId: String,
    private val localName: String,
    private val localPublicKey: ByteArray,
    private val router: MeshRouter,
    private val queue: PendingMessageStore,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String, String, MeshPacket) -> Unit,
    private val onPeer: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    private val onDeliveryAck: (String) -> Unit = {},
    private val onPeerCountChanged: (Int) -> Unit = {},
    private val onDiagnostic: (String, String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val HELLO_MAGIC = "MESH_HELLO_V1"
        private const val RETRY_MS = 5000L
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MeshBluetoothChat").apply { isDaemon = true }
    }

    private val peerIds = mutableMapOf<String, String>()
    private val awaitingDelivery = mutableMapOf<String, MutableMap<UUID, Long>>()
    @Volatile private var running = false

    private val rfcomm = MeshRfcommTransport(
        context = context,
        adapter = adapter,
        serviceUuid = MeshProtocol.RFCOMM_SERVICE_UUID,
        helloPayload = ::hello,
        onDiagnostic = onDiagnostic,
        onFrame = { address, bytes -> handleIncoming(address, bytes) },
        onConnected = { address ->
            onDiagnostic("BLUETOOTH", "connected address=**" + address.takeLast(5))
        },
        onDisconnected = { address ->
            synchronized(peerIds) { peerIds.remove(address) }
            onDiagnostic("BLUETOOTH", "disconnected address=**" + address.takeLast(5))
            onPeerCountChanged(peerCount())
        }
    )

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!hasConnectPermission()) {
            onStatus("Bluetooth: нет разрешения на подключение")
            return
        }
        if (!adapter.isEnabled) {
            onStatus("Bluetooth выключен")
            return
        }
        running = true
        rfcomm.start()
        onStatus("Bluetooth чат активен")
        onDiagnostic("BLUETOOTH", "transport=RFCOMM_only")
        executor.scheduleWithFixedDelay({
            if (running) {
                connectBondedPeers()
                flushQueue()
            }
        }, 0, RETRY_MS, TimeUnit.MILLISECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun connectBondedPeers() {
        if (!hasConnectPermission() || !adapter.isEnabled) return
        runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList()).forEach {
            rfcomm.connect(it)
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun hello(): ByteArray {
        val key = Base64.getEncoder().encodeToString(localPublicKey)
        return listOf(HELLO_MAGIC, localId, localName.take(64), key)
            .joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }

    private fun handleIncoming(from: String, bytes: ByteArray) {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
        if (text != null && text.startsWith(HELLO_MAGIC + "|")) {
            val p = text.split("|", limit = 4)
            if (p.size == 4) {
                val id = p[1].trim()
                val name = p[2].trim().ifBlank { id.take(8) }
                val key = runCatching { Base64.getDecoder().decode(p[3]) }.getOrNull()
                if (id.isNotBlank() && key != null && key.isNotEmpty()) {
                    synchronized(peerIds) { peerIds[from] = id }
                    rfcomm.markReady(from)
                    onPeer(id, name, key)
                    onPeerCountChanged(peerCount())
                    flushQueue()
                }
            }
            return
        }

        val packet = MeshPacket.decode(bytes) ?: return
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val payload = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (payload.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                val delivered = payload.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                markDelivered(delivered)
                onDeliveryAck(delivered)
            } else {
                onMessage(payload, packet.sourceId, packet)
                runCatching { router.createDeliveryAck(packet) }.getOrNull()?.let {
                    broadcast(it.encode(), from)
                }
            }
            queue.remove(packet.messageId)
        } else if (next != null) {
            queue.enqueue(next)
            broadcast(next.encode(), from)
        }
    }

    @SuppressLint("MissingPermission")
    fun send(packet: MeshPacket) {
        queue.enqueue(packet)
        flushQueue()
    }

    fun retryPending() {
        if (running) flushQueue()
    }

    @SuppressLint("MissingPermission")
    private fun flushQueue() {
        if (!running) return
        val now = System.currentTimeMillis()
        for (address in rfcomm.readyAddresses()) {
            for (entry in queue.snapshot()) {
                val waiting = awaitingDelivery[address]?.get(entry.id)
                if (waiting != null && now - waiting < RETRY_MS) continue
                if (waiting != null) awaitingDelivery[address]?.remove(entry.id)
                if (rfcomm.send(address, entry.bytes)) {
                    awaitingDelivery.getOrPut(address) { mutableMapOf() }[entry.id] = now
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcast(bytes: ByteArray, except: String? = null) {
        rfcomm.readyAddresses().filter { it != except }.forEach { rfcomm.send(it, bytes) }
    }

    private fun markDelivered(messageId: String) {
        val id = runCatching { UUID.fromString(messageId) }.getOrNull() ?: return
        queue.remove(id)
        synchronized(awaitingDelivery) {
            awaitingDelivery.values.forEach { it.remove(id) }
            awaitingDelivery.entries.removeIf { it.value.isEmpty() }
        }
    }

    private fun peerCount(): Int = synchronized(peerIds) {
        rfcomm.readyAddresses().map { peerIds[it] ?: it }.toSet().size
    }

    fun onlinePeerCount(): Int = peerCount()
    fun isBleTransportReady(): Boolean = rfcomm.readyAddresses().isNotEmpty()
    fun isHealthy(): Boolean = running && adapter.isEnabled && rfcomm.isRunning()

    fun stop() {
        running = false
        executor.shutdownNow()
        rfcomm.stop()
        synchronized(peerIds) { peerIds.clear() }
        synchronized(awaitingDelivery) { awaitingDelivery.clear() }
        onPeerCountChanged(0)
        onDiagnostic("BLUETOOTH", "stopped")
    }
}
