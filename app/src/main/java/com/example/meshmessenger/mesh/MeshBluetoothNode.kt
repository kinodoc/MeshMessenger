package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Minimal BLE GATT mesh transport: advertise/scan + one GATT service/characteristic. */
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
        private const val MAGIC: Byte = 0x4d
        private const val HEADER = 8
        private const val MAX_FRAME = 4092
        private const val ASSEMBLY_TIMEOUT = 30_000L
        private const val RECONNECT_DELAY = 800L
        private const val RX_UUID = "7d2a1001-8b4f-4f10-9d3e-8b7d6a2f0001"
        private const val TX_UUID = "7d2a1002-8b4f-4f10-9d3e-8b7d6a2f0001"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }

    private val main = Handler(Looper.getMainLooper())
    private val serviceUuid = MeshProtocol.BLUETOOTH_SERVICE_UUID
    private val rxUuid = UUID.fromString(RX_UUID)
    private val txUuid = UUID.fromString(TX_UUID)
    private val cccdUuid = UUID.fromString(CCCD_UUID)

    private var server: BluetoothGattServer? = null
    private var service: BluetoothGattService? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanning = false
    private var advertising = false
    private var running = false

    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientRx = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribed = ConcurrentHashMap.newKeySet<String>()
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val writeQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val writing = ConcurrentHashMap.newKeySet<String>()
    private val notifyQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val notifying = ConcurrentHashMap.newKeySet<String>()
    private val assemblies = ConcurrentHashMap<String, Assembly>()
    private val frameCounter = AtomicInteger(1)

    private data class Assembly(
        val type: Int,
        val count: Int,
        val parts: Array<ByteArray?>,
        val createdAt: Long
    )

    private fun hello(): ByteArray {
        val key = Base64.encodeToString(localPublicKey, Base64.NO_WRAP)
        return (HELLO + "|" + localId + "|" + localName.take(64) + "|" + key)
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun permissionsOk(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!permissionsOk() || !adapter.isEnabled) {
            onDiagnostic("BLE_START", "not_ready")
            return
        }
        running = true
        openServer()
    }

    @SuppressLint("MissingPermission")
    private fun openServer() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        server = runCatching { manager.openGattServer(context, serverCallback) }.getOrNull()
        if (server == null) {
            running = false
            onDiagnostic("BLE_GATT_SERVER", "open_failed")
            return
        }

        val gattService = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            rxUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            txUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(BluetoothGattDescriptor(
            cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))
        gattService.addCharacteristic(rx)
        gattService.addCharacteristic(tx)
        service = gattService

        if (!server!!.addService(gattService)) {
            onDiagnostic("BLE_GATT_SERVER", "add_service_failed")
            return
        }
        onDiagnostic("BLE_GATT_SERVER", "opened")
        startAdvertising()
        startScan()
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevices[address] = device
                onDiagnostic("BLE_GATT_SERVER", "connected address=**" + address.takeLast(5))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanup(address)
                onDiagnostic("BLE_GATT_SERVER", "disconnected status=" + status)
                restartScan()
            }
            onPeerCountChanged(peerCount())
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == cccdUuid) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribed.add(device.address)
                    enqueueNotify(device.address, frame(1, hello()))
                    onDiagnostic("BLE_NOTIFY", "enabled address=**" + device.address.takeLast(5))
                } else {
                    subscribed.remove(device.address)
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            flushNotify(device.address)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid != rxUuid) return
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            receiveChunk(device.address, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val address = device.address
            notifying.remove(address)
            notifyQueues[address]?.removeFirstOrNull()
            if (notifyQueues[address]?.isEmpty() == true) notifyQueues.remove(address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDiagnostic("BLE_NOTIFY", "failed status=" + status)
            }
            flushNotify(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adv = adapter.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val id = localId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), id)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settings: AdvertiseSettings) {
                advertising = true
                onDiagnostic("BLE_ADVERTISE", "started")
                onStatus("Mesh активен • BLE готов")
            }
            override fun onStartFailure(errorCode: Int) {
                advertising = false
                onDiagnostic("BLE_ADVERTISE", "failed code=" + errorCode)
            }
        }
        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { onDiagnostic("BLE_ADVERTISE", "exception=" + it.javaClass.simpleName) }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!running || scanning) return
        val s = adapter.bluetoothLeScanner ?: return
        scanner = s
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching {
            s.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            onDiagnostic("BLE_SCAN", "started")
        }.onFailure {
            onDiagnostic("BLE_SCAN", "start_failed=" + it.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handleScan(result) }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onDiagnostic("BLE_SCAN", "failed code=" + errorCode)
            restartScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScan(result: ScanResult) {
        if (!running) return
        val address = result.device.address
        val data = result.scanRecord?.getServiceData(ParcelUuid(serviceUuid)) ?: return
        if (data.size < 8) return
        val peerId = data.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (peerId == localId || peerId.isBlank()) return
        peerNodeIds[address] = peerId

        val initiator = localId.lowercase() < peerId.lowercase()
        onDiagnostic("BLE_SCAN_MATCH", "node=" + peerId + " rssi=" + result.rssi + " initiator=" + initiator)
        if (!initiator || clients.containsKey(address) || connecting.contains(address) || ready.contains(address)) return

        connecting.add(address)
        stopScan()
        connect(result.device)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        val address = device.address
        main.post {
            if (!running) return@post
            val gatt = runCatching {
                if (Build.VERSION.SDK_INT >= 26)
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                else
                    device.connectGatt(context, false, gattCallback)
            }.getOrNull()
            if (gatt == null) {
                connecting.remove(address)
                restartScan()
                return@post
            }
            clients[address] = gatt
            onDiagnostic("BLE_GATT_CONNECT", "start address=**" + address.takeLast(5))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connecting.remove(address)
                onDiagnostic("BLE_GATT_CONNECT", "connected address=**" + address.takeLast(5))
                main.post {
                    val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                    onDiagnostic("BLE_GATT_SERVICES", "discover_started=" + started)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanupClient(address, gatt)
                onDiagnostic("BLE_GATT_CONNECT", "disconnected status=" + status)
                restartScan()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDiagnostic("BLE_GATT_SERVICES", "failed status=" + status)
                cleanupClient(address, gatt)
                restartScan()
                return
            }
            val remote = gatt.getService(serviceUuid)
            val tx = remote?.getCharacteristic(txUuid)
            val rx = remote?.getCharacteristic(rxUuid)
            if (tx == null || rx == null) {
                onDiagnostic("BLE_GATT_SERVICES", "required_characteristics_missing")
                cleanupClient(address, gatt)
                restartScan()
                return
            }
            clientRx[address] = rx
            runCatching { gatt.setCharacteristicNotification(tx, true) }
            val descriptor = tx.getDescriptor(cccdUuid)
            if (descriptor == null) {
                onDiagnostic("BLE_NOTIFY", "cccd_missing")
                cleanupClient(address, gatt)
                restartScan()
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
            onDiagnostic("BLE_NOTIFY", "cccd_write_started=" + started)
            if (!started) {
                cleanupClient(address, gatt)
                restartScan()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != cccdUuid) return
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onDiagnostic("BLE_NOTIFY", "cccd_ready")
                enqueueClient(address, frame(1, hello()))
                flushClient(address)
            } else {
                onDiagnostic("BLE_NOTIFY", "cccd_failed status=" + status)
                cleanupClient(address, gatt)
                restartScan()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != rxUuid) return
            val address = gatt.device.address
            writing.remove(address)
            writeQueues[address]?.removeFirstOrNull()
            if (writeQueues[address]?.isEmpty() == true) writeQueues.remove(address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDiagnostic("BLE_WRITE", "failed status=" + status)
                cleanupClient(address, gatt)
                restartScan()
                return
            }
            flushClient(address)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == txUuid) {
                receiveChunk(gatt.device.address, characteristic.value ?: ByteArray(0))
            }
        }
    }

    private fun frame(type: Int, bytes: ByteArray): List<ByteArray> {
        require(bytes.size <= MAX_FRAME)
        val id = frameCounter.getAndIncrement() and 0xffff
        val payloadSize = 20 - HEADER
        val count = (bytes.size + payloadSize - 1) / payloadSize
        require(count in 1..0xffff)
        return (0 until count).map { index ->
            val from = index * payloadSize
            val to = minOf(bytes.size, from + payloadSize)
            ByteBuffer.allocate(HEADER + to - from)
                .put(MAGIC).put(type.toByte())
                .putShort(id.toShort()).putShort(index.toShort()).putShort(count.toShort())
                .put(bytes, from, to - from).array()
        }
    }

    private fun enqueueClient(address: String, frames: List<ByteArray>) {
        val q = writeQueues.getOrPut(address) { ArrayDeque() }
        synchronized(q) { frames.forEach { q.addLast(it) } }
    }

    private fun enqueueNotify(address: String, frames: List<ByteArray>) {
        val q = notifyQueues.getOrPut(address) { ArrayDeque() }
        synchronized(q) { frames.forEach { q.addLast(it) } }
    }

    @SuppressLint("MissingPermission")
    private fun flushClient(address: String) {
        if (!ready.contains(address) || writing.contains(address)) return
        val gatt = clients[address] ?: return
        val characteristic = clientRx[address] ?: return
        val part = writeQueues[address]?.firstOrNull() ?: return
        writing.add(address)
        val started = runCatching {
            gatt.writeCharacteristic(characteristic, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }.getOrDefault(-1)
        if (started < 0) {
            writing.remove(address)
            onDiagnostic("BLE_WRITE", "start_failed")
            cleanupClient(address, gatt)
            restartScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun flushNotify(address: String) {
        if (!subscribed.contains(address) || notifying.contains(address)) return
        val device = serverDevices[address] ?: return
        val characteristic = service?.getCharacteristic(txUuid) ?: return
        val part = notifyQueues[address]?.firstOrNull() ?: return
        notifying.add(address)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                server?.notifyCharacteristicChanged(device, characteristic, false, part) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = part
                server?.notifyCharacteristicChanged(device, characteristic, false) == true
            }
        }.getOrDefault(false)
        if (!ok) {
            notifying.remove(address)
            notifyQueues[address]?.removeFirstOrNull()
            onDiagnostic("BLE_NOTIFY", "start_failed")
        }
    }

    private fun receiveChunk(address: String, chunk: ByteArray) {
        if (chunk.size < HEADER || chunk[0] != MAGIC) return
        val b = ByteBuffer.wrap(chunk)
        b.get()
        val type = b.get().toInt() and 0xff
        val id = b.short.toInt() and 0xffff
        val index = b.short.toInt() and 0xffff
        val count = b.short.toInt() and 0xffff
        if (count == 0 || index >= count) return
        val now = System.currentTimeMillis()
        val key = address + ":" + id
        val old = assemblies[key]
        val assembly = if (old == null || old.count != count || old.type != type ||
            now - old.createdAt > ASSEMBLY_TIMEOUT) {
            Assembly(type, count, arrayOfNulls(count), now).also { assemblies[key] = it }
        } else old
        assembly.parts[index] = chunk.copyOfRange(HEADER, chunk.size)
        if (assembly.parts.all { it != null }) {
            assemblies.remove(key)
            val bytes = assembly.parts.filterNotNull().fold(ByteArray(0)) { a, p -> a + p }
            handleFrame(address, type, bytes)
        }
        assemblies.entries.removeIf { now - it.value.createdAt > ASSEMBLY_TIMEOUT }
    }

    private fun handleFrame(address: String, type: Int, bytes: ByteArray) {
        if (type == 1) {
            handleHello(address, bytes)
            return
        }
        val packet = MeshPacket.decode(bytes) ?: return
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val text = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                val id = text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                runCatching { queue.remove(UUID.fromString(id)) }
                onDeliveryAck(id)
            } else {
                onMessage(text, packet.sourceId, packet)
                runCatching { router.createDeliveryAck(packet) }.getOrNull()?.let { sendPacket(it) }
            }
            queue.remove(packet.messageId)
        } else if (next != null) {
            queue.enqueue(next)
            sendPacket(next)
        }
    }

    private fun handleHello(address: String, bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.startsWith(HELLO + "|")) return
        val p = text.split("|", limit = 4)
        if (p.size != 4 || p[1].isBlank() || p[1] == localId) return
        val key = runCatching { Base64.decode(p[3], Base64.DEFAULT) }.getOrNull() ?: return
        peerNodeIds[address] = p[1]
        ready.add(address)
        onPeer(p[1], p[2].ifBlank { p[1].take(8) }, key)
        onPeerCountChanged(peerCount())
        onDiagnostic("BLE_READY", "node=" + p[1] + " address=**" + address.takeLast(5))
        flushClient(address)
        flushNotify(address)
        flushQueue()
    }

    private fun sendPacket(packet: MeshPacket) {
        queue.enqueue(packet)
        val bytes = packet.encode()
        for (address in ready.toList()) {
            if (clients.containsKey(address)) {
                enqueueClient(address, frame(2, bytes))
                flushClient(address)
            } else if (serverDevices.containsKey(address)) {
                enqueueNotify(address, frame(2, bytes))
                flushNotify(address)
            }
        }
    }

    fun send(packet: MeshPacket) {
        queue.enqueue(packet)
        flushQueue()
    }

    private fun flushQueue() {
        val entries = queue.snapshot()
        for (address in ready.toList()) {
            val frames = entries.map { frame(2, it.bytes) }.flatten()
            if (clients.containsKey(address)) {
                enqueueClient(address, frames)
                flushClient(address)
            } else if (serverDevices.containsKey(address)) {
                enqueueNotify(address, frames)
                flushNotify(address)
            }
        }
    }

    fun retryPending() { flushQueue() }

    private fun cleanupClient(address: String, gatt: BluetoothGatt) {
        clients.remove(address, gatt)
        clientRx.remove(address)
        connecting.remove(address)
        ready.remove(address)
        writing.remove(address)
        writeQueues.remove(address)
        runCatching { gatt.close() }
        onPeerCountChanged(peerCount())
    }

    private fun cleanup(address: String) {
        ready.remove(address)
        peerNodeIds.remove(address)
        serverDevices.remove(address)
        subscribed.remove(address)
        notifyQueues.remove(address)
        assemblies.keys.removeIf { it.startsWith(address + ":") }
        clients.remove(address)?.let { runCatching { it.close() } }
        clientRx.remove(address)
        connecting.remove(address)
        writing.remove(address)
        writeQueues.remove(address)
    }

    private fun restartScan() {
        if (!running) return
        main.postDelayed({ startScan() }, RECONNECT_DELAY)
    }

    private fun peerCount(): Int = ready.mapNotNull { peerNodeIds[it] }.toSet().size
    fun onlinePeerCount(): Int = peerCount()
    fun isBleTransportReady(): Boolean = ready.isNotEmpty()
    fun isHealthy(): Boolean = running && adapter.isEnabled && advertising && scanning

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        scanning = false
        advertising = false
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        clientRx.clear()
        serverDevices.clear()
        subscribed.clear()
        ready.clear()
        peerNodeIds.clear()
        connecting.clear()
        writing.clear()
        writeQueues.clear()
        notifyQueues.clear()
        notifying.clear()
        assemblies.clear()
        runCatching { server?.close() }
        server = null
        service = null
        scanner = null
        advertiser = null
        advertiseCallback = null
        onPeerCountChanged(0)
        onDiagnostic("BLE_STOP", "stopped")
    }
}
