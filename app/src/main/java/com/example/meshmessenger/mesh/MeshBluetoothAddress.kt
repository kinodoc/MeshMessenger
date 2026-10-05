package com.example.meshmessenger.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Resolves the Classic Bluetooth address needed for RFCOMM. */
object MeshBluetoothAddress {
    private const val FAKE_ADDRESS = "02:00:00:00:00:00"

    @SuppressLint("MissingPermission", "HardwareIds")
    fun get(context: Context, adapter: BluetoothAdapter): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return null

        runCatching {
            adapter.address
        }.getOrNull()?.takeIf { valid(it) }?.let { return it }

        if (Build.VERSION.SDK_INT < 33) {
            runCatching {
                Settings.Secure.getString(context.contentResolver, "bluetooth_address")
            }.getOrNull()?.takeIf { valid(it) }?.let { return it }
        }

        // Briar uses the framework service as a last resort on Android versions
        // where BluetoothAdapter.getAddress() returns the fake 02:00 address.
        return runCatching {
            val field = adapter.javaClass.getDeclaredField("mService")
            field.isAccessible = true
            val service = field.get(adapter) ?: return@runCatching null
            val method = service.javaClass.getMethod("getAddress")
            (method.invoke(service) as? String)?.takeIf { valid(it) }
        }.getOrNull()
    }

    private fun valid(address: String?): Boolean =
        !address.isNullOrBlank() &&
            BluetoothAdapter.checkBluetoothAddress(address) &&
            !address.equals(FAKE_ADDRESS, ignoreCase = true)
}
