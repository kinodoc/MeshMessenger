package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Clean transport: BLE discovers peers, classic RFCOMM carries data. */
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
        private const val HELLO = "MESH_HELLO_V1"
        private const val TTL = 30_000L
    }

    private val serviceUuid = MeshProtocol.BLUETOOTH_SERVICE_UUID
    private val rfcomm = MeshRfcommTransport(
        context, adapter, MeshProtocol.RFCOMM_SERVICE_UUID,
        { hello() }, onDiagnostic,
        { address, bytes -> receive(address, bytes) },
        { address -> onDiagnostic("BLE_RFCOMM", "connected address=**" + address.takeLast(5)) },
        { address ->
            peerNodeIds.remove(address)
            connecting.remove(address)
            onPeerCountChanged(peerCount())
        }
    )
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanning = false
    private var advertising = false
    private var running = false
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    private val peerSeen = ConcurrentHashMap<String, Long>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { discover(result) }
        override fun onScanFailed(code: Int) {
            scanning = false
            onDiagnostic("BLE_SCAN", "failed code=" + code)
        }
    }

    private fun hello(): ByteArray {
        val key = Base64.getEncoder().encodeToString(localPublicKey)
        return (HELLO + "|" + localId + "|" + localName.take(64) + "|" + key)
            .toByteArray(StandardCharsets.UTF_8)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!permissionsOk() || !adapter.isEnabled) {
            onDiagnostic("BLE_START", "not_ready")
            return
        }
        running = true
        rfcomm.start()
        startAdvertising()
        startScan()
        onDiagnostic("BLE_START", "clean_transport_started")
    }

    private fun permissionsOk(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val a = adapter.bluetoothLeAdvertiser ?: return
        advertiser = a
        val id = localId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), id)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings) {
                advertising = true
                onDiagnostic("BLE_ADVERTISE", "started clean=true")
                onStatus("Mesh активен • BLE готов")
            }
            override fun onStartFailure(code: Int) {
                advertising = false
                onDiagnostic("BLE_ADVERTISE", "failed code=" + code)
            }
        }
        runCatching { a.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { onDiagnostic("BLE_ADVERTISE", "exception=" + it.javaClass.simpleName) }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val s = adapter.bluetoothLeScanner ?: return
        scanner = s
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching {
            s.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            onDiagnostic("BLE_SCAN", "started clean=true")
        }.onFailure {
            onDiagnostic("BLE_SCAN", "exception=" + it.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover(result: ScanResult) {
        if (!running) return
        val address = result.device.address ?: return
        val data = result.scanRecord?.getServiceData(ParcelUuid(serviceUuid)) ?: return
        if (data.size < 8) return
        val peerId = data.take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (peerId == localId || peerId.isBlank()) return

        onDiagnostic("BLE_SCAN_MATCH", "node=" + peerId + " rssi=" + result.rssi)
        val initiator = localId < peerId
        onDiagnostic("BLE_ARBITRATION", "initiator=" + initiator + " peer=" + peerId)
        if (!initiator || !connecting.add(address) || rfcomm.readyAddresses().contains(address)) return
        rfcomm.connect(address, MeshProtocol.RFCOMM_SERVICE_UUID)
    }

    private fun receive(address: String, bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        if (text.startsWith(HELLO + "|")) {
            val p = text.split("|", limit = 4)
            if (p.size != 4 || p[1].isBlank() || p[1] == localId) return
            val key = runCatching { Base64.getDecoder().decode(p[3]) }.getOrNull() ?: return
            peerNodeIds[address] = p[1]
            peerSeen[p[1]] = System.currentTimeMillis()
            rfcomm.markReady(address)
            onPeer(p[1], p[2].ifBlank { p[1].take(8) }, key)
            onPeerCountChanged(peerCount())
            flushQueue()
            onDiagnostic("BLE_READY", "node=" + p[1] + " address=**" + address.takeLast(5))
            return
        }

        val packet = MeshPacket.decode(bytes) ?: return
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val textValue = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (textValue.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                val id = textValue.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                runCatching { queue.remove(UUID.fromString(id)) }
                onDeliveryAck(id)
            } else {
                onMessage(textValue, packet.sourceId, packet)
                runCatching { router.createDeliveryAck(packet) }.getOrNull()?.let { sendAck(it) }
            }
            queue.remove(packet.messageId)
        } else if (next != null) {
            queue.enqueue(next)
            broadcast(next.encode(), address)
        }
    }

    fun send(packet: MeshPacket) {
        queue.enqueue(packet)
        flushQueue()
    }

    fun retryPending() {
        if (rfcomm.readyAddresses().isNotEmpty()) flushQueue()
    }

    private fun sendAck(packet: MeshPacket) {
        queue.enqueue(packet)
        val bytes = packet.encode()
        rfcomm.readyAddresses().forEach { rfcomm.send(it, bytes) }
    }

    private fun flushQueue() {
        val entries = queue.snapshot()
        for (address in rfcomm.readyAddresses()) {
            for (entry in entries) rfcomm.send(address, entry.bytes)
        }
    }

    private fun broadcast(bytes: ByteArray, except: String? = null) {
        rfcomm.readyAddresses().filter { it != except }.forEach { rfcomm.send(it, bytes) }
    }

    private fun peerCount(): Int {
        val now = System.currentTimeMillis()
        peerSeen.entries.removeIf { now - it.value > TTL }
        return rfcomm.readyAddresses().count { peerNodeIds.containsKey(it) }
    }

    fun onlinePeerCount(): Int = peerCount()
    fun isBleTransportReady(): Boolean = rfcomm.readyAddresses().isNotEmpty()
    fun isHealthy(): Boolean = adapter.isEnabled && running && advertising && scanning

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        scanning = false
        advertising = false
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        scanner = null
        advertiser = null
        advertiseCallback = null
        rfcomm.stop()
        peerNodeIds.clear()
        peerSeen.clear()
        connecting.clear()
        onPeerCountChanged(0)
        onDiagnostic("BLE_STOP", "clean_transport_stopped")
    }
}
