package com.example.meshmessenger.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restarts the persistent foreground mesh service after boot or app replacement.
 * The service itself decides whether Mesh is enabled and which transport mode to run.
 */
class MeshBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val serviceIntent = Intent(context, MeshForegroundService::class.java)
                    .setAction(MeshForegroundService.ACTION_APP_START)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }.onFailure {
                    android.util.Log.e("MeshMessenger", "Auto-start Mesh service failed", it)
                }
            }
        }
    }
}
