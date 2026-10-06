package com.example.meshmessenger.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/** Native Android BLE GATT central; no third-party BLE transport stack. */
class MeshNativeCentral(
    private val context: Context,
    private val localId: String,
    private val serviceUuid: UUID,
    private val rxUuid: UUID,
    private val txUuid: UUID,
    @Suppress("UNUSED_PARAMETER") private val allocatorUuid: UUID,
    private val helloPayload: () -> ByteArray,
    private val onDiagnostic: (String, String) -> Unit,
    private val onFragment: (String, ByteArray) -> Unit,
    private val onReady: (String, Boolean) -> Unit,
    private val onWrite: (String, Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scanner: BluetoothLeScanner? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
    }
    private val gatts = mutableMapOf<String, BluetoothGatt>()
    private val rxs = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val ready = mutableSetOf<String>()
    private val helloDone = mutableSetOf<String>()
    private val connecting = mutableSetOf<String>()
    private var scanning = false
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            discover(result)
        }
        override fun onScanFailed(code: Int) {
            scanning = false
            onDiagnostic("BLE_SCAN", "failed code=" + code)
            handler.postDelayed({ startScan() }, 1000L)
        }
    }

    private fun callback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatts[address] = gatt
                connecting.remove(address)
                onDiagnostic("BLE_GATT_CONNECT", "connected address=**" + address.takeLast(5))
                runCatching { gatt.discoverServices() }.onFailure { disconnect(address) }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                val wasReady = ready.remove(address)
                helloDone.remove(address)
                rxs.remove(address)
                connecting.remove(address)
                onReady(address, false)
                gatts.remove(address)?.let { runCatching { it.close() } }
                onDiagnostic("BLE_GATT_CONNECT", "disconnected status=" + status + " was_ready=" + wasReady + " address=**" + address.takeLast(5))
                if (!wasReady) handler.postDelayed({ startScan() }, 500L)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect(address)
                return
            }
            val service = gatt.getService(serviceUuid)
            val rx = service?.getCharacteristic(rxUuid)
            val tx = service?.getCharacteristic(txUuid)
            if (rx == null || tx == null) {
                onDiagnostic("BLE_GATT_SERVICES", "missing_rx_tx address=**" + address.takeLast(5))
                disconnect(address)
                return
            }
            rxs[address] = rx
            runCatching { gatt.setCharacteristicNotification(tx, true) }
            tx.getDescriptor(cccd)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                runCatching { gatt.writeDescriptor(it) }
            }
            handler.postDelayed({ writeHello(address) }, 200L)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == txUuid) onFragment(address, characteristic.value.copyOf())
        }

        @Deprecated("API 33 callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == txUuid) onFragment(address, value.copyOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != rxUuid) return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (characteristic.value.contentEquals(helloPayload())) {
                if (ok) {
                    helloDone.add(address)
                    onDiagnostic("BLE_GATT_HELLO", "write_success address=**" + address.takeLast(5))
                } else {
                    onReady(address, false)
                }
            } else {
                onWrite(address, ok)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!scanning) startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val s = scanner ?: return
        runCatching {
            s.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
            scanning = true
            onDiagnostic("BLE_SCAN", "started native_gatt=true")
        }.onFailure {
            onDiagnostic("BLE_SCAN", "exception=" + it.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover(result: ScanResult) {
        val address = result.device.address
        if (gatts.containsKey(address) || connecting.contains(address)) return
        val node = result.scanRecord?.getManufacturerSpecificData(0xFFFF)
            ?.joinToString("") { "%02x".format(it.toInt() and 255) }
            ?.takeIf { it.length == 16 } ?: return
        if (node == localId || localId.lowercase() > node.lowercase()) return
        connecting.add(address)
        scanner?.stopScan(scanCallback)
        scanning = false
        onDiagnostic("BLE_SCAN_MATCH", "node=" + node + " rssi=" + result.rssi + " address=**" + address.takeLast(5))
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                result.device.connectGatt(context, false, callback(address), BluetoothDevice.TRANSPORT_LE)
            } else {
                result.device.connectGatt(context, false, callback(address))
            }
        }.onFailure {
            connecting.remove(address)
            handler.postDelayed({ startScan() }, 500L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeHello(address: String) {
        val gatt = gatts[address] ?: return
        val characteristic = rxs[address] ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = helloPayload()
        val started = runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)
        onDiagnostic("BLE_GATT_HELLO", "write_started=" + started + " address=**" + address.takeLast(5))
    }

    fun markPeerHello(address: String) {
        if (!gatts.containsKey(address) || !helloDone.contains(address)) return
        if (ready.add(address)) {
            onReady(address, true)
            handler.postDelayed({ startScan() }, 300L)
        }
    }

    @SuppressLint("MissingPermission")
    fun write(address: String, bytes: ByteArray): Boolean {
        val gatt = gatts[address] ?: return false
        if (!ready.contains(address)) return false
        val characteristic = rxs[address] ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = bytes.copyOf()
        return runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)
    }

    fun readyAddresses(): Set<String> = ready.toSet()
    fun isReady(): Boolean = ready.isNotEmpty()
    fun isRunning(): Boolean = true

    @SuppressLint("MissingPermission")
    private fun disconnect(address: String) {
        gatts.remove(address)?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        rxs.remove(address)
        ready.remove(address)
        helloDone.remove(address)
        connecting.remove(address)
        onReady(address, false)
        handler.postDelayed({ startScan() }, 500L)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { scanner?.stopScan(scanCallback) }
        scanning = false
        gatts.values.toList().forEach {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatts.clear()
        rxs.clear()
        ready.clear()
        helloDone.clear()
        connecting.clear()
        onDiagnostic("BLE_GATT", "native_central_stopped")
    }
}
