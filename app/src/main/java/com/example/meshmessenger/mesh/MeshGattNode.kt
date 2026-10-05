package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    // Lightweight counters distinguish missing scan callbacks from a service UUID mismatch.
    private val serverClients = mutableMapOf<String, BluetoothDevice>()
    // BLE MAC addresses may rotate. Track the application's stable Node ID too,
    // so one physical peer cannot create several GATT connections/counts.
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    // Presence is keyed by the stable Mesh Node ID, not by BLE address or GATT connection.
    // A phone may advertise many times and may rotate its BLE address; it is still one peer.
    private val peerLastSeenAt = ConcurrentHashMap<String, Long>()
    // CCCD enabled is not the same as application-ready: HELLO must also complete.
    private val notifyReady = mutableSetOf<String>()
    private val gattReady = mutableSetOf<String>()
    private val service = MeshProtocol.SERVICE_UUID
    private val localRfcommUuid = UUID.nameUUIDFromBytes(("MeshMessenger-RFCOMM:" + localId).toByteArray(StandardCharsets.UTF_8))
    private val rx = MeshProtocol.RX_UUID
    private val tx = MeshProtocol.TX_UUID
    private val allocator = MeshProtocol.ALLOCATOR_UUID
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val l2capTransport = MeshL2capTransport(
        context = context,
        adapter = adapter,
        localId = localId,
        helloPayload = {
            val key = Base64.getEncoder().encodeToString(localPublicKey)
            listOf(HELLO_MAGIC, localId, localName.take(64), key)
                .joinToString("|").toByteArray(StandardCharsets.UTF_8)
        },
        onDiagnostic = onDiagnostic,
        onFrame = { address, bytes -> handleIncomingFragment(address, bytes) },
        onConnected = { address ->
            onDiagnostic("BLE_L2CAP", "transport_connected address=**" + address.takeLast(5))
        },
        onDisconnected = { address ->
            gattReady.remove(address)
            peerNodeIds.remove(address)
            onPeerCountChanged(peerCount())
            onDiagnostic("BLE_L2CAP", "transport_disconnected address=**" + address.takeLast(5))
        }
    )
    private val rfcommTransport = MeshRfcommTransport(
        context = context,
        adapter = adapter,
        serviceUuid = localRfcommUuid,
        helloPayload = {
            val key = Base64.getEncoder().encodeToString(localPublicKey)
            listOf(HELLO_MAGIC, localId, localName.take(64), key)
                .joinToString("|").toByteArray(StandardCharsets.UTF_8)
        },
        onDiagnostic = onDiagnostic,
        onFrame = { address, bytes -> handleIncomingFragment(address, bytes) },
        onConnected = { address ->
            onDiagnostic("BLE_RFCOMM", "transport_connected address=**" + address.takeLast(5))
        },
        onDisconnected = { address ->
            peerNodeIds.remove(address)
            peerLastSeenAt.entries.removeIf { it.value <= System.currentTimeMillis() }
            onPeerCountChanged(peerCount())
            onDiagnostic("BLE_RFCOMM", "transport_disconnected address=**" + address.takeLast(5))
        }
    )
    private val rxCharacteristic = BluetoothGattCharacteristic(rx, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
    private val txCharacteristic = BluetoothGattCharacteristic(tx, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
    private val allocatorCharacteristic = BluetoothGattCharacteristic(allocator, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
    private val rfcommInfoCharacteristic = BluetoothGattCharacteristic(MeshProtocol.RFCOMM_INFO_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
    private val descriptor = BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
    private val allocatorSessions = ConcurrentHashMap<String, ByteArray>()
    private val blessedCentral = MeshBlessedCentral(
        context = context,
        localId = localId,
        serviceUuid = service,
        rxUuid = rx,
        txUuid = tx,
        allocatorUuid = allocator,
        helloPayload = {
            val key = Base64.getEncoder().encodeToString(localPublicKey)
            listOf(HELLO_MAGIC, localId, localName.take(64), key)
                .joinToString("|").toByteArray(StandardCharsets.UTF_8)
        },
        onDiagnostic = onDiagnostic,
        onFragment = { address, bytes -> handleIncomingFragment(address, bytes) },
        onL2capPeer = { peripheral, _, remotePsm ->
            if (l2capTransport.isSupported()) {
                val device = adapter.getRemoteDevice(peripheral.address)
                l2capTransport.connect(device, remotePsm)
            }
        },
        onRfcommPeer = { _, classicAddress, peerUuid ->
            rfcommTransport.connect(classicAddress, peerUuid)
        },
        onReady = { address, ready ->
            if (ready) {
                gattReady.add(address)
                onDiagnostic("BLE_BLESSED_READY", "ready=true address=**" + address.takeLast(5))
                onPeerCountChanged(peerCount())
                flushQueue()
            } else {
                gattReady.remove(address)
                writing.remove(address)
                onDiagnostic("BLE_BLESSED_READY", "ready=false address=**" + address.takeLast(5))
                onPeerCountChanged(peerCount())
            }
        },
        onWrite = { address, ok ->
            if (writing.remove(address)) {
                writeTimeouts.remove(address)?.cancel(false)
            val queueForPeer = writeQueues[address]
            val task = queueForPeer?.firstOrNull()
            if (!ok) {
                onDiagnostic("BLE_BLESSED_WRITE", "packet_failed address=**" + address.takeLast(5))
                queueForPeer?.removeFirstOrNull()
            } else if (task != null) {
                val remaining = task.fragments.drop(1)
                queueForPeer.removeFirst()
                if (remaining.isEmpty()) {
                    if (queue.snapshot().any { it.id == task.messageId }) {
                        awaitingDelivery.getOrPut(address) { mutableMapOf() }[task.messageId] = System.currentTimeMillis()
                    }
                } else queueForPeer.addFirst(task.copy(fragments = remaining))
            }
            if (queueForPeer?.isEmpty() == true) writeQueues.remove(address)
                flushPeerQueue(address)
            }
        }
    )

    companion object {
        private const val TAG = "MeshGattDiag"
        private const val FRAGMENT_MAGIC: Byte = 0x4D
        private const val HELLO_MAGIC = "MESH_HELLO_V1"
        private const val FRAGMENT_HEADER_SIZE = 21
        private const val FRAGMENT_CHUNK_SIZE = 180
        private const val MAX_FRAGMENTS = 65535
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L
        private const val DELIVERY_RETRY_MS = 10_000L
        private const val GATT_WRITE_TIMEOUT_MS = 5_000L
        private const val GATT_MTU_TIMEOUT_MS = 3_500L
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
    private val writeTimeouts = ConcurrentHashMap<String, ScheduledFuture<*>>()
    // Android 11/OEM GATT stacks can return a successful discovery callback while
    // serving a stale cached service table. Retry one discovery after refreshing
    // the hidden GATT cache before declaring the peer incompatible.
    private val writeTimeoutExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MeshGattWriteTimeout").apply { isDaemon = true }
        }
    private val notificationQueues = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val notifying = mutableSetOf<String>()
    // A successful GATT write only confirms the characteristic write callback.
    // Keep the durable packet until the destination sends the application ACK.
    private val awaitingDelivery = mutableMapOf<String, MutableMap<UUID, Long>>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        rfcommTransport.start()
        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid == this@MeshGattNode.service && status == BluetoothGatt.GATT_SUCCESS) {
                    gattServerReady = true
                    startBleAdvertisingAndScan()
                } else if (service.uuid == this@MeshGattNode.service) {
                    onStatus("BLE: сервис GATT не запущен: " + status)
                }
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == MeshProtocol.RFCOMM_INFO_UUID) {
                    val address = MeshBluetoothAddress.get(context, adapter)
                    val info = address?.let { "$it|$localRfcommUuid".toByteArray(StandardCharsets.UTF_8) }
                    onDiagnostic("BLE_RFCOMM_INFO", "read address=**" + device.address.takeLast(5) + " local_classic=" + (address != null))
                    server?.sendResponse(device, requestId, if (info != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, info)
                    return
                }
                if (characteristic.uuid == allocator) {
                    val session = allocatorSessions[device.address]
                    onDiagnostic("BLE_ALLOCATOR", "read address=**" + device.address.takeLast(5) + " allocated=" + (session != null))
                    server?.sendResponse(device, requestId, if (session != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, session)
                    return
                }
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }

            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                Log.d(TAG, "gatt_write_rx peer=${device.address.takeLast(5)} uuid=${characteristic.uuid} bytes=${value.size} prepared=$preparedWrite response=$responseNeeded offset=$offset prefix=${value.take(8).joinToString("") { "%02x".format(it) }}")
                if (characteristic.uuid == allocator && value.size == 16) {
                    val busy = allocatorSessions.keys.any { it != device.address }
                    if (!busy) {
                        allocatorSessions[device.address] = value.copyOf()
                        onDiagnostic("BLE_ALLOCATOR", "session_allocated address=**" + device.address.takeLast(5))
                        if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } else if (responseNeeded) {
                        onDiagnostic("BLE_ALLOCATOR", "session_rejected_busy address=**" + device.address.takeLast(5))
                        server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                    return
                }
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
                    allocatorSessions.remove(device.address)
                    onDiagnostic(
                        "BLE_GATT_SERVER",
                        "client_connected address=**" + device.address.takeLast(5) +
                            " server_clients=" + serverClients.size + " ready=" + gattReady.size
                    )
                    // BLESSED owns the central connection lifecycle. A peripheral-side
                    // connection is not enough to mark a peer online; wait for the
                    // application HELLO handshake below.
                    onDiagnostic("BLE_GATT_SERVER", "waiting_for_hello address=**" + device.address.takeLast(5))
                } else {
                    serverClients.remove(device.address)
                    allocatorSessions.remove(device.address)
                    peerNodeIds.remove(device.address)
                    notifyReady.remove(device.address)
                    gattReady.remove(device.address)
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
                } else {
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
        gattService.addCharacteristic(allocatorCharacteristic)
        gattService.addCharacteristic(rfcommInfoCharacteristic)
        gattService.addCharacteristic(rxCharacteristic)
        gattService.addCharacteristic(txCharacteristic)
        server?.addService(gattService)
        onDiagnostic("BLE_GATT_SERVER", "opened")

        // Advertising starts from onServiceAdded() so clients never discover an incomplete GATT server.
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertisingAndScan() {
        if (advertiser != null) return

        advertiser = adapter.bluetoothLeAdvertiser
        val adv = advertiser ?: run { onStatus("BLE advertising недоступен"); return }
        l2capTransport.start()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // Briar's BLE/L2CAP model keeps the service UUID in the primary
        // advertisement and publishes the dynamic L2CAP PSM as a 4-byte uint32
        // service-data value in the scan response. Keep our stable Node ID in
        // manufacturer data so the older GATT fallback can still elect an
        // initiator without consuming the PSM service-data slot.
        val nodeIdBytes = localId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(service))
            .build()
        // Keep the primary packet to service UUID only. A 128-bit UUID plus
        // manufacturer data can exceed the legacy 31-byte advertising budget
        // on Android 8/11 OEM stacks and causes ADVERTISE_FAILED_DATA_TOO_LARGE.
        // Put the stable Node ID in the scan response; on API 29+ prepend the
        // dynamic L2CAP PSM, yielding [PSM(4) + NodeId(8)].
        val scanPayload = if (l2capTransport.psm > 0) {
            val psm = l2capTransport.psm
            byteArrayOf(
                (psm ushr 24).toByte(),
                (psm ushr 16).toByte(),
                (psm ushr 8).toByte(),
                psm.toByte()
            ) + nodeIdBytes
        } else {
            nodeIdBytes
        }
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(service), scanPayload)
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

        // BLESSED owns the central-side scan/connect/GATT operation queue.
        // Do not run the raw BluetoothLeScanner path in parallel.
        blessedCentral.start()
        onDiagnostic("BLE_BLESSED", "central_transport_enabled")
        return
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
                blessedCentral.markPeerHello(from)
                val duplicateAddress = peerNodeIds.entries.firstOrNull { it.value == peerId && it.key != from }?.key
                if (duplicateAddress != null) {
                    // Same application identity reached us through a rotated BLE
                    // address. Keep the first connection and close this duplicate.
                    onDiagnostic("BLE_DUPLICATE", "node=" + peerId + ",address=**" + from.takeLast(5))
                    serverClients[from]?.let { runCatching { server?.cancelConnection(it) } }
                    return
                }
                val key = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull()
                if (key != null && key.isNotEmpty()) {
                    // L2CAP has its own socket/handshake lifecycle and must not be
                    // gated by the GATT-ready set. If this HELLO arrived over an
                    // L2CAP socket, mark that socket application-ready directly.
                    val rfcommHello = rfcommTransport.markReady(from)
                    val l2capHello = if (!rfcommHello) l2capTransport.markReady(from) else true
                    if (!rfcommHello && !l2capHello) {
                        // GATT fallback reaches the same application-ready boundary.
                        gattReady.add(from)
                        blessedCentral.markPeerHello(from)
                    }
                    peerLastSeenAt[peerId] = System.currentTimeMillis()
                    onDiagnostic("BLE_GATT_READY", "server_hello=true address=**" + from.takeLast(5))
                    onPeer(peerId, parts[2].ifBlank { peerId.take(8) }, key)
                    onPeerCountChanged(peerCount())
                    flushQueue()
                }
            }
            return
        }
        val packetBytes = acceptFragment(from, bytes) ?: run { Log.d(TAG, "fragment_pending_or_rejected peer=${from.takeLast(5)} bytes=${bytes.size}"); return }
        Log.d(TAG, "packet_reassembled peer=${from.takeLast(5)} bytes=${packetBytes.size}")
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
        val packet = MeshPacket.decode(bytes) ?: run { Log.w(TAG, "packet_decode_failed peer=${from.takeLast(5)} bytes=${bytes.size}"); return }
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
        // Retrying the durable queue must not publish peer-count state.
        // The peer-count callback schedules retryPending() when a peer appears;
        // calling it back here creates an infinite main-thread recursion:
        // peer-count -> retryPending -> peer-count -> ...
        if (serverClients.isEmpty() && blessedCentral.readyAddresses().isEmpty() &&
            l2capTransport.readyAddresses().isEmpty() && rfcommTransport.readyAddresses().isEmpty()) return
        flushQueue()
    }

    @SuppressLint("MissingPermission")
    private fun flushQueue() {
        val entries = queue.snapshot()

        // Classic Bluetooth RFCOMM is the primary Briar-style Bluetooth transport.
        val rfcommReady = rfcommTransport.readyAddresses()
        for (address in rfcommReady) {
            for (entry in entries) {
                val waitingSince = awaitingDelivery[address]?.get(entry.id)
                if (waitingSince != null && System.currentTimeMillis() - waitingSince < DELIVERY_RETRY_MS) continue
                if (waitingSince != null) awaitingDelivery[address]?.remove(entry.id)
                if (rfcommTransport.send(address, entry.bytes)) {
                    awaitingDelivery.getOrPut(address) { mutableMapOf() }[entry.id] = System.currentTimeMillis()
                }
            }
        }

        // API 29+ peers may also use the existing L2CAP CoC transport. The socket is
        // considered application-ready only after the same HELLO exchange used
        // by the GATT fallback has completed.
        val l2capReady = l2capTransport.readyAddresses()
        for (address in l2capReady) {
            for (entry in entries) {
                val waitingSince = awaitingDelivery[address]?.get(entry.id)
                if (waitingSince != null && System.currentTimeMillis() - waitingSince < DELIVERY_RETRY_MS) continue
                if (waitingSince != null) awaitingDelivery[address]?.remove(entry.id)
                if (l2capTransport.send(address, entry.bytes)) {
                    awaitingDelivery.getOrPut(address) { mutableMapOf() }[entry.id] = System.currentTimeMillis()
                }
            }
        }

        // A peer may exist only on the GATT-server side. The previous code
        // tracked those clients but never sent the durable outgoing queue to them.
        val serverOnly = serverClients.keys.filter {
            notifyReady.contains(it)
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

        // Application packets require the complete GATT setup and HELLO handshake.
        val ready = blessedCentral.readyAddresses().toList()
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
        if (!blessedCentral.readyAddresses().contains(address)) return
        val task = writeQueues[address]?.firstOrNull() ?: return
        val part = task.fragments.firstOrNull() ?: return

        // BLESSED is the sole owner of central-side GATT writes. There is no
        // legacy BluetoothGatt fallback: having two GATT implementations in
        // parallel was the source of duplicate connections and status 133.
        writing.add(address)
        val started = blessedCentral.write(address, part)
        if (!started) {
            writing.remove(address)
            onDiagnostic("BLE_BLESSED_WRITE", "start_failed address=**" + address.takeLast(5))
            return
        }
        writeTimeouts.remove(address)?.cancel(false)
        writeTimeouts[address] = writeTimeoutExecutor.schedule({
            if (!writing.remove(address)) return@schedule
            onDiagnostic("BLE_BLESSED_WRITE_TIMEOUT", "address=**" + address.takeLast(5) + ",timeout_ms=" + GATT_WRITE_TIMEOUT_MS)
            writeQueues.remove(address)
        }, GATT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
        val device = serverClients[address] ?: return
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
        // L2CAP carries complete MeshPacket frames directly; GATT keeps the
        // existing 180-byte fragmentation path for the compatibility fallback.
        for (address in l2capTransport.readyAddresses()) {
            if (address == except) continue
            l2capTransport.send(address, bytes)
        }

        val fragments = runCatching { fragment(bytes) }.getOrNull() ?: return
        val devices = LinkedHashMap<String, BluetoothDevice>()
        for ((address, device) in serverClients.toMap()) devices[address] = device
        for ((address, device) in devices) {
            if (address == except || !notifyReady.contains(address)) continue
            for (part in fragments) enqueueNotification(device, part)
        }
    }

    private fun peerCount(): Int {
        // Online means application READY, not merely GATT connected. Both sides
        // reach READY only after the HELLO handshake has completed.
        val readyAddresses = gattReady.toMutableSet().apply {
            addAll(blessedCentral.readyAddresses())
            addAll(l2capTransport.readyAddresses())
            addAll(rfcommTransport.readyAddresses())
        }
        val uniquePeers = mutableSetOf<String>()
        for (address in readyAddresses) uniquePeers.add(peerNodeIds[address] ?: address)
        return uniquePeers.size
    }

    fun onlinePeerCount(): Int = peerCount()

    /** True when at least one BLE transport completed the application handshake. */
    fun isBleTransportReady(): Boolean =
        rfcommTransport.readyAddresses().isNotEmpty() ||
            l2capTransport.readyAddresses().isNotEmpty() ||
            gattReady.isNotEmpty() || blessedCentral.isReady()

    /** True when the core BLE/GATT transport is running. L2CAP is an optional
     * optimization on API 29+ and must never make the GATT fallback unhealthy. */
    fun isHealthy(): Boolean = runCatching {
        adapter.isEnabled && gattServerReady && server != null &&
            advertisingStarted && advertiser != null &&
            blessedCentral.isRunning()
    }.getOrDefault(false)

    fun stop() {
        gattServerReady = false
        advertisingStarted = false
        // Android may turn the adapter off before the watchdog calls stop().
        // BLE scanner APIs throw IllegalStateException in that state; shutdown
        // must remain best-effort and must never crash the process.
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
        runCatching { blessedCentral.stop() }
        runCatching { l2capTransport.stop() }
        runCatching { rfcommTransport.stop() }
        serverClients.clear()
        peerNodeIds.clear()
        peerLastSeenAt.clear()
        notifyReady.clear()
        gattReady.clear()
        allocatorSessions.clear()
        notificationQueues.clear()
        notifying.clear()
        awaitingDelivery.clear()
        assemblies.clear()
        writeTimeouts.values.forEach { it.cancel(false) }
        writeTimeouts.clear()
        onPeerCountChanged(0)
        server?.close()
        server = null
        onDiagnostic("BLE_STOP", "all_gatt_closed")
    }
}
