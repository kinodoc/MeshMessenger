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
import android.util.Log
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
    private val onPeerCountChanged: (Int) -> Unit = {},
    private val onDiagnostic: (String, String) -> Unit = { _, _ -> }
) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    @Volatile private var gattServerReady = false
    @Volatile private var advertisingStarted = false
    @Volatile private var scanningStarted = false
    @Volatile private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanRetryCount = 0
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val peers = mutableMapOf<String, BluetoothGatt>()
    private val serverClients = mutableMapOf<String, BluetoothDevice>()
    private val connecting = mutableSetOf<String>()
    // BLE MAC addresses may rotate. Track the application's stable Node ID too,
    // so one physical peer cannot create several GATT connections/counts.
    private val connectingNodeIds = ConcurrentHashMap.newKeySet<String>()
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    // Presence is keyed by the stable Mesh Node ID, not by BLE address or GATT connection.
    // A phone may advertise many times and may rotate its BLE address; it is still one peer.
    private val peerLastSeenAt = ConcurrentHashMap<String, Long>()
    private val notifyReady = mutableSetOf<String>()
    private val service = MeshProtocol.SERVICE_UUID
    private val rx = MeshProtocol.RX_UUID
    private val tx = MeshProtocol.TX_UUID
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val rxCharacteristic = BluetoothGattCharacteristic(rx, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
    private val txCharacteristic = BluetoothGattCharacteristic(tx, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
    private val descriptor = BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

    companion object {
        private const val TAG = "MeshGattDiag"
        private const val FRAGMENT_MAGIC: Byte = 0x4D
        private const val HELLO_MAGIC = "MESH_HELLO_V1"
        private const val FRAGMENT_HEADER_SIZE = 21
        private const val FRAGMENT_CHUNK_SIZE = 180
        private const val MAX_FRAGMENTS = 65535
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L
        private const val DELIVERY_RETRY_MS = 10_000L
        private const val PEER_PRESENCE_TTL_MS = 30_000L
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
    private val notificationQueues = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val notifying = mutableSetOf<String>()
    // A successful GATT write only confirms the characteristic write callback.
    // Keep the durable packet until the destination sends the application ACK.
    private val awaitingDelivery = mutableMapOf<String, MutableMap<UUID, Long>>()

    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid == this@MeshGattNode.service && status == BluetoothGatt.GATT_SUCCESS) {
                    gattServerReady = true
                    startBleAdvertisingAndScan()
                } else if (service.uuid == this@MeshGattNode.service) {
                    onStatus("BLE: сервис GATT не запущен: " + status)
                }
            }

            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                Log.d(TAG, "gatt_write_rx peer=${device.address.takeLast(5)} uuid=${characteristic.uuid} bytes=${value.size} prepared=$preparedWrite response=$responseNeeded offset=$offset prefix=${value.take(8).joinToString("") { "%02x".format(it) }}")
                if (characteristic.uuid == rx) handleIncomingFragment(device.address, value)
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (descriptor.uuid == cccd && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    notifyReady.add(device.address)
                    sendHelloTo(device)
                    flushQueue()
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    serverClients[device.address] = device
                } else {
                    serverClients.remove(device.address)
                    peerNodeIds.remove(device.address)
                    notifyReady.remove(device.address)
                    assemblies.remove(device.address)
                    notificationQueues.remove(device.address)
                    notifying.remove(device.address)
                    awaitingDelivery.remove(device.address)
                }
                onPeerCountChanged(peerCount())
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                val address = device.address
                notifying.remove(address)
                val q = notificationQueues[address]
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    q?.removeFirstOrNull()
                    onDiagnostic("BLE_NOTIFY_TX", "success address=**${address.takeLast(5)} remaining=${q?.size ?: 0}")
                } else {
                    onDiagnostic("BLE_NOTIFY_TX", "failed address=**${address.takeLast(5)} status=$status")
                    onStatus("BLE: ошибка уведомления $status")
                    // Drop only the failed fragment; the next queued fragment can still proceed.
                    q?.removeFirstOrNull()
                }
                if (q?.isEmpty() == true) notificationQueues.remove(address)
                flushNotificationQueue(address)
            }
        })
        val gattService = BluetoothGattService(service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        txCharacteristic.addDescriptor(descriptor)
        gattService.addCharacteristic(rxCharacteristic)
        gattService.addCharacteristic(txCharacteristic)
        server?.addService(gattService)
        onDiagnostic("BLE_GATT_SERVER", "opened")

        // Advertising starts from onServiceAdded() so clients never discover an incomplete GATT server.
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertisingAndScan() {
        if (advertiser != null || scanner != null) return

        advertiser = adapter.bluetoothLeAdvertiser
        val adv = advertiser ?: run { onStatus("BLE advertising недоступен"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // Publish the stable Mesh Node ID in service data. Android exposes the
        // scan record service data independently from the BLE address, allowing
        // scanners to deduplicate the same phone before opening GATT.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(service))
            .build()
        // Keep the 128-bit service UUID in the primary advertisement and put the
        // stable Node ID into the scan response. A connectable legacy BLE
        // advertisement is limited to 31 bytes, so both cannot safely fit there.
        // Node IDs are 16 hex characters (8 bytes). Encode the ID as raw bytes
        // instead of UTF-8 text: the 128-bit service UUID plus 8 bytes of node
        // data fit in a legacy 31-byte BLE scan response. Sending the 16 ASCII
        // characters made the payload too large and caused ADVERTISE_FAILED_DATA_TOO_LARGE (1).
        val nodeIdBytes = localId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(service), nodeIdBytes)
            .build()

        var retriedWithoutScanResponse = false
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertisingStarted = true
                onDiagnostic(
                    "BLE_ADVERTISE",
                    if (retriedWithoutScanResponse) {
                        "started mode=BALANCED tx=MEDIUM scanResponse=off"
                    } else {
                        "started mode=BALANCED tx=MEDIUM scanResponse=on"
                    }
                )
                onStatus("Mesh активен • BLE relay готов")
            }
            override fun onStartFailure(errorCode: Int) {
                advertisingStarted = false
                onDiagnostic("BLE_ADVERTISE", "failed code=" + errorCode)
                // Some Android 11/OEM Bluetooth stacks reject a perfectly valid
                // 128-bit service-data scan response as "data too large" (1).
                // The service UUID is already in the primary advertisement, so
                // the scan response is optional. Retry once without it; the peer
                // Node ID will be learned from the GATT HELLO after connection.
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE &&
                    !retriedWithoutScanResponse
                ) {
                    retriedWithoutScanResponse = true
                    onDiagnostic("BLE_ADVERTISE", "retry_without_scan_response")
                    runCatching {
                        adv.startAdvertising(settings, data, advertiseCallback!!)
                    }.onFailure {
                        onDiagnostic("BLE_ADVERTISE", "fallback_exception=" + it.javaClass.simpleName)
                    }
                    return
                }
                onStatus("BLE advertising error: " + errorCode)
            }
        }
        runCatching { adv.startAdvertising(settings, data, scanResponse, advertiseCallback!!) }
            .onFailure { onDiagnostic("BLE_ADVERTISE", "start_exception=" + it.javaClass.simpleName) }

        scanner = adapter.bluetoothLeScanner
        val bleScanner = scanner ?: run {
            onStatus("BLE scan недоступен")
            return
        }
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val uuids = record.serviceUuids.orEmpty()
                if (!uuids.any { it.uuid == service }) return
                val advertisedNodeId = record.getServiceData(ParcelUuid(service))
                    ?.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    ?.takeIf { it.length == 16 }
                if (advertisedNodeId == localId) return
                connect(result.device, advertisedNodeId?.takeIf { it.isNotBlank() })
            }
            override fun onScanFailed(errorCode: Int) {
                scanningStarted = false
                onDiagnostic("BLE_SCAN", "failed code=" + errorCode)
                onStatus("BLE scan error: " + errorCode)
                scheduleScanRestart("callback-$errorCode")
            }
        }
        runCatching { bleScanner.startScan(null, scanSettings, scanCallback) }
            .onSuccess { scanningStarted = true; scanRetryCount = 0; onDiagnostic("BLE_SCAN", "started mode=BALANCED") }
            .onFailure {
                scanningStarted = false
                onDiagnostic("BLE_SCAN", "start_exception=" + it.javaClass.simpleName)
                scheduleScanRestart("start-exception")
            }
    }

    /** Recover BLE discovery after transient Android/OEM scanner failures. */
    @SuppressLint("MissingPermission")
    private fun scheduleScanRestart(reason: String) {
        if (!running) return
        val attempt = (++scanRetryCount).coerceAtMost(6)
        val delayMs = (2_000L * attempt).coerceAtMost(15_000L)
        onDiagnostic("BLE_SCAN", "retry_scheduled attempt=$attempt delay_ms=$delayMs reason=$reason")
        mainHandler.removeCallbacksAndMessages("ble-scan-retry")
        mainHandler.postAtTime({
            if (!running || scanningStarted) return@postAtTime
            val currentScanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
            val callback = scanCallback
            if (currentScanner == null || callback == null || !adapter.isEnabled) {
                scheduleScanRestart("scanner-unavailable")
                return@postAtTime
            }
            scanner = currentScanner
            runCatching {
                currentScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), callback)
            }.onSuccess {
                scanningStarted = true
                scanRetryCount = 0
                onDiagnostic("BLE_SCAN", "restarted")
            }.onFailure {
                scanningStarted = false
                onDiagnostic("BLE_SCAN", "restart_exception=" + it.javaClass.simpleName)
                scheduleScanRestart("restart-exception")
            }
        }, "ble-scan-retry", android.os.SystemClock.uptimeMillis() + delayMs)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, advertisedNodeId: String? = null) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val stableId = advertisedNodeId?.trim().orEmpty()
        if (device.address == adapter.address ||
            peers.containsKey(device.address) ||
            connecting.contains(device.address) ||
            (stableId.isNotBlank() && (peerNodeIds.values.contains(stableId) || !connectingNodeIds.add(stableId)))) return
        connecting.add(device.address)
        onDiagnostic("BLE_GATT_CONNECT", "start address=**" + device.address.takeLast(5) + ",node=" + stableId)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                onDiagnostic("BLE_GATT_STATE", "address=**" + device.address.takeLast(5) + ",status=" + status + ",state=" + newState)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    peers[device.address] = g
                    onPeerCountChanged(peerCount())
                    val mtuRequested = runCatching { g.requestMtu(247) }.getOrDefault(false)
                    if (!mtuRequested) {
                        onDiagnostic("BLE_GATT_MTU", "request_failed; discover_services_without_mtu")
                        g.discoverServices()
                    }
                } else {
                    peers.remove(device.address)
                    connecting.remove(device.address)
                    if (stableId.isNotBlank()) connectingNodeIds.remove(stableId)
                    peerNodeIds.remove(device.address)
                    notifyReady.remove(device.address)
                    assemblies.remove(device.address)
                    writeQueues.remove(device.address)
                    writing.remove(device.address)
                    helloWriting.remove(device.address)
                    notificationQueues.remove(device.address)
                    notifying.remove(device.address)
                    awaitingDelivery.remove(device.address)
                    onPeerCountChanged(peerCount())
                    g.close()
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                onDiagnostic("BLE_GATT_MTU", "mtu=$mtu status=$status")
                // Continue with the negotiated (or default) MTU; discover only once.
                runCatching { g.discoverServices() }.onFailure {
                    onDiagnostic("BLE_GATT_DISCOVERY", "start_exception=" + it.javaClass.simpleName)
                    g.disconnect()
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onStatus("BLE: ошибка обнаружения сервисов $status")
                    g.disconnect()
                    return
                }
                val remoteService = g.getService(service) ?: run {
                    onStatus("BLE: сервис Mesh не найден")
                    g.disconnect()
                    return
                }
                val remoteRx = remoteService.getCharacteristic(rx) ?: run {
                    onStatus("BLE: RX characteristic не найден")
                    g.disconnect()
                    return
                }
                val remoteTx = remoteService.getCharacteristic(tx) ?: run {
                    onStatus("BLE: TX characteristic не найден")
                    g.disconnect()
                    return
                }
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
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                Log.d(TAG, "gatt_notify_rx peer=${device.address.takeLast(5)} uuid=${characteristic.uuid} bytes=${value.size} prefix=${value.take(8).joinToString("") { "%02x".format(it) }}")
                if (characteristic.uuid == tx) handleIncomingFragment(device.address, value)
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
                    onDiagnostic("BLE_WRITE_TX", "failed address=**${address.takeLast(5)} status=$status")
                    writing.remove(address)
                    onStatus("BLE: ошибка записи " + status)
                    flushPeerQueue(address)
                    return
                }
                onDiagnostic("BLE_WRITE_TX", "success address=**${address.takeLast(5)}")
                val queueForPeer = writeQueues[address]
                val task = queueForPeer?.firstOrNull()
                if (task != null) {
                    val remaining = task.fragments.drop(1)
                    queueForPeer.removeFirst()
                    if (remaining.isEmpty()) {
                        if (queue.snapshot().any { it.id == task.messageId }) {
                            awaitingDelivery.getOrPut(address) { mutableMapOf() }[task.messageId] = System.currentTimeMillis()
                        }
                    } else {
                        queueForPeer.addFirst(task.copy(fragments = remaining))
                    }
                    if (queueForPeer.isEmpty()) writeQueues.remove(address)
                }
                writing.remove(address)
                flushPeerQueue(address)
            }
        }
        val gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) {
            connecting.remove(device.address)
            if (stableId.isNotBlank()) connectingNodeIds.remove(stableId)
            return
        }
        peers[device.address] = gatt
        onPeerCountChanged(peerCount())
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
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(
                    characteristic,
                    characteristic.value.copyOf(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!started) helloWriting.remove(gatt.device.address)
    }

    @SuppressLint("MissingPermission")
    private fun sendHelloTo(device: BluetoothDevice) {
        if (!notifyReady.contains(device.address)) return
        val key = Base64.getEncoder().encodeToString(localPublicKey)
        val bytes = listOf(
            HELLO_MAGIC, localId, localName.take(64), key
        ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
        enqueueNotification(device, bytes)
    }

    private fun handleIncomingFragment(from: String, bytes: ByteArray) {
        Log.d(TAG, "fragment_rx peer=${from.takeLast(5)} bytes=${bytes.size} prefix=${bytes.take(8).joinToString("") { "%02x".format(it) }}")
        val text = String(bytes, StandardCharsets.UTF_8)
        if (text.startsWith(HELLO_MAGIC + "|")) {
            val parts = text.split("|", limit = 4)
            if (parts.size == 4 && parts[1].isNotBlank()) {
                val peerId = parts[1].trim()
                if (peerId == localId) return
                peerNodeIds[from] = peerId
                val duplicateAddress = peerNodeIds.entries.firstOrNull { it.value == peerId && it.key != from }?.key
                if (duplicateAddress != null) {
                    // Same application identity reached us through a rotated BLE
                    // address. Keep the first connection and close this duplicate.
                    onDiagnostic("BLE_DUPLICATE", "node=" + peerId + ",address=**" + from.takeLast(5))
                    peers[from]?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
                    serverClients[from]?.let { runCatching { server?.cancelConnection(it) } }
                    return
                }
                val key = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull()
                if (key != null && key.isNotEmpty()) {
                    peerLastSeenAt[peerId] = System.currentTimeMillis()
                    onPeer(peerId, parts[2].ifBlank { peerId.take(8) }, key)
                    onPeerCountChanged(peerCount())
                }
            }
            return
        }
        val packetBytes = acceptFragment(from, bytes) ?: run { Log.d(TAG, "fragment_pending_or_rejected peer=${from.takeLast(5)} bytes=${bytes.size}"); return }
        Log.d(TAG, "packet_reassembled peer=${from.takeLast(5)} bytes=${packetBytes.size}")
        onDiagnostic("BLE_PACKET_REASSEMBLED", "peer=**${from.takeLast(5)} bytes=${packetBytes.size}")
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
            Log.d(TAG, "fragment_accepted peer=${from.takeLast(5)} id=$id index=$index count=$count payload=${payload.size}")

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
        val packet = MeshPacket.decode(bytes) ?: run { Log.w(TAG, "packet_decode_failed peer=${from.takeLast(5)} bytes=${bytes.size}"); onDiagnostic("BLE_PACKET_DECODE", "failed peer=**${from.takeLast(5)} bytes=${bytes.size}"); return }
        onDiagnostic("BLE_PACKET_RX", "peer=**${from.takeLast(5)} id=${packet.messageId} dst=${packet.destinationId.take(8)}")
        // Do not treat ordinary messages as presence. Presence is refreshed only
        // by the explicit BLE HELLO/presence exchange in the GATT path.
        Log.d(TAG, "packet_decoded peer=${from.takeLast(5)} id=${packet.messageId} src=${packet.sourceId.take(8)} dst=${packet.destinationId.take(8)} local=${packet.destinationId == localId}")
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val text = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                val deliveredId = text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                Log.i(TAG, "delivery_ack_received ackPacket=${packet.messageId} deliveredId=$deliveredId src=${packet.sourceId.take(8)}")
                markDelivered(deliveredId)
                onDeliveryAck(deliveredId)
            } else {
                Log.d(TAG, "message_delivered_to_callback id=${packet.messageId} src=${packet.sourceId.take(8)} textBytes=${text.toByteArray(StandardCharsets.UTF_8).size}")
                onMessage(text, packet.sourceId, packet)
                val ack = runCatching { router.createDeliveryAck(packet) }.onFailure {
                    Log.e(TAG, "delivery_ack_create_failed for=${packet.messageId}", it)
                }.getOrNull()
                if (ack != null) {
                    Log.i(TAG, "delivery_ack_created for=${packet.messageId} ack=${ack.messageId} peers=${peerCount()} serverClients=${serverClients.size} ready=${notifyReady.size}")
                    broadcast(ack.encode())
                }
            }
            queue.remove(packet.messageId)
        } else if (next != null) {
            queue.enqueue(next)
            broadcast(next.encode(), except = from)
        }
    }

    private fun markDelivered(messageId: String) {
        val id = runCatching { UUID.fromString(messageId) }.getOrNull() ?: return
        queue.remove(id)
        for (pending in awaitingDelivery.values) pending.remove(id)
        awaitingDelivery.entries.removeIf { it.value.isEmpty() }
    }

    @SuppressLint("MissingPermission")
    fun send(packet: MeshPacket) {
        Log.d(TAG, "send_enqueue id=${packet.messageId} src=${packet.sourceId.take(8)} dst=${packet.destinationId.take(8)} peers=${peerCount()}")
        queue.enqueue(packet)
        flushQueue()
    }

    /**
     * Re-attempts packets that are still waiting for a delivery acknowledgement.
     * The durable queue is intentionally kept until the destination confirms receipt.
     */
    @SuppressLint("MissingPermission")
    fun retryPending() {
        onPeerCountChanged(peerCount())
        if (peers.isEmpty() && serverClients.isEmpty()) return
        refreshPeerPresence()
        flushQueue()
    }

    /** Refresh application-level presence while GATT connections remain alive. */
    @SuppressLint("MissingPermission")
    private fun refreshPeerPresence() {
        for ((address, gatt) in peers.toMap()) {
            if (!notifyReady.contains(address) || writing.contains(address) || helloWriting.contains(address)) continue
            onDiagnostic("BLE_PRESENCE_TX", "role=central address=**${address.takeLast(5)}")
            writeHello(gatt)
        }
        for ((address, device) in serverClients.toMap()) {
            if (!notifyReady.contains(address)) continue
            onDiagnostic("BLE_PRESENCE_TX", "role=server address=**${address.takeLast(5)}")
            sendHelloTo(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun flushQueue() {
        val entries = queue.snapshot()

        // A peer may exist only on the GATT-server side. The previous code
        // tracked those clients but never sent the durable outgoing queue to them.
        val serverOnly = serverClients.keys.filter {
            !peers.containsKey(it) && notifyReady.contains(it)
        }

        for (address in serverOnly) {
            val device = serverClients[address] ?: continue
            if (notificationQueues[address]?.isNotEmpty() == true) continue
            for (entry in entries) {
                val waitingSince = awaitingDelivery[address]?.get(entry.id)
                if (waitingSince != null && System.currentTimeMillis() - waitingSince < DELIVERY_RETRY_MS) continue
                if (waitingSince != null) awaitingDelivery[address]?.remove(entry.id)
                val fragments = runCatching { fragment(entry.bytes) }.getOrNull() ?: continue
                fragments.forEach { enqueueNotification(device, it) }
                awaitingDelivery.getOrPut(address) { mutableMapOf() }[entry.id] = System.currentTimeMillis()
            }
        }

        val ready = peers.filterKeys { notifyReady.contains(it) }.keys.toList()
        for (address in ready) {
            for (entry in entries) {
                val waitingSince = awaitingDelivery[address]?.get(entry.id)
                if (waitingSince != null && System.currentTimeMillis() - waitingSince < DELIVERY_RETRY_MS) continue
                if (waitingSince != null) awaitingDelivery[address]?.remove(entry.id)
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
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(
                    characteristic,
                    part.copyOf(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!started) {
            writing.remove(address)
            onStatus("BLE: запись занята")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueNotification(device: BluetoothDevice, bytes: ByteArray) {
        val address = device.address
        if (!notifyReady.contains(address)) return
        notificationQueues.getOrPut(address) { ArrayDeque() }.addLast(bytes.copyOf())
        flushNotificationQueue(address)
    }

    @SuppressLint("MissingPermission")
    private fun flushNotificationQueue(address: String) {
        if (notifying.contains(address) || !notifyReady.contains(address)) return
        val device = peers[address]?.device ?: serverClients[address] ?: return
        val bytes = notificationQueues[address]?.firstOrNull() ?: return
        val srv = server ?: return
        val characteristic = txCharacteristic

        val started = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(device, characteristic, false, bytes) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = bytes
                srv.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.getOrDefault(false)

        if (started) {
            notifying.add(address)
        } else {
            onStatus("BLE: уведомление не запущено")
            notificationQueues[address]?.removeFirstOrNull()
            if (notificationQueues[address]?.isEmpty() == true) notificationQueues.remove(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcast(bytes: ByteArray, except: String? = null) {
        val fragments = runCatching { fragment(bytes) }.getOrNull() ?: return
        val devices = LinkedHashMap<String, BluetoothDevice>()
        for ((address, gatt) in peers.toMap()) devices[address] = gatt.device
        for ((address, device) in serverClients.toMap()) devices[address] = device
        for ((address, device) in devices) {
            if (address == except || !notifyReady.contains(address)) continue
            for (part in fragments) enqueueNotification(device, part)
        }
    }

    private fun peerCount(): Int {
        val now = System.currentTimeMillis()
        peerLastSeenAt.entries.removeIf { now - it.value > PEER_PRESENCE_TTL_MS }
        // A live GATT connection is itself proof that BLE is connected, even if
        // the peer's HELLO/presence packet has not arrived (or has expired).
        // Count both central and peripheral connections, deduplicated by address.
        val connectedAddresses = peers.keys.toMutableSet().apply { addAll(serverClients.keys) }
        return maxOf(connectedAddresses.size, peerLastSeenAt.count { now - it.value <= PEER_PRESENCE_TTL_MS })
    }

    fun onlinePeerCount(): Int = peerCount()

    /** True only when the GATT server, BLE advertiser and BLE scanner are all running. */
    fun isHealthy(): Boolean = runCatching {
        adapter.isEnabled && gattServerReady && server != null &&
            advertisingStarted && advertiser != null &&
            scanningStarted && scanner != null
    }.getOrDefault(false)

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages("ble-scan-retry")
        gattServerReady = false
        advertisingStarted = false
        scanningStarted = false
        // Android may turn the adapter off before the watchdog calls stop().
        // BLE scanner APIs throw IllegalStateException in that state; shutdown
        // must remain best-effort and must never crash the process.
        if (runCatching { adapter.isEnabled }.getOrDefault(false)) {
            scanner?.let { sc ->
                scanCallback?.let { cb ->
                    runCatching { sc.stopScan(cb) }
                        .onFailure { onDiagnostic("BLE_SCAN", "stop_failed ${it.javaClass.simpleName}") }
                }
            }
        }
        scanCallback = null
        scanner = null
        scanningStarted = false
        val adv = advertiser
        val callback = advertiseCallback
        if (adv != null && callback != null && runCatching { adapter.isEnabled }.getOrDefault(false)) {
            runCatching { adv.stopAdvertising(callback) }
                .onSuccess { onDiagnostic("BLE_ADVERTISE", "stopped") }
                .onFailure { onDiagnostic("BLE_ADVERTISE", "stop_failed ${it.javaClass.simpleName}") }
        }
        advertiseCallback = null
        advertiser = null
        advertisingStarted = false
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        serverClients.clear()
        connecting.clear()
        connectingNodeIds.clear()
        peerNodeIds.clear()
        peerLastSeenAt.clear()
        notifyReady.clear()
        notificationQueues.clear()
        notifying.clear()
        awaitingDelivery.clear()
        assemblies.clear()
        onPeerCountChanged(0)
        server?.close()
        server = null
        onDiagnostic("BLE_STOP", "all_gatt_closed")
    }
}
