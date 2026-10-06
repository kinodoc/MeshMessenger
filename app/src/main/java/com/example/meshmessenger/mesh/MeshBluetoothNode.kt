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

/** Simple Classic Bluetooth chat node: Android discovery + insecure RFCOMM. No legacy low-energy stack. */
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
        private const val DISCOVERY_INTERVAL_MS = 10_000L
        private const val PRESENCE_INTERVAL_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    private val readyPeers = ConcurrentHashMap.newKeySet<String>()
    private var receiverRegistered = false
    private var running = false

    private val rfcomm = MeshRfcommTransport(
        context, adapter, MeshProtocol.RFCOMM_SERVICE_UUID,
        { helloPayload() }, onDiagnostic,
        { address, bytes -> handleFrame(address, bytes) },
        { address -> onDiagnostic("BT_RFCOMM", "connected address=**${address.takeLast(5)}") },
        { address ->
            readyPeers.remove(address)
            peerNodeIds.remove(address)
            onPeerCountChanged(peerCount())
        }
    )

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            val action = intent?.action ?: return
            if (action != BluetoothDevice.ACTION_FOUND && action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothAdapter.ACTION_DISCOVERY_FINISHED) return
            if (action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                onDiagnostic("BT_DISCOVERY", "finished")
                handler.postDelayed({ if (running) startDiscovery() }, 250L)
                return
            }
            val device = deviceFromIntent(intent) ?: return
            if (device.address == adapter.address) return
            onDiagnostic("BT_DISCOVERY", "found name=" + runCatching { device.name }.getOrNull().orEmpty().take(32) + " address=**" + device.address.takeLast(5))
            if (shouldInitiate(device.address)) {
                rfcomm.connect(device.address, MeshProtocol.RFCOMM_SERVICE_UUID)
            } else {
                onDiagnostic("BT_DISCOVERY", "passive_peer address=**" + device.address.takeLast(5))
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
        registerReceiver()
        rfcomm.start()
        onDiagnostic("BT", "started transport=RFCOMM")
        startDiscovery()
        handler.postDelayed(discoveryLoop, DISCOVERY_INTERVAL_MS)
        handler.postDelayed(presenceLoop, PRESENCE_INTERVAL_MS)
    }

    private val discoveryLoop = object : Runnable {
        override fun run() {
            if (!running) return
            startDiscovery()
            handler.postDelayed(this, DISCOVERY_INTERVAL_MS)
        }
    }

    private val presenceLoop = object : Runnable {
        override fun run() {
            if (!running) return
            for (address in readyPeers.toList()) {
                if (rfcomm.readyAddresses().contains(address)) {
                    rfcomm.send(address, helloPayload())
                    onDiagnostic("BT_HELLO_TX", "heartbeat address=**${address.takeLast(5)}")
                }
            }
            handler.postDelayed(this, PRESENCE_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) },
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!running || !adapter.isEnabled) return
        runCatching { adapter.cancelDiscovery() }
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        onDiagnostic("BT_DISCOVERY", "start=" + started)
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
            onDiagnostic("BT_PACKET", "decode_failed address=**\${address.takeLast(5)} bytes=\${bytes.size}")
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
        onDiagnostic("BT_READY", "peer=\${peerId.take(12)} address=**\${address.takeLast(5)}")
        onPeer(peerId, parts[2].trim().ifBlank { peerId.take(8) }, key)
        onPeerCountChanged(peerCount())
        flushQueue()
    }

    private fun handlePacket(address: String, packet: MeshPacket) {
        onDiagnostic("BT_PACKET_RX", "id=\${packet.messageId} src=\${packet.sourceId.take(8)} dst=\${packet.destinationId.take(8)}")
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val text = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                val deliveredId = text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                queue.remove(runCatching { UUID.fromString(deliveredId) }.getOrNull() ?: packet.messageId)
                onDeliveryAck(deliveredId)
                onDiagnostic("BT_DELIVERY_ACK", "delivered=\$deliveredId")
            } else {
                onMessage(text, packet.sourceId, packet)
                runCatching { router.createDeliveryAck(packet) }.getOrNull()?.let { sendTo(address, it) }
                queue.remove(packet.messageId)
                onDiagnostic("BT_DELIVERED", "id=\${packet.messageId}")
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
        for (entry in queue.snapshot()) {
            for (address in readyPeers.toList()) {
                if (!rfcomm.readyAddresses().contains(address)) {
                    readyPeers.remove(address)
                    continue
                }
                rfcomm.send(address, entry.bytes)
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
        handler.removeCallbacks(discoveryLoop)
        handler.removeCallbacks(presenceLoop)
        runCatching { adapter.cancelDiscovery() }
        rfcomm.stop()
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        readyPeers.clear()
        peerNodeIds.clear()
        onPeerCountChanged(0)
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
    private fun startDiscovery() {
        if (!running || !adapter.isEnabled) return
        if (runCatching { adapter.isDiscovering }.getOrDefault(false)) {
            onDiagnostic("BT_DISCOVERY", "already_active")
            return
        }
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        onDiagnostic("BT_DISCOVERY", "start=" + started)
        if (!started) handler.postDelayed({ if (running) startDiscovery() }, 1500L)
    }
