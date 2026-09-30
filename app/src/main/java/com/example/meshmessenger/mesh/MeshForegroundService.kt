package com.example.meshmessenger.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.meshmessenger.MainActivity
import com.example.meshmessenger.R

/** Keeps the mesh transports alive while the UI is not visible. */
class MeshForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "mesh_runtime"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_APP_START = "com.example.meshmessenger.APP_START"
        const val ACTION_START = "com.example.meshmessenger.START_MESH"
        const val ACTION_STOP = "com.example.meshmessenger.STOP_MESH"
        const val ACTION_SEND_IP = "com.example.meshmessenger.SEND_IP"
	const val ACTION_SEND_MESH = "com.example.meshmessenger.SEND_MESH"
        const val EXTRA_IP = "ip"
        const val EXTRA_PACKET = "packet"

        const val ACTION_IP_MESSAGE = "com.example.meshmessenger.IP_MESSAGE"
        const val ACTION_MESH_MESSAGE = "com.example.meshmessenger.MESH_MESSAGE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_MESH_TEXT = "mesh_text"

        const val ACTION_IP_STATUS = "com.example.meshmessenger.IP_STATUS"
        const val ACTION_MESH_STATUS = "com.example.meshmessenger.MESH_STATUS"
        const val ACTION_MESH_STATUS_REQUEST = "com.example.meshmessenger.MESH_STATUS_REQUEST"
        const val EXTRA_MESH_ACTIVE = "mesh_active"
        const val EXTRA_PACKET_ID = "packet_id"
        const val EXTRA_IP_SUCCESS = "ip_success"
        const val ACTION_PEER_STATUS = "com.example.meshmessenger.PEER_STATUS"
        const val EXTRA_BLE_COUNT = "ble_count"
        const val EXTRA_NETBIRD_COUNT = "netbird_count"
        const val ACTION_MESH_DELIVERED = "com.example.meshmessenger.MESH_DELIVERED"
        const val EXTRA_DELIVERED_PACKET_ID = "delivered_packet_id"

        private const val RETRY_INTERVAL_MS = 5_000L
    }

    private var node: MeshGattNode? = null
    private var meshEnabled = false
    private var ipTransport: MeshIpTransport? = null
    private var netBirdGuard: NetBirdGuard? = null
    private var discovery: MeshDiscovery? = null
    private lateinit var pendingIp: PendingIpMessageStore
    private lateinit var contacts: ContactStore
    private var blePeerCount = 0
    private var netBirdPeerCount = 0

    private val retryHandler = Handler(Looper.getMainLooper())

    private val retryRunnable = object : Runnable {
        override fun run() {
            retryPendingIp()
            retryHandler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()

        startForeground(
            NOTIFICATION_ID,
            notification("Mesh Messenger работает")
        )

        pendingIp = PendingIpMessageStore(this)
        contacts = ContactStore(this)
        retryHandler.post(retryRunnable)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {

            ACTION_APP_START -> {
                updateNotification("Mesh Messenger работает")
                sendMeshStatus(meshEnabled && (node != null || ipTransport != null))
            }

            ACTION_MESH_STATUS_REQUEST -> {
                sendMeshStatus(meshEnabled && (node != null || ipTransport != null))
            }

            ACTION_START -> {
                meshEnabled = true
                startMesh()
            }

            ACTION_STOP -> {
                meshEnabled = false
                stopMesh()
                updateNotification("Mesh Messenger работает")
            }

            ACTION_SEND_MESH -> {
                val encoded = intent.getByteArrayExtra(EXTRA_PACKET)
                if (encoded != null) {
                    runCatching {
                        val packet = MeshPacket.decode(encoded)
                        if (packet != null) {
                            node?.send(packet)
                        }
                    }.onFailure {
                        updateNotification("Ошибка отправки Mesh-пакета")
                    }
                }
            }

            ACTION_SEND_IP -> {

                val ip = intent.getStringExtra(EXTRA_IP)
                val bytes = intent.getByteArrayExtra(EXTRA_PACKET)

                if (!ip.isNullOrBlank() && bytes != null) {
                    val packet = MeshPacket.decode(bytes)

                    if (packet != null) {
                        val success = ipTransport?.send(packet, ip) == true

                        if (success) {
                            pendingIp.remove(packet.messageId)
                        }

                        sendIpStatus(
                            packet.messageId.toString(),
                            success
                        )
                    } else {
                        updateNotification("IP: неверный пакет")
                    }
                }
            }

            else -> {
                sendMeshStatus(meshEnabled && (node != null || ipTransport != null))
            }
        }

        return START_STICKY
    }

    private fun startMesh() {
        if (node != null || ipTransport != null) {
            sendMeshStatus(meshEnabled && (node != null || ipTransport != null))
            return
        }

        val identity = IdentityStore(this)

        val router = MeshRouter(
            identity.nodeId,
            identity.keyPair.private
        ).also {
            it.identityPublicBytes = identity.keyPair.public.encoded
        }

        val queue = PendingMessageStore(this)

        netBirdGuard = NetBirdGuard(this)

        runCatching {
            discovery = MeshDiscovery(
                localId = identity.nodeId,
                localName = identity.displayName,
                publicKey = identity.keyPair.public.encoded,
                onPeer = { nodeId, name, publicKey, ip ->
                    val key = android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP)
                    val current = contacts.get(nodeId)
                    contacts.upsert(
                        ContactStore.Contact(
                            nodeId = nodeId,
                            name = name.trim().ifBlank { nodeId.take(8) },
                            publicKeyBase64 = key,
                            netBirdIp = ip.ifBlank { current?.netBirdIp.orEmpty() },
                            lastSeenAt = System.currentTimeMillis()
                        )
                    )
                },
                onCount = { count ->
                    netBirdPeerCount = count
                    sendPeerStatus()
                },
                onStatus = { updateNotification(it) }
            ).also { it.start() }
        }.onFailure {
            discovery = null
            android.util.Log.e("MeshMessenger", "Discovery start failed", it)
        }

        runCatching {
            ipTransport = MeshIpTransport(
                localId = identity.nodeId,
                router = router,
                queue = queue,
                guard = netBirdGuard!!,
                onStatus = { updateNotification(it) },
                onMessage = { text, sourceId, packet, remoteIp ->
                    rememberPeer(packet, remoteIp)
                    handleIpMessage(text, sourceId)
                },
                onDeliveryAck = { packetId -> sendDeliveryStatus(packetId) }
            ).also {
                it.start()
            }
        }.onFailure {
            ipTransport = null
            updateNotification("Внутренняя сеть: не удалось запустить транспорт")
            android.util.Log.e("MeshMessenger", "IP transport start failed", it)
        }

        val adapter = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }.getOrNull()

        if (adapter != null) {
            runCatching {
                node = MeshGattNode(
                    this,
                    adapter,
                    identity.nodeId,
                    identity.displayName,
                    identity.keyPair.public.encoded,
                    router,
                    queue,
                    { updateNotification(it) },
                    { text, sourceId, packet ->
                        rememberPeer(packet)
                        handleMeshMessage(text, sourceId)
                    },
                    { nodeId, name, publicKey ->
                        val key = android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP)
                        val current = contacts.get(nodeId)
                        contacts.upsert(
                            ContactStore.Contact(
                                nodeId = nodeId,
                                name = name.trim().ifBlank { nodeId.take(8) },
                                publicKeyBase64 = key,
                                netBirdIp = current?.netBirdIp.orEmpty(),
                                lastSeenAt = System.currentTimeMillis()
                            )
                        )
                    },
                    { packetId -> sendDeliveryStatus(packetId) },
                    { count ->
                        blePeerCount = count
                        sendPeerStatus()
                    }
                ).also {
                    it.start()
                }
            }.onFailure {
                node = null
                updateNotification("BLE: не удалось запустить mesh")
                android.util.Log.e("MeshMessenger", "BLE mesh start failed", it)
            }
        }

        val active = node != null || ipTransport != null
        updateNotification(if (active) "Mesh работает" else "Mesh не удалось запустить")
        sendMeshStatus(meshEnabled && active)
    }

    private fun retryPendingIp() {
        val transport = ipTransport ?: return
        val guard = netBirdGuard ?: return

        if (guard.status() != NetBirdGuard.Status.CONNECTED) {
            return
        }

        val entries = pendingIp.snapshot()
        if (entries.isEmpty()) {
            return
        }

        for (entry in entries) {
            val packet = MeshPacket.decode(entry.bytes) ?: run {
                pendingIp.remove(entry.id)
                sendIpStatus(entry.id.toString(), false)
                continue
            }

            val success = transport.send(packet, entry.ip)

            if (success) {
                pendingIp.remove(entry.id)
                sendIpStatus(entry.id.toString(), true)
            }
        }
    }

    private fun rememberPeer(packet: MeshPacket, netBirdIp: String = "") {
        val name = packet.senderName.trim().ifBlank { packet.sourceId.take(8) }
        val key = Base64.encodeToString(packet.senderPublicKey, Base64.NO_WRAP)
        if (packet.sourceId.isNotBlank() && key.isNotBlank()) {
            val current = contacts.get(packet.sourceId)
            contacts.upsert(
                ContactStore.Contact(
                    nodeId = packet.sourceId,
                    name = name,
                    publicKeyBase64 = key,
                    netBirdIp = netBirdIp.ifBlank { current?.netBirdIp.orEmpty() },
                    lastSeenAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun sendDeliveryStatus(packetId: String) {
        if (packetId.isBlank()) return
        sendBroadcast(Intent(ACTION_MESH_DELIVERED).apply {
            setPackage(packageName)
            putExtra(EXTRA_DELIVERED_PACKET_ID, packetId)
        })
    }

    private fun sendIpStatus(
        packetId: String,
        success: Boolean
    ) {
        val statusIntent = Intent(ACTION_IP_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_PACKET_ID, packetId)
            putExtra(EXTRA_IP_SUCCESS, success)
        }

        sendBroadcast(statusIntent)
    }

    private fun handleMeshMessage(text: String, sourceId: String) {
        val intent = Intent(ACTION_MESH_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESH_TEXT, text)
            putExtra(EXTRA_SOURCE_ID, sourceId)
        }
        sendBroadcast(intent)
    }

    private fun handleIpMessage(
        text: String,
        sourceId: String
    ) {
        val intent = Intent(ACTION_IP_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_SOURCE_ID, sourceId)
        }

        sendBroadcast(intent)
    }

    private fun stopMesh() {
        retryHandler.removeCallbacks(retryRunnable)

        node?.stop()
        node = null

        ipTransport?.stop()
        ipTransport = null

        discovery?.stop()
        discovery = null

        netBirdGuard = null
        blePeerCount = 0
        netBirdPeerCount = 0
        sendPeerStatus()
        sendMeshStatus(false)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Messenger",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description = "Фоновая работа mesh-сети"

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                if (meshEnabled) R.drawable.ic_mesh_notification_active
                else R.drawable.ic_mesh_notification
            )
            .setContentTitle("Mesh Messenger")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun sendPeerStatus() {
        val intent = Intent(ACTION_PEER_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_BLE_COUNT, blePeerCount)
            putExtra(EXTRA_NETBIRD_COUNT, netBirdPeerCount)
        }
        sendBroadcast(intent)
    }

    private fun sendMeshStatus(active: Boolean) {
        val intent = Intent(ACTION_MESH_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESH_ACTIVE, active)
        }
        sendBroadcast(intent)
    }


    private fun updateNotification(text: String) {
        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            notification(text)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMesh()
        super.onDestroy()
    }
}
