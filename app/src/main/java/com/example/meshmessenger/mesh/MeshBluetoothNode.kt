package com.example.meshmessenger.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Simple Classic Bluetooth chat node: controlled discovery + insecure RFCOMM. */
class MeshBluetoothNode(
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
        private const val DISCOVERY_INTERVAL_MS = 20_000L
        private const val DISCOVERY_RETRY_MS = 2_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val PRESENCE_INTERVAL_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MeshBtNode").apply { isDaemon = true }
    }
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    private val readyPeers = ConcurrentHashMap.newKeySet<String>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private var receiverRegistered = false
    private var running = false
    @Volatile private var discoveryActive = false

    private val rfcomm = MeshRfcommTransport(
        context, adapter, MeshProtocol.RFCOMM_SERVICE_UUID,
        { helloPayload() }, onDiagnostic,
        { address, bytes -> handleFrame(address, bytes) },
        { address ->
            connectingPeers.remove(address)
            onDiagnostic("BT_RFCOMM", "connected address=**@@{address.takeLast(5)}")
        },
        { address ->
            connectingPeers.remove(address)
            readyPeers.remove(address)
            peerNodeIds.remove(address)
            onPeerCountChanged(peerCount())
            if (running) {
                handler.postDelayed({ if (running) maybeStartDiscovery() }, RECONNECT_DELAY_MS)
            }
        }
    )

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    discoveryActive = false
                    onDiagnostic("BT_DISCOVERY", "finished")
                    handler.postDelayed({ if (running) maybeStartDiscovery() }, DISCOVERY_RETRY_MS)
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = deviceFromIntent(intent) ?: return
                    if (device.address == adapter.address) return
                    onDiagnostic(
                        "BT_DISCOVERY",
                        "found name=" + runCatching { device.name }.getOrNull().orEmpty().take(32) +
                            " address=**" + device.address.takeLast(5)
                    )
                    if (shouldInitiate(device.address)) connectIfNeeded(device.address)
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = deviceFromIntent(intent) ?: return
                    if (device.address != adapter.address) {
                        onDiagnostic("BT_DISCOVERY", "acl_connected address=**" + device.address.takeLast(5))
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!adapter.isEnabled) {
            onStatus("Bluetooth: выключен")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Bluetooth: нет разрешения")
            return
        }

        running = true
        discoveryActive = false
        connectingPeers.clear()
        registerReceiver()
        rfcomm.start()
        onDiagnostic("BT", "started transport=RFCOMM")
        connectBondedPeers()
        handler.postDelayed({ if (running) maybeStartDiscovery() }, 750L)
        handler.postDelayed(presenceLoop, PRESENCE_INTERVAL_MS)
        handler.postDelayed(discoveryLoop, DISCOVERY_INTERVAL_MS)
    }

    private val discoveryLoop = object : Runnable {
        override fun run() {
            if (!running) return
            maybeStartDiscovery()
            handler.postDelayed(this, DISCOVERY_INTERVAL_MS)
        }
    }

    private val presenceLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val peers = readyPeers.toList()
            io.execute {
                for (address in peers) {
                    if (rfcomm.readyAddresses().contains(address)) {
                        rfcomm.send(address, helloPayload())
                        onDiagnostic("BT_HELLO_TX", "heartbeat address=**@@{address.takeLast(5)}")
                    }
                }
            }
            handler.postDelayed(this, PRESENCE_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun connectBondedPeers() {
        for (device in runCatching { adapter.bondedDevices }.getOrDefault(emptySet())) {
            if (device.address != adapter.address && shouldInitiate(device.address)) {
                connectIfNeeded(device.address)
            }
        }
    }

    private fun hasReadyPeers(): Boolean =
        readyPeers.any { rfcomm.readyAddresses().contains(it) }

    @SuppressLint("MissingPermission")
    private fun maybeStartDiscovery() {
        if (!running || !adapter.isEnabled || discoveryActive) return
        if (hasReadyPeers() || connectingPeers.isNotEmpty()) {
            onDiagnostic("BT_DISCOVERY", "deferred connected=" + hasReadyPeers() + " connecting=" + connectingPeers.size)
            return
        }

        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (started) {
            discoveryActive = true
            onDiagnostic("BT_DISCOVERY", "start=true")
        } else {
            onDiagnostic("BT_DISCOVERY", "start=false")
            handler.postDelayed({ if (running) maybeStartDiscovery() }, DISCOVERY_RETRY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectIfNeeded(address: String) {
        if (!running || address == adapter.address) return
        if (readyPeers.contains(address) || connectingPeers.contains(address)) return

        connectingPeers.add(address)
        if (discoveryActive || runCatching { adapter.isDiscovering }.getOrDefault(false)) {
            runCatching { adapter.cancelDiscovery() }
            discoveryActive = false
            onDiagnostic("BT_DISCOVERY", "cancel_for_connect address=**" + address.takeLast(5))
        }
        onDiagnostic("BT_RFCOMM", "connect_requested address=**" + address.takeLast(5))
        rfcomm.connect(address, MeshProtocol.RFCOMM_SERVICE_UUID)
    }

    @SuppressLint("MissingPermission")
    private fun shouldInitiate(address: String): Boolean {
        val local = runCatching { adapter.address }.getOrDefault("")
        return local.isNotBlank() && local < address
    }

    private fun helloPayload(): ByteArray {
        val key = Base64.encodeToString(localPublicKey, Base64.NO_WRAP)
        return listOf(HELLO_MAGIC, localId, localName.take(64), key)
            .joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }

    private fun handleFrame(address: String, bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        if (text.startsWith(HELLO_MAGIC + "|")) {
            handleHello(address, text)
            return
        }
        val packet = MeshPacket.decode(bytes) ?: run {
            onDiagnostic("BT_PACKET", "decode_failed address=**@@{address.takeLast(5)} bytes=@@{bytes.size}")
            return
        }
        handlePacket(address, packet)
    }

    private fun handleHello(address: String, text: String) {
        val parts = text.split("|", limit = 4)
        if (parts.size != 4 || parts[1].isBlank() || parts[1] == localId) return
        val peerId = parts[1].trim()
        val key = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull() ?: return
        if (key.isEmpty()) return
        peerNodeIds[address] = peerId
        readyPeers.add(address)
        rfcomm.markReady(address)
        onDiagnostic("BT_READY", "peer=@@{peerId.take(12)} address=**@@{address.takeLast(5)}")
        onPeer(peerId, parts[2].trim().ifBlank { peerId.take(8) }, key)
        onPeerCountChanged(peerCount())
        flushQueue()
    }

    private fun handlePacket(address: String, packet: MeshPacket) {
        onDiagnostic("BT_PACKET_RX", "id=@@{packet.messageId} src=@@{packet.sourceId.take(8)} dst=@@{packet.destinationId.take(8)}")
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val text = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                val deliveredId = text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                queue.remove(runCatching { UUID.fromString(deliveredId) }.getOrNull() ?: packet.messageId)
                onDeliveryAck(deliveredId)
                onDiagnostic("BT_DELIVERY_ACK", "delivered=@@{deliveredId}")
            } else {
                onMessage(text, packet.sourceId, packet)
                runCatching { router.createDeliveryAck(packet) }.getOrNull()?.let { sendTo(address, it) }
                queue.remove(packet.messageId)
                onDiagnostic("BT_DELIVERED", "id=@@{packet.messageId}")
            }
        } else if (next != null) {
            queue.enqueue(next)
            broadcast(next, address)
        }
    }

    private fun sendTo(address: String, packet: MeshPacket): Boolean =
        rfcomm.send(address, packet.encode())

    fun send(packet: MeshPacket) {
        queue.enqueue(packet)
        flushQueue()
    }

    fun retryPending() {
        if (running) flushQueue()
    }

    private fun flushQueue() {
        val entries = queue.snapshot()
        val peers = readyPeers.toList()
        io.execute {
            for (entry in entries) {
                for (address in peers) {
                    if (!rfcomm.readyAddresses().contains(address)) {
                        readyPeers.remove(address)
                        continue
                    }
                    rfcomm.send(address, entry.bytes)
                }
            }
        }
    }

    private fun broadcast(packet: MeshPacket, except: String?) {
        val bytes = packet.encode()
        for (address in readyPeers.toList()) {
            if (address != except) rfcomm.send(address, bytes)
        }
    }

    fun onlinePeerCount(): Int = peerCount()

    fun isBluetoothTransportReady(): Boolean =
        readyPeers.any { rfcomm.readyAddresses().contains(it) }

    fun isHealthy(): Boolean = running && adapter.isEnabled && receiverRegistered

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        discoveryActive = false
        handler.removeCallbacks(discoveryLoop)
        handler.removeCallbacks(presenceLoop)
        runCatching { adapter.cancelDiscovery() }
        rfcomm.stop()
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        readyPeers.clear()
        peerNodeIds.clear()
        connectingPeers.clear()
        onPeerCountChanged(0)
        io.shutdownNow()
        onDiagnostic("BT", "stopped")
    }

    private fun peerCount(): Int {
        val ids = mutableSetOf<String>()
        for (address in readyPeers) {
            if (rfcomm.readyAddresses().contains(address)) ids += peerNodeIds[address] ?: address
        }
        return ids.size
    }

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
