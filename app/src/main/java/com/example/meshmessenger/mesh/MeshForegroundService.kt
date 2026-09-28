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

/** Keeps the mesh transport alive while the UI is not visible. */
class MeshForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "mesh_runtime"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.meshmessenger.START_MESH"
        const val ACTION_STOP = "com.example.meshmessenger.STOP_MESH"
    }

    private var node: MeshGattNode? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Mesh работает в фоне"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMesh(); stopSelf(); return START_NOT_STICKY }
            else -> startMesh()
        }
        return START_STICKY
    }

    private fun startMesh() {
        if (node != null) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val identity = IdentityStore(this)
        val router = MeshRouter(identity.nodeId, identity.keyPair.private).also { it.identityPublicBytes = identity.keyPair.public.encoded }
        val queue = PendingMessageStore(this)
        node = MeshGattNode(this, adapter, identity.nodeId, router, queue,
            { updateNotification(it) },
            { _, _ -> updateNotification("Получено сообщение") }
        ).also { it.start() }
    }

    private fun stopMesh() {
        node?.stop()
        node = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Mesh Messenger", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Фоновая работа mesh-сети"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Mesh Messenger")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopMesh(); super.onDestroy() }
}
