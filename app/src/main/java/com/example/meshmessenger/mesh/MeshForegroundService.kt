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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.meshmessenger.MainActivity
import com.example.meshmessenger.R
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the mesh transports alive while the UI is not visible. */
class MeshForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "mesh_runtime"
        private const val INCOMING_CHANNEL_ID = "incoming_messages_v1"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_APP_START = "com.example.meshmessenger.APP_START"
        const val ACTION_START = "com.example.meshmessenger.START_MESH"
        const val ACTION_STOP = "com.example.meshmessenger.STOP_MESH"
        const val ACTION_SET_MODE = "com.example.meshmessenger.SET_TRANSPORT_MODE"
        const val EXTRA_TRANSPORT_MODE = "transport_mode"
	const val ACTION_SEND_MESH = "com.example.meshmessenger.SEND_MESH"
        const val EXTRA_PACKET = "packet"
        const val ACTION_PROFILE_NAME_CHANGED = "com.example.meshmessenger.PROFILE_NAME_CHANGED"

        const val ACTION_RELAY_MESSAGE = "com.example.meshmessenger.RELAY_MESSAGE"
        const val ACTION_MESH_MESSAGE = "com.example.meshmessenger.MESH_MESSAGE"
        const val EXTRA_RELAY_TEXT = "relay_text"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_MESH_TEXT = "mesh_text"
        const val ACTION_CONTACT_UPDATED = "com.example.meshmessenger.CONTACT_UPDATED"
        const val EXTRA_CONTACT_NAME = "contact_name"

        const val ACTION_MESH_STATUS = "com.example.meshmessenger.MESH_STATUS"
        const val ACTION_MESH_STATUS_REQUEST = "com.example.meshmessenger.MESH_STATUS_REQUEST"
        const val EXTRA_MESH_ACTIVE = "mesh_active"
        const val EXTRA_PACKET_ID = "packet_id"
        const val ACTION_PEER_STATUS = "com.example.meshmessenger.PEER_STATUS"
        const val EXTRA_BT_COUNT = "bt_count"
        const val EXTRA_RELAY_COUNT = "relay_count"
        const val ACTION_MESH_DELIVERED = "com.example.meshmessenger.MESH_DELIVERED"
        const val EXTRA_DELIVERED_PACKET_ID = "delivered_packet_id"

        private const val RETRY_INTERVAL_MS = 5_000L
        private const val AUTO_BUGREPORT_INTERVAL_MS = 3L * 60L * 60L * 1000L
        private const val AUTO_BUGREPORT_OFFLINE_RETRY_MS = 15L * 60L * 1000L
        private const val PREFS = "mesh_runtime"
        private const val KEY_ENABLED = "mesh_enabled"
    }

    private var node: MeshBluetoothNode? = null
    private var meshEnabled = false
    private var transportMode = "BOTH"
    private var relayTransport: MeshRelayTransport? = null
    private lateinit var pendingMesh: PendingMessageStore
    private lateinit var contacts: ContactStore
    private lateinit var chats: ChatStore
    private var btPeerCount = 0
    private var relayPeerCount = 0
    private var lastPeerStatusDiagnostic: String? = null
    private var nodeStartedAtMs = 0L
    private lateinit var diagnostics: MeshDiagnostics
    private lateinit var bluetoothMonitor: MeshBluetoothMonitor

    private val retryHandler = Handler(Looper.getMainLooper())
    private val autoBugReportInProgress = AtomicBoolean(false)
    @Volatile private var serviceDestroyed = false

    private fun hasValidatedNetwork(): Boolean = runCatching {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private val autoBugReportRunnable: Runnable = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return
            if (!hasValidatedNetwork()) {
                diagnostics.event("BUGREPORT_AUTO_DEFERRED", "reason=no_validated_network")
                retryHandler.postDelayed(this, AUTO_BUGREPORT_OFFLINE_RETRY_MS)
                return
            }
            if (!autoBugReportInProgress.compareAndSet(false, true)) {
                diagnostics.event("BUGREPORT_AUTO_SKIPPED", "reason=upload_in_progress")
                retryHandler.postDelayed(this, AUTO_BUGREPORT_INTERVAL_MS)
                return
            }
            Thread {
                try {
                    val report = MeshBugReport.create(this@MeshForegroundService, diagnostics, bluetoothMonitor.snapshot())
                    val result = MeshBugReportUploader.upload(report)
                    report.delete()
                    diagnostics.event("BUGREPORT_AUTO_UPLOAD", "result=$result")
                } catch (error: Exception) {
                    diagnostics.event("BUGREPORT_AUTO_ERROR", "error_type=${error.javaClass.simpleName}")
                    android.util.Log.w("MeshMessenger", "Automatic bug report upload failed", error)
                } finally {
                    autoBugReportInProgress.set(false)
                    if (!serviceDestroyed) retryHandler.postDelayed(autoBugReportRunnable, AUTO_BUGREPORT_INTERVAL_MS)
                }
            }.start()
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (meshEnabled) {
                val adapter = runCatching {
                    (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                }.getOrNull()
                if (transportMode == "RELAY") {
                    if (node != null) { node?.stop(); node = null; btPeerCount = 0; sendPeerStatus() }
                    sendMeshStatus(false)
                } else if (adapter?.isEnabled != true) {
                    if (node != null) {
                        diagnostics.event("BT_WATCHDOG", "bluetooth_disabled")
                        node?.stop()
                        node = null
                    }
                    sendMeshStatus(false)
                } else if (node == null) {
                    diagnostics.event("BT_WATCHDOG", "node_missing_restart")
                    startMesh(startBluetooth = true)
                } else if (node?.isHealthy() == true) {
                    sendMeshStatus(true)
                } else if (System.currentTimeMillis() - nodeStartedAtMs >= 30_000L) {
                    diagnostics.event("BT_WATCHDOG", "node_unhealthy_restart")
                    node?.stop()
                    node = null
                    startMesh(startBluetooth = true)
                } else {
                    diagnostics.event("BT_WATCHDOG", "node_initializing")
                    updateNotification("Mesh запускается…")
                }
            } else {
                sendMeshStatus(false)
            }
            retryHandler.postDelayed(this, 10_000L)
        }
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            relayTransport?.retryPending()
            node?.retryPending()
            retryHandler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        diagnostics = MeshDiagnostics(this)
        diagnostics.event("SERVICE_CREATE")
        val bluetoothAdapter = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }.getOrNull()
        bluetoothMonitor = MeshBluetoothMonitor(this, bluetoothAdapter, diagnostics)
        bluetoothMonitor.start()
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            diagnostics.crash(thread, throwable)
            previousCrashHandler?.uncaughtException(thread, throwable)
        }

        createChannel()

        startForeground(
            NOTIFICATION_ID,
            notification("Mesh Messenger работает")
        )

        pendingMesh = PendingMessageStore(this)
        contacts = ContactStore(this)
        chats = ChatStore(this)
        retryHandler.post(retryRunnable)
        retryHandler.postDelayed(autoBugReportRunnable, AUTO_BUGREPORT_INTERVAL_MS)
        meshEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        transportMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("transport_mode", "BOTH") ?: "BOTH"
        startMesh(startBluetooth = meshEnabled && transportMode != "RELAY")
        retryHandler.postDelayed(watchdogRunnable, 10_000L)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {

            ACTION_APP_START -> {
                updateNotification("Mesh Messenger работает")
                if (meshEnabled && transportMode != "RELAY" && (node == null || node?.isHealthy() != true)) startMesh(startBluetooth = true)
                else if (node?.isHealthy() == true) sendMeshStatus(true)
                else sendMeshStatus(false)
            }

            ACTION_MESH_STATUS_REQUEST -> {
                if (node?.isHealthy() == true) sendMeshStatus(true)
                else if (!meshEnabled) sendMeshStatus(false)
                // While enabled but initializing, do not publish a false/off state.
                // MainActivity registers its peer-status receiver on every start;
                // replay current channel counts so the UI does not default to disconnected.
                btPeerCount = node?.onlinePeerCount() ?: 0
                sendPeerStatus()
            }

            ACTION_START -> {
                meshEnabled = true
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
                startMesh(startBluetooth = transportMode != "RELAY")
            }

            ACTION_SET_MODE -> {
                val requested = intent.getStringExtra(EXTRA_TRANSPORT_MODE)?.uppercase()
                if (requested in setOf("BT", "BOTH", "RELAY")) {
                    transportMode = requested!!
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("transport_mode", transportMode).putBoolean(KEY_ENABLED, true).apply()
                    meshEnabled = true
                    diagnostics.event("TRANSPORT_MODE", "mode=$transportMode")
                    node?.stop()
                    node = null
                    btPeerCount = 0
                    stopRelayLayer()
                    sendPeerStatus()
                    startMesh(startBluetooth = transportMode != "RELAY")
                    retryHandler.removeCallbacks(retryRunnable)
                    retryHandler.post(retryRunnable)
                }
            }

            ACTION_STOP -> {
                meshEnabled = false
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
                stopMesh()
                updateNotification("Mesh Messenger работает")
            }

            ACTION_PROFILE_NAME_CHANGED -> {
                sendProfileNameUpdate(intent.getStringExtra(EXTRA_CONTACT_NAME))
            }

            ACTION_SEND_MESH -> {
                val encoded = intent.getByteArrayExtra(EXTRA_PACKET)
                diagnostics.event("PACKET_SEND_REQUEST", "bytes=${encoded?.size ?: 0} mode=$transportMode bt_ready=${node?.isBluetoothTransportReady() == true} relay_ready=${relayTransport != null}")
                android.util.Log.d("MeshBluetoothDiag", "service_send_mesh bytes=${encoded?.size ?: 0} nodeReady=${node != null}")
                if (encoded != null) {
                    runCatching {
                        val packet = MeshPacket.decode(encoded)
                        if (packet != null) {
                            android.util.Log.d("MeshBluetoothDiag", "service_packet_decoded id=${packet.messageId} src=${packet.sourceId.take(8)} dst=${packet.destinationId.take(8)}")
                            diagnostics.event("PACKET_DECODED", "bytes=${encoded.size} bt_ready=${node?.isBluetoothTransportReady() == true} relay_ready=${relayTransport != null}")
                            if (transportMode != "RELAY") runCatching { node?.send(packet) }
                                .onFailure { diagnostics.event("BT_SEND_ERROR", "error_type=${it.javaClass.simpleName}") }
                            if (transportMode != "BT") runCatching { relayTransport?.send(packet) }
                                .onFailure { diagnostics.event("RELAY_SEND_ERROR", "error_type=${it.javaClass.simpleName}") }
                        } else {
                            diagnostics.event("PACKET_DECODE_ERROR", "bytes=${encoded.size}")
                            android.util.Log.w("MeshBluetoothDiag", "service_packet_decode_failed bytes=${encoded.size}")
                        }
                    }.onFailure {
                        diagnostics.event("PACKET_SEND_ERROR", "error_type=${it.javaClass.simpleName}")
                        updateNotification("Ошибка отправки Mesh-пакета")
                    }
                }
            }


            else -> {
                sendMeshStatus(node != null)
            }
        }

        return START_STICKY
    }

    private fun startMesh(startBluetooth: Boolean) {
        diagnostics.event("MESH_START", "bluetooth=$startBluetooth")
        if (startBluetooth && node != null) {
            if (node?.isHealthy() == true) {
                sendMeshStatus(true)
                return
            }
            if (System.currentTimeMillis() - nodeStartedAtMs < 30_000L) {
                diagnostics.event("BT_START", "already_initializing")
                return
            }
            diagnostics.event("BT_WATCHDOG", "replacing_unhealthy_node")
            node?.stop()
            node = null
        }

        val identity = IdentityStore(this)

        val router = MeshRouter(
            identity.nodeId,
            identity.keyPair.private
        ).also {
            it.identityPublicBytes = identity.keyPair.public.encoded
        }

        val queue = PendingMessageStore(this)

        // Apply the persisted transport mode before starting transports.
        // In BT-only mode do not briefly start Relay and immediately stop it.
        if (transportMode == "BT") stopRelayLayer()

        if (transportMode != "BT" && relayTransport == null) runCatching {
            relayTransport = MeshRelayTransport(
                context = this,
                localId = identity.nodeId,
                localName = identity.displayName,
                publicKey = identity.keyPair.public.encoded,
                router = router,
                queue = queue,
                onStatus = { updateNotification(it) },
                onMessage = { text, sourceId, packet, _ ->
                    handleIncomingPersisted(text, sourceId, packet, ACTION_RELAY_MESSAGE)
                },
                onDeliveryAck = { packetId -> sendDeliveryStatus(packetId) },
                onPeer = { nodeId, name, publicKey, _ ->
                    val key = Base64.encodeToString(publicKey, Base64.NO_WRAP)
                    contacts.upsert(
                        ContactStore.Contact(
                            nodeId = nodeId,
                            name = name.trim().ifBlank { nodeId.take(8) },
                            publicKeyBase64 = key,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    )
                },
                onPeerCount = { count ->
                    relayPeerCount = count
                    diagnostics.event("RELAY_PEER_COUNT", "count=$count")
                    sendPeerStatus()
                    if (count > 0) retryHandler.post { relayTransport?.retryPending() }
                },
                onDiagnostic = { type, detail -> diagnostics.event(type, detail) }
            ).also { it.start() }
        }.onFailure {
            relayTransport = null
            updateNotification("Relay: не удалось запустить транспорт")
            android.util.Log.e("MeshMessenger", "Relay transport start failed", it)
        }

        if (!startBluetooth || transportMode == "RELAY") {
            sendMeshStatus(false)
            return
        }

        val adapter = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }.getOrNull()

        if (adapter != null) {
            runCatching {
                nodeStartedAtMs = System.currentTimeMillis()
                node = MeshBluetoothNode(
                    this,
                    adapter,
                    identity.nodeId,
                    identity.displayName,
                    identity.keyPair.public.encoded,
                    router,
                    queue,
                    { updateNotification(it) },
                    { text, sourceId, packet ->
                        handleIncomingPersisted(text, sourceId, packet, ACTION_MESH_MESSAGE)
                    },
                    { nodeId, name, publicKey ->
                        val key = android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP)
                        contacts.upsert(
                            ContactStore.Contact(
                                nodeId = nodeId,
                                name = name.trim().ifBlank { nodeId.take(8) },
                                publicKeyBase64 = key,
                                lastSeenAt = System.currentTimeMillis()
                            )
                        )
                    },
                    { packetId -> sendDeliveryStatus(packetId) },
                    { count ->
                        btPeerCount = count
                        sendPeerStatus()
                        if (count > 0) retryHandler.post { node?.retryPending() }
                    },
                    { type, detail -> diagnostics.event(type, detail) }
                ).also {
                    it.start()
                }
            }.onFailure {
                node = null
                updateNotification("BT: не удалось запустить mesh")
                android.util.Log.e("MeshMessenger", "BT mesh start failed", it)
            }
        }

        val active = node?.isHealthy() == true
        updateNotification(if (active) "Mesh работает" else "Mesh запускается…")
        // Classic Bluetooth RFCOMM startup and discovery run asynchronously.
        // Do not reset the UI to off while initialization is still in progress.
        if (active) sendMeshStatus(true)
    }


    private fun sendDeliveryStatus(packetId: String) {
        android.util.Log.i("MeshBluetoothDiag", "delivery_status_broadcast packetId=$packetId")
        if (packetId.isBlank()) return
        runCatching {
            val id = java.util.UUID.fromString(packetId)
            pendingMesh.remove(id)
        }
        sendBroadcast(Intent(ACTION_MESH_DELIVERED).apply {
            setPackage(packageName)
            putExtra(EXTRA_DELIVERED_PACKET_ID, packetId)
        })
    }


    private fun sendProfileNameUpdate(name: String?) {
        val identity = IdentityStore(this)
        val safeName = name?.trim().orEmpty().ifBlank { identity.displayName }.take(64)
        val router = MeshRouter(identity.nodeId, identity.keyPair.private).also {
            it.identityPublicBytes = identity.keyPair.public.encoded
        }
        val contactsSnapshot = contacts.all()
        contactsSnapshot.forEach { contact ->
            val publicKey = runCatching {
                CryptoManager.publicKeyFromBase64(contact.publicKeyBase64)
            }.getOrNull() ?: return@forEach
            val packet = runCatching {
                router.createProfileUpdate(contact.nodeId, publicKey, safeName)
            }.getOrNull() ?: return@forEach

            pendingMesh.enqueue(packet)
            if (transportMode != "RELAY") runCatching { node?.send(packet) }
            if (transportMode != "BT") runCatching { relayTransport?.send(packet) }
        }
        diagnostics.event("PROFILE_NAME_UPDATE", "contacts=${contactsSnapshot.size}")
    }

    private fun handleIncomingPersisted(
        text: String,
        sourceId: String,
        packet: MeshPacket,
        action: String
    ) {
        val packetId = packet.messageId.toString()
        if (text.startsWith(MeshRouter.PROFILE_UPDATE_PREFIX)) {
            val newName = text.removePrefix(MeshRouter.PROFILE_UPDATE_PREFIX)
                .trim().take(64)
                .ifBlank { sourceId.take(8) }
            val existing = contacts.get(sourceId)
            if (existing != null) {
                contacts.rename(sourceId, newName)
            } else {
                val key = Base64.encodeToString(packet.senderPublicKey, Base64.NO_WRAP)
                contacts.upsert(
                    ContactStore.Contact(
                        nodeId = sourceId,
                        name = newName,
                        publicKeyBase64 = key,
                        lastSeenAt = System.currentTimeMillis()
                    )
                )
            }
            sendBroadcast(Intent(ACTION_CONTACT_UPDATED).apply {
                setPackage(packageName)
                putExtra(EXTRA_SOURCE_ID, sourceId)
                putExtra(EXTRA_CONTACT_NAME, newName)
            })
            diagnostics.event("PROFILE_NAME_RECEIVED", "source=${sourceId.take(12)}")
            return
        }

        val isNewMessage = chats.addIncomingIfAbsent(
            sourceId,
            text,
            packetId
        )

        if (isNewMessage) {
            showIncomingMessageNotification(text, sourceId, packetId)
        }

        val intent = Intent(action).apply {
            setPackage(packageName)
            if (action == ACTION_RELAY_MESSAGE) {
                putExtra(EXTRA_RELAY_TEXT, text)
            } else {
                putExtra(EXTRA_MESH_TEXT, text)
            }
            putExtra(EXTRA_SOURCE_ID, sourceId)
            putExtra(EXTRA_PACKET_ID, packetId)
        }
        sendBroadcast(intent)
    }

    private fun stopMesh() {
        diagnostics.event("MESH_STOP")
        // Stopping BT leaves the internet relay active independently.
        node?.stop()
        node = null
        btPeerCount = 0
        sendPeerStatus()
        sendMeshStatus(false)
    }

    private fun stopRelayLayer() {
        relayTransport?.stop()
        relayTransport = null
        relayPeerCount = 0
        sendPeerStatus()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Messenger",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description = "Фоновая работа mesh-сети"

            val incomingChannel = NotificationChannel(
                INCOMING_CHANNEL_ID,
                "Входящие сообщения",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых сообщениях"
                setSound(
                    android.net.Uri.parse(
                        "android.resource://$packageName/${R.raw.mesh_messenger_tron_notification}"
                    ),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(channel)
                createNotificationChannel(incomingChannel)
            }
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

    private fun showIncomingMessageNotification(
        text: String,
        sourceId: String,
        packetId: String?
    ) {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationId = 10_000 + ((packetId ?: "$sourceId:$text").hashCode() and 0x7FFF_FFFF) % 90_000

        val builder = NotificationCompat.Builder(this, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh_notification_active)
            .setContentTitle("Новое сообщение")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (Build.VERSION.SDK_INT < 26) {
            builder.setSound(
                android.net.Uri.parse(
                    "android.resource://$packageName/${R.raw.mesh_messenger_tron_notification}"
                )
            )
        }

        getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
    }

    private fun sendPeerStatus() {
        // UI status broadcasts may be repeated for lifecycle/replay requests,
        // but diagnostic logging should only record actual count changes.
        val detail = "bt=$btPeerCount,relay=$relayPeerCount"
        if (detail != lastPeerStatusDiagnostic) {
            lastPeerStatusDiagnostic = detail
            diagnostics.event("PEER_STATUS", detail)
        }
        val intent = Intent(ACTION_PEER_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_BT_COUNT, btPeerCount)
            putExtra(EXTRA_RELAY_COUNT, relayPeerCount)
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
        diagnostics.event("STATUS", text)
        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            notification(text)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceDestroyed = true
        retryHandler.removeCallbacks(autoBugReportRunnable)
        retryHandler.removeCallbacks(watchdogRunnable)
        retryHandler.removeCallbacks(retryRunnable)
        bluetoothMonitor.stop()
        stopMesh()
        stopRelayLayer()
        super.onDestroy()
    }
}