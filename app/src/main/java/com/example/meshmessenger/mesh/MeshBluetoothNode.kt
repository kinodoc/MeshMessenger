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
import java.util.LinkedHashSet
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
        private const val INITIAL_DISCOVERY_DELAY_MS = 10_000L
        private const val MAX_DISCOVERY_DELAY_MS = 120_000L
        private const val DISCOVERY_RETRY_MS = 2_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val PRESENCE_INTERVAL_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val INITIAL_RETRY_MS = 1_500L
        private const val MAX_RETRY_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MeshBtNode").apply { isDaemon = true }
    }
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    private val readyPeers = ConcurrentHashMap.newKeySet<String>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private val candidateQueue = LinkedHashSet<String>()
    private val retryDelayMs = HashMap<String, Long>()
    private val retryAfterMs = HashMap<String, Long>()
    private val handshakeTimeouts = ConcurrentHashMap<String, Runnable>()
    private val connectTimeouts = ConcurrentHashMap<String, Runnable>()
    private var receiverRegistered = false
    @Volatile private var running = false
    @Volatile private var discoveryActive = false
    private var discoveryDelayMs = INITIAL_DISCOVERY_DELAY_MS
    // Connection attempts and retries are coordinated here, not in the socket layer.
    @Volatile private var activeConnectAddress: String? = null
    private val connectQueueRunnable = Runnable { pumpConnectQueue() }

    private lateinit var rfcomm: MeshClassicBluetoothTransport

    init {
        rfcomm = MeshClassicBluetoothTransport(
        context, adapter, MeshProtocol.RFCOMM_SERVICE_UUID,
        { helloPayload() }, onDiagnostic,
        { address, bytes -> handleFrame(address, bytes) },
        { address ->
            // Mark the peer synchronously so the main-thread queue cannot start
            // another outbound attempt in the small window before this callback
            // is processed by the handler.
            connectingPeers.add(address)
            onDiagnostic("BT_RFCOMM", "connected address=**" + address.takeLast(5))
            handler.post {
                cancelConnectTimeout(address)
                if (!running || readyPeers.contains(address) || !rfcomm.isConnected(address)) {
                    connectingPeers.remove(address)
                    return@post
                }
                candidateQueue.remove(address)
                if (activeConnectAddress == null) activeConnectAddress = address
                scheduleHandshakeTimeout(address)
            }
        },
        { address ->
            handler.post {
                cancelConnectTimeout(address)
                cancelHandshakeTimeout(address)
                val wasReady = readyPeers.remove(address)
                peerNodeIds.remove(address)
                connectingPeers.remove(address)
                if (activeConnectAddress == address) activeConnectAddress = null
                onPeerCountChanged(peerCount())
                if (running) {
                    scheduleConnectRetry(address, if (wasReady) "socket_lost" else "handshake_lost")
                    pumpConnectQueue()
                    handler.postDelayed({ if (running) maybeStartDiscovery() }, RECONNECT_DELAY_MS)
                }
            }
        },
        { address, connected ->
            handler.post {
                if (!running) return@post
                cancelConnectTimeout(address)
                if (connected || rfcomm.isConnected(address) || readyPeers.contains(address)) {
                    if (!readyPeers.contains(address)) {
                        connectingPeers.add(address)
                        scheduleHandshakeTimeout(address)
                    } else {
                        connectingPeers.remove(address)
                        if (activeConnectAddress == address) activeConnectAddress = null
                        pumpConnectQueue()
                    }
                    onDiagnostic(
                        "BT_CONNECT_STATE",
                        "finished_connected address=**" + address.takeLast(5) +
                            " ready=" + readyPeers.contains(address)
                    )
                } else {
                    connectingPeers.remove(address)
                    if (activeConnectAddress == address) activeConnectAddress = null
                    onDiagnostic("BT_CONNECT_STATE", "finished_failed address=**" + address.takeLast(5))
                    scheduleConnectRetry(address, "connect_failed")
                    pumpConnectQueue()
                    handler.postDelayed({ if (running) maybeStartDiscovery() }, RECONNECT_DELAY_MS)
                }
            }
        }
        )
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    discoveryActive = true
                    onDiagnostic("BT_DISCOVERY", "started bonded=" + runCatching { adapter.bondedDevices.size }.getOrDefault(0) + " ready=" + readyPeers.size + " connecting=" + connectingPeers.size)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    discoveryActive = false
                    onDiagnostic("BT_DISCOVERY", "finished ready=" + readyPeers.size + " connecting=" + connectingPeers.size)
                    handler.postDelayed({ if (running) maybeStartDiscovery() }, DISCOVERY_RETRY_MS)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val previous = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
                    onDiagnostic("BT_ADAPTER", "state=" + stateName(state) + " previous=" + stateName(previous) + " ready=" + readyPeers.size + " connecting=" + connectingPeers.size)
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device = deviceFromIntent(intent) ?: return
                    if (device.address == adapter.address) return
                    onDiagnostic(
                        "BT_DISCOVERY",
                        "found name=" + runCatching { device.name }.getOrNull().orEmpty().take(32) +
                            " address=**" + device.address.takeLast(5)
                    )
                    if (isUsableClassicDevice(device) && shouldInitiate(device.address)) connectIfNeeded(device.address)
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = deviceFromIntent(intent) ?: return
                    if (device.address != adapter.address) onDiagnostic("BT_ACL", "state=disconnected address=**" + device.address.takeLast(5))
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = deviceFromIntent(intent) ?: return
                    if (device.address != adapter.address && isUsableClassicDevice(device)) {
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
        onDiagnostic("BT_STATE", "start enabled=true bonded=" + runCatching { adapter.bondedDevices.size }.getOrDefault(0) + " address_known=" + !adapter.address.isNullOrBlank())
        connectingPeers.clear()
        candidateQueue.clear()
        retryDelayMs.clear()
        retryAfterMs.clear()
        activeConnectAddress = null
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
                        onDiagnostic("BT_HELLO_TX", "heartbeat address=**${address.takeLast(5)}")
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
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
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
        if (activeConnectAddress != null || connectingPeers.isNotEmpty()) {
            onDiagnostic(
                "BT_DISCOVERY",
                "deferred active=**" + (activeConnectAddress?.takeLast(5) ?: "none") +
                    " handshakes=" + connectingPeers.size +
                    " ready=" + readyPeers.size
            )
            return
        }
        if (runCatching { adapter.isDiscovering }.getOrDefault(false)) {
            discoveryActive = true
            onDiagnostic("BT_DISCOVERY", "already_discovering=true")
            return
        }

        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (started) {
            discoveryActive = true
            discoveryDelayMs = INITIAL_DISCOVERY_DELAY_MS
            onDiagnostic("BT_DISCOVERY", "start=true ready=" + readyPeers.size)
        } else {
            discoveryDelayMs = (discoveryDelayMs * 2).coerceAtMost(MAX_DISCOVERY_DELAY_MS)
            onDiagnostic("BT_DISCOVERY", "start=false next_ms=" + discoveryDelayMs)
            handler.postDelayed({ if (running) maybeStartDiscovery() }, discoveryDelayMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isUsableClassicDevice(device: BluetoothDevice): Boolean =
        device.type != BluetoothDevice.DEVICE_TYPE_LE

    @SuppressLint("MissingPermission")
    private fun connectIfNeeded(address: String) {
        if (!running || address.isBlank() || address == adapter.address) return
        if (readyPeers.contains(address) || connectingPeers.contains(address) || rfcomm.isConnected(address)) {
            onDiagnostic(
                "BT_CONNECT_SKIP",
                "reason=peer_already_active address=**" + address.takeLast(5) +
                    " ready=" + readyPeers.contains(address) +
                    " connecting=" + connectingPeers.contains(address) +
                    " socket=" + rfcomm.isConnected(address)
            )
            return
        }

        val added = candidateQueue.add(address)
        if (added) {
            onDiagnostic("BT_CONNECT_CANDIDATE", "address=**" + address.takeLast(5) + " queued=" + candidateQueue.size)
        }
        pumpConnectQueue()
    }

    private fun pumpConnectQueue() {
        if (!running || activeConnectAddress != null || connectingPeers.isNotEmpty()) return

        val now = System.currentTimeMillis()
        candidateQueue.removeAll { address ->
            readyPeers.contains(address) || rfcomm.isConnected(address) || connectingPeers.contains(address)
        }
        val address = candidateQueue.firstOrNull { (retryAfterMs[it] ?: 0L) <= now }
        if (address == null) {
            val nextRetry = candidateQueue.mapNotNull { retryAfterMs[it] }
                .filter { it > now }
                .minOrNull()
            if (nextRetry != null) {
                handler.removeCallbacks(connectQueueRunnable)
                handler.postDelayed(connectQueueRunnable, (nextRetry - now).coerceAtLeast(100L))
            }
            return
        }

        candidateQueue.remove(address)
        activeConnectAddress = address
        connectingPeers.add(address)

        if (discoveryActive || runCatching { adapter.isDiscovering }.getOrDefault(false)) {
            runCatching { adapter.cancelDiscovery() }
            discoveryActive = false
            onDiagnostic("BT_DISCOVERY", "cancel_for_connect address=**" + address.takeLast(5))
        }

        onDiagnostic(
            "BT_RFCOMM",
            "connect_requested address=**" + address.takeLast(5) +
                " ready=" + readyPeers.size +
                " connecting=" + connectingPeers.size +
                " queued=" + candidateQueue.size +
                " discovery=" + discoveryActive
        )
        scheduleConnectTimeout(address)
        rfcomm.connect(address, MeshProtocol.RFCOMM_SERVICE_UUID)
    }

    private fun scheduleConnectTimeout(address: String) {
        cancelConnectTimeout(address)
        val timeout = Runnable {
            connectTimeouts.remove(address)
            if (running && activeConnectAddress == address && !rfcomm.isConnected(address)) {
                onDiagnostic("BT_CONNECT_TIMEOUT", "address=**" + address.takeLast(5) + " timeout_ms=" + CONNECT_TIMEOUT_MS)
                rfcomm.cancelPendingConnect(address, "connect_timeout")
            }
        }
        connectTimeouts[address] = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout(address: String) {
        connectTimeouts.remove(address)?.let { handler.removeCallbacks(it) }
    }

    private fun scheduleConnectRetry(address: String, reason: String) {
        if (!running || address.isBlank() || readyPeers.contains(address)) return
        val now = System.currentTimeMillis()
        if ((retryAfterMs[address] ?: 0L) > now) return

        val delay = retryDelayMs[address] ?: INITIAL_RETRY_MS
        retryDelayMs[address] = (delay * 2L).coerceAtMost(MAX_RETRY_MS)
        retryAfterMs[address] = now + delay
        candidateQueue.add(address)
        onDiagnostic(
            "BT_RETRY_SCHEDULED",
            "reason=" + reason + " address=**" + address.takeLast(5) + " delay_ms=" + delay
        )
        handler.removeCallbacks(connectQueueRunnable)
        handler.postDelayed(connectQueueRunnable, delay)
    }

    private fun scheduleHandshakeTimeout(address: String) {
        cancelHandshakeTimeout(address)
        if (readyPeers.contains(address)) return
        val timeout = Runnable {
            handshakeTimeouts.remove(address)
            if (running && !readyPeers.contains(address) && rfcomm.isConnected(address)) {
                onDiagnostic("BT_HANDSHAKE_TIMEOUT", "address=**" + address.takeLast(5))
                rfcomm.close(address, "handshake_timeout")
            }
        }
        handshakeTimeouts[address] = timeout
        handler.postDelayed(timeout, HANDSHAKE_TIMEOUT_MS)
    }

    private fun cancelHandshakeTimeout(address: String) {
        handshakeTimeouts.remove(address)?.let { handler.removeCallbacks(it) }
    }

    @SuppressLint("MissingPermission")
    private fun shouldInitiate(address: String): Boolean {
        val local = runCatching { adapter.address }.getOrDefault("")
        val result = local.isNotBlank() && local < address
        onDiagnostic("BT_DIRECTION", "address=**" + address.takeLast(5) + " initiate=" + result)
        return result
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
            onDiagnostic("BT_PACKET", "decode_failed address=**${address.takeLast(5)} bytes=${bytes.size}")
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
        // The node ID is the SHA-256 key ID. Do not mark a transport ready
        // when the peer claims an identity that does not match its public key.
        val publicKey = runCatching { CryptoManager.publicKeyFromBase64(parts[3]) }.getOrNull() ?: return
        if (CryptoManager.keyId(publicKey) != peerId) {
            onDiagnostic("BT_READY", "identity_mismatch address=**${address.takeLast(5)}")
            return
        }
        val firstReady = readyPeers.add(address)
        peerNodeIds[address] = peerId
        if (!rfcomm.markReady(address)) {
            if (firstReady) readyPeers.remove(address)
            onDiagnostic("BT_READY", "socket_missing address=**" + address.takeLast(5))
            return
        }
        handler.post {
            if (!running) return@post
            cancelConnectTimeout(address)
            cancelHandshakeTimeout(address)
            connectingPeers.remove(address)
            if (activeConnectAddress == address) activeConnectAddress = null
            candidateQueue.remove(address)
            retryDelayMs.remove(address)
            retryAfterMs.remove(address)
            discoveryDelayMs = INITIAL_DISCOVERY_DELAY_MS
            onDiagnostic("BT_PEER_STATE", "ready_count=" + readyPeers.size + " peer_count=" + peerCount())
            onDiagnostic("BT_READY", "peer=" + peerId.take(12) + " address=**" + address.takeLast(5))
            onPeer(peerId, parts[2].trim().ifBlank { peerId.take(8) }, key)
            onPeerCountChanged(peerCount())
            flushQueue()
            pumpConnectQueue()
        }
    }

    private fun handlePacket(address: String, packet: MeshPacket) {
        onDiagnostic("BT_PACKET_RX", "id=${packet.messageId} src=${packet.sourceId.take(8)} dst=${packet.destinationId.take(8)}")
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            val text = router.decryptForLocal(packet) ?: "[не удалось расшифровать]"
            if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                val deliveredId = text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX)
                queue.remove(runCatching { UUID.fromString(deliveredId) }.getOrNull() ?: packet.messageId)
                onDeliveryAck(deliveredId)
                onDiagnostic("BT_DELIVERY_ACK", "delivered=${deliveredId}")
            } else {
                onMessage(text, packet.sourceId, packet)
                runCatching { router.createDeliveryAck(packet) }.getOrNull()?.let { sendTo(address, it) }
                queue.remove(packet.messageId)
                onDiagnostic("BT_DELIVERED", "id=${packet.messageId}")
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

    fun isHealthy(): Boolean = running && adapter.isEnabled && receiverRegistered && rfcomm.isRunning()

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        discoveryActive = false
        discoveryDelayMs = INITIAL_DISCOVERY_DELAY_MS
        handler.removeCallbacks(discoveryLoop)
        handler.removeCallbacks(presenceLoop)
        handler.removeCallbacks(connectQueueRunnable)
        handshakeTimeouts.values.forEach { handler.removeCallbacks(it) }
        handshakeTimeouts.clear()
        connectTimeouts.values.forEach { handler.removeCallbacks(it) }
        connectTimeouts.clear()
        candidateQueue.clear()
        retryDelayMs.clear()
        retryAfterMs.clear()
        runCatching { adapter.cancelDiscovery() }
        rfcomm.stop()
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        readyPeers.clear()
        peerNodeIds.clear()
        onDiagnostic("BT_STATE", "stopped")
        connectingPeers.clear()
        activeConnectAddress = null
        onPeerCountChanged(0)
        // Keep the node executor reusable across service stop/start cycles.
        onDiagnostic("BT", "stopped")
    }

    private fun peerCount(): Int {
        val ids = mutableSetOf<String>()
        for (address in readyPeers) {
            if (rfcomm.readyAddresses().contains(address)) ids += peerNodeIds[address] ?: address
        }
        return ids.size
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        else -> "UNKNOWN(" + state + ")"
    }

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
