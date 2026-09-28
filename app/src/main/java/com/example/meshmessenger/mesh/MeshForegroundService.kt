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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.meshmessenger.MainActivity

/** Keeps the mesh transports alive while the UI is not visible. */
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mesh_runtime"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.meshmessenger.START_MESH"
        const val ACTION_STOP = "com.example.meshmessenger.STOP_MESH"
        const val ACTION_SEND_IP = "com.example.meshmessenger.SEND_IP"

        const val EXTRA_IP = "ip"
        const val EXTRA_PACKET = "packet"

        const val ACTION_IP_MESSAGE = "com.example.meshmessenger.IP_MESSAGE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOURCE_ID = "source_id"
    }

    private var node: MeshGattNode? = null
    private var ipTransport: MeshIpTransport? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            notification("Mesh работает в фоне")
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                stopMesh()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SEND_IP -> {
                startMesh()

                val ip = intent.getStringExtra(EXTRA_IP)
                val bytes = intent.getByteArrayExtra(EXTRA_PACKET)

                if (!ip.isNullOrBlank() && bytes != null) {
                    val packet = MeshPacket.decode(bytes)

                    if (packet != null) {
                        ipTransport?.send(packet, ip)
                    } else {
                        updateNotification("IP: неверный пакет")
                    }
                }
            }

            else -> {
                startMesh()
            }
        }

        return START_STICKY
    }

    private fun startMesh() {
        if (node != null || ipTransport != null) return

        val identity = IdentityStore(this)

        val router = MeshRouter(
            identity.nodeId,
            identity.keyPair.private
        ).also {
            it.identityPublicBytes = identity.keyPair.public.encoded
        }

        val queue = PendingMessageStore(this)

        ipTransport = MeshIpTransport(
            localId = identity.nodeId,
            router = router,
            queue = queue,
            guard = NetBirdGuard(this),
            onStatus = { updateNotification(it) },
            onMessage = { text, sourceId ->
                handleIpMessage(text, sourceId)
            }
        ).also {
            it.start()
        }

        val adapter = (
            getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
            ).adapter

        if (adapter != null) {
            node = MeshGattNode(
                this,
                adapter,
                identity.nodeId,
                router,
                queue,
                { updateNotification(it) },
                { _, _ ->
                    updateNotification("Получено сообщение")
                }
            ).also {
                it.start()
            }
        }

        updateNotification("BLE + NetBird IP работают")
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
        node?.stop()
        node = null

        ipTransport?.stop()
        ipTransport = null
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
                android.R.drawable.stat_sys_data_bluetooth
            )
            .setContentTitle("Mesh Messenger")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
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
