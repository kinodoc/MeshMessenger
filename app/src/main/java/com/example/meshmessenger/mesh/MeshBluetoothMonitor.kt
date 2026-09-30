package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Records Bluetooth adapter/profile events without changing Bluetooth state. */
class MeshBluetoothMonitor(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val diagnostics: MeshDiagnostics
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    diagnostics.event("BT_ADAPTER_STATE", "state=${stateName(state)}")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = deviceFromIntent(intent)
                    val action = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "CONNECTED" else "DISCONNECTED"
                    diagnostics.event("BT_ACL", "state=${action},device=${deviceLabel(device)}")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = deviceFromIntent(intent)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    diagnostics.event("BT_BOND", "state=${bondName(state)},device=${deviceLabel(device)}")
                }
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                    recordProfileChange(intent, "A2DP")
                }
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> {
                    recordProfileChange(intent, "HEADSET")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        diagnostics.event("BT_MONITOR_START", snapshot())
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        diagnostics.event("BT_MONITOR_STOP")
    }

    @SuppressLint("MissingPermission")
    fun snapshot(): String {
        val a = adapter ?: return "adapter=null"
        val enabled = runCatching { a.isEnabled }.getOrDefault(false)
        val state = runCatching { stateName(a.state) }.getOrDefault("UNKNOWN")
        val a2dp = runCatching { profileStateName(a.getProfileConnectionState(BluetoothProfile.A2DP)) }.getOrDefault("UNKNOWN")
        val headset = runCatching { profileStateName(a.getProfileConnectionState(BluetoothProfile.HEADSET)) }.getOrDefault("UNKNOWN")
        return "enabled=${enabled},state=${state},a2dp=${a2dp},headset=${headset}"
    }

    @SuppressLint("MissingPermission")
    private fun recordProfileChange(intent: Intent, profile: String) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val device = deviceFromIntent(intent)
        diagnostics.event(
            "BT_PROFILE",
            "profile=${profile},state=${profileStateName(state)},device=${deviceLabel(device)}"
        )
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice?): String {
        if (device == null) return "null"
        val safeName = runCatching { device.name.orEmpty() }
            .getOrDefault("")
            .replace(Regex("[\\r\\n|]"), " ")
            .take(60)
        val address = device.address.orEmpty()
        val masked = if (address.length >= 5) "**:**:**:**:" + address.takeLast(5) else "masked"
        val bond = runCatching { bondName(device.bondState) }.getOrDefault("UNKNOWN")
        return "name=${safeName},address=${masked},bond=${bond}"
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "UNKNOWN_${state}"
    }

    private fun bondName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN_${state}"
    }

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private fun profileStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN_${state}"
    }
}
