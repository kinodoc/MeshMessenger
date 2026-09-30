package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Bidirectional BLE GATT transport with a durable store-and-forward queue. */
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
    private val onPeer: (nodeId: String, name: String, publicKey: ByteArray) -> Unit = { _, _, _ -> },
    private val onDeliveryAck: (String) -> Unit = {},
    private val onPeerCountChanged: (Int) -> Unit = {}
) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val peers = mutableMapOf<String, BluetoothGatt>()
    private val connecting = mutableSetOf<String>()
    private val notifyReady = mutableSetOf<String>()
    private val service = MeshProtocol.SERVICE_UUID
    private val rx = MeshProtocol.RX_UUID
    private val tx = MeshProtocol.TX_UUID
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val rxCharacteristic = BluetoothGattCharacteristic(rx, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
    private val txCharacteristic = BluetoothGattCharacteristic(tx, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
    private val descriptor = BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

    companion object {
        private const val FRAGMENT_MAGIC: Byte = 0x4D
        private const val HELLO_MAGIC = "MESH_HELLO_V1"
        private const val FRAGMENT_HEADER_SIZE = 21
        private const val FRAGMENT_CHUNK_SIZE = 180
        private const val MAX_FRAGMENTS = 65535
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L
    }

    private data class Assembly(
        val createdAt: Long,
        val count: Int,
        val parts: Array<ByteArray?>
    )

    private val assemblies = ConcurrentHashMap<String, MutableMap<UUID, Assembly>>()
    private data class WriteTask(val messageId: UUID, val fragments: List<ByteArray>)
    private val writeQueues = mutableMapOf<String, ArrayDeque<WriteTask>>()
    private val writing = mutableSetOf<String>()
    private val helloWriting = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (characteristic.uuid == rx) handleIncomingFragment(device.address, value)
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (descriptor.uuid == cccd && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    notifyReady.add(device.address)
                    sendHelloTo(device)
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState != BluetoothProfile.STATE_CONNECTED) {
                    notifyReady.remove(device.address)
                    assemblies.remove(device.address)
                }
            }
        })
        val gattService = BluetoothGattService(service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        txCharacteristic.addDescriptor(descriptor)
        gattService.addCharacteristic(rxCharacteristic)
        gattService.addCharacteristic(txCharacteristic)
        server?.addService(gattService)

        advertiser = adapter.bluetoothLeAdvertiser
        val adv = advertiser ?: run { onStatus("BLE advertising недоступен"); return }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(android.os.ParcelUuid(service)).build()
        adv.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { onStatus("Mesh активен • BLE relay готов") }
            override fun onStartFailure(errorCode: Int) { onStatus("BLE advertising error: $errorCode") }
        })

        scanner = adapter.bluetoothLeScanner
        val bleScanner = scanner ?: run {
            onStatus("BLE scan недоступен")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                if (uuids.any { it.uuid == service }) connect(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                onStatus("BLE scan error: $errorCode")
            }
        }
        bleScanner.startScan(
            null,
            scanSettings,
            scanCallback
        )
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (device.address == adapter.address || peers.containsKey(device.address) || !connecting.add(device.address)) return
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    peers[device.address] = g
                    onPeerCountChanged(peers.size)
                    g.requestMtu(247)
                    g.discoverServices()
                } else {
                    peers.remove(device.address)
                    connecting.remove(device.address)
                    notifyReady.remove(device.address)
                    assemblies.remove(device.address)
                    writeQueues.remove(device.address)
                    writing.remove(device.address)
                    helloWriting.remove(device.address)
                    onPeerCountChanged(peers.size)
                    g.close()
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val remoteService = g.getService(service) ?: return
                val remoteRx = remoteService.getCharacteristic(rx) ?: return
                val remoteTx = remoteService.getCharacteristic(tx) ?: return
                g.setCharacteristicNotification(remoteTx, true)
                val d = remoteTx.getDescriptor(cccd) ?: return
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
                peers[device.address] = g
                flushQueue()
            }
            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == cccd && status == BluetoothGatt.GATT_SUCCESS) {
                    notifyReady.add(device.address)
                    writeHello(g)
                    flushQueue()
                }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == tx) handleIncomingFragment(device.address, characteristic.value)
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid != rx) return
                val address = device.address
                if (helloWriting.remove(address)) {
                    if (status != BluetoothGatt.GATT_SUCCESS) onStatus("BLE: HELLO не отправлен")
                    flushPeerQueue(address)
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    writing.remove(address)
                    onStatus("BLE: ошибка записи " + status)
                    flushPeerQueue(address)
                    return
                }
                val queueForPeer = writeQueues[address]
                val task = queueForPeer?.firstOrNull()
                if (task != null) {
                    val remaining = task.fragments.drop(1)
                    queueForPeer.removeFirst()
                    if (remaining.isEmpty()) {
                        queue.remove(task.messageId)
                    } else {
                        queueForPeer.addFirst(task.copy(fragments = remaining))
                    }
                    if (queueForPeer.isEmpty()) writeQueues.remove(address)
                }
                writing.remove(address)
                flushPeerQueue(address)
            }
        }
        peers[device.address] = device.connectGatt(context, false, callback)
        onPeerCountChanged(peers.size)
    }

    @SuppressLint("MissingPermission")
    private fun writeHello(gatt: BluetoothGatt) {
        val service = gatt.getService(service) ?: return
        val characteristic = service.getCharacteristic(rx) ?: return
        val key = Base64.getEncoder().encodeToString(localPublicKey)
        characteristic.value = listOf(
            HELLO_MAGIC, localId, localName.take(64), key
        ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        helloWriting.add(gatt.device.address)
        if (!runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)) {
            helloWriting.remove(gatt.device.address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendHelloTo(device: BluetoothDevice) {
        if (!notifyReady.contains(device.address)) return
        val key = Base64.getEncoder().encodeToString(localPublicKey)
        val bytes = listOf(
            HELLO_MAGIC, localId, localName.take(64), key
        ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
        val characteristic = txCharacteristic.apply { value = bytes }
        runCatching {
            server?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun handleIncomingFragment(from: String, bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        if (text.startsWith(HELLO_MAGIC + "|")) {
            val parts = text.split("|", limit = 4)
            if (parts.size == 4 && parts[1].isNotBlank()) {
                val key = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull()
                if (key != null && key.isNotEmpty()) {
                    onPeer(parts[1], parts[2].ifBlank { parts[1].take(8) }, key)
                }
            }
            return
        }
        val packetBytes = acceptFragment(from, bytes) ?: return
        handleIncoming(from, packetBytes)
    }

    private fun acceptFragment(from: String, bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        if (bytes[0] != FRAGMENT_MAGIC) return bytes
        if (bytes.size < FRAGMENT_HEADER_SIZE) return null

        return runCatching {
            val b = ByteBuffer.wrap(bytes)
            b.get()
            val id = UUID(b.long, b.long)
            val index = b.short.toInt() and 0xffff
            val count = b.short.toInt() and 0xffff

            require(count in 1..MAX_FRAGMENTS)
            require(index in 0 until count)
            require(bytes.size <= FRAGMENT_HEADER_SIZE + FRAGMENT_CHUNK_SIZE)

            val payload = ByteArray(b.remaining())
            b.get(payload)

            val byMessage = assemblies.getOrPut(from) { mutableMapOf() }
            val now = System.currentTimeMillis()
            byMessage.entries.removeIf { now - it.value.createdAt > REASSEMBLY_TIMEOUT_MS }

            val assembly = byMessage[id] ?: Assembly(now, count, arrayOfNulls(count)).also {
                byMessage[id] = it
            }

            require(assembly.count == count)
            assembly.parts[index] = payload

            if (assembly.parts.any { it == null }) {
                null
            } else {
                byMessage.remove(id)
                val total = assembly.parts.sumOf { it!!.size }
                ByteArray(total).also { result ->
                    var offset = 0
                    assembly.parts.forEach { part ->
                        val p = part!!
                        p.copyInto(result, offset)
                        offset += p.size
                    }
                }
            }
        }.getOrNull()
    }

    private fun fragment(bytes: ByteArray): List<ByteArray> {
        val id = MeshPacket.decode(bytes)?.messageId
            ?: throw IllegalArgumentException("Invalid mesh packet")
        val count = (bytes.size + FRAGMENT_CHUNK_SIZE - 1) / FRAGMENT_CHUNK_SIZE
        require(count in 1..MAX_FRAGMENTS) { "Mesh packet requires too many BLE fragments" }

        return bytes.asList().chunked(FRAGMENT_CHUNK_SIZE).mapIndexed { index, chunk ->
            ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + chunk.size)
                .put(FRAGMENT_MAGIC)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .putShort(index.toShort())
                .putShort(count.toShort())
                .put(chunk.toByteArray())
                .array()
        }
    }

    private fun handleIncoming(from: String, bytes: ByteArray) {
        val packet = MeshPacket.decode(bytes) ?: return
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val text = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                onDeliveryAck(text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX))
            } else {
                onMessage(text, packet.sourceId, packet)
                val ack = runCatching { router.createDeliveryAck(packet) }.getOrNull()
                if (ack != null) broadcast(ack.encode())
            }
            queue.remove(packet.messageId)
        } else if (next != null) {
            queue.enqueue(next)
            broadcast(next.encode(), except = from)
        }
    }

    @SuppressLint("MissingPermission")
    fun send(packet: MeshPacket) {
        queue.enqueue(packet)
        flushQueue()
    }

    @SuppressLint("MissingPermission")
    private fun flushQueue() {
        val ready = peers.filterKeys { notifyReady.contains(it) }.keys.toList()
        for (address in ready) {
            for (entry in queue.snapshot()) {
                val fragments = runCatching { fragment(entry.bytes) }.getOrNull() ?: continue
                val q = writeQueues.getOrPut(address) { ArrayDeque() }
                if (q.none { it.messageId == entry.id }) {
                    q.addLast(WriteTask(entry.id, fragments))
                }
            }
            flushPeerQueue(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun flushPeerQueue(address: String) {
        if (writing.contains(address)) return
        val gatt = peers[address] ?: return
        if (!notifyReady.contains(address)) return
        val task = writeQueues[address]?.firstOrNull() ?: return
        val part = task.fragments.firstOrNull() ?: return
        val characteristic = gatt.getService(service)?.getCharacteristic(rx) ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = part
        writing.add(address)
        if (!runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)) {
            writing.remove(address)
            onStatus("BLE: запись занята")
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcast(bytes: ByteArray, except: String? = null) {
        val fragments = runCatching { fragment(bytes) }.getOrNull() ?: return
        for ((address, gatt) in peers.toMap()) {
            if (address == except || !notifyReady.contains(address)) continue
            for (part in fragments) {
                val characteristic = txCharacteristic.apply { value = part }
                runCatching { server?.notifyCharacteristicChanged(gatt.device, characteristic, false) }
            }
        }
    }

    fun stop() {
        scanner?.let { sc -> scanCallback?.let { cb -> sc.stopScan(cb) } }
        scanCallback = null
        scanner = null
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        notifyReady.clear()
        assemblies.clear()
        onPeerCountChanged(0)
        server?.close()
        server = null
    }
}
