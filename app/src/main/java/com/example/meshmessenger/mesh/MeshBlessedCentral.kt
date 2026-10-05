package com.example.meshmessenger.mesh

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.bluetooth.le.ScanResult
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import java.util.UUID

/** BLESSED-based BLE central: serialized scan/connect/GATT operations. */
class MeshBlessedCentral(
    context: Context,
    private val serviceUuid: UUID,
    private val rxUuid: UUID,
    private val txUuid: UUID,
    private val helloPayload: () -> ByteArray,
    private val onDiagnostic: (String, String) -> Unit,
    private val onFragment: (String, ByteArray) -> Unit,
    private val onReady: (String, Boolean) -> Unit,
    private val onWrite: (String, Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val connected = LinkedHashMap<String, BluetoothPeripheral>()
    private val ready = mutableSetOf<String>()
    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            val address = peripheral.address
            val rx = peripheral.getCharacteristic(serviceUuid, rxUuid)
            val tx = peripheral.getCharacteristic(serviceUuid, txUuid)
            onDiagnostic("BLE_BLESSED_SERVICES", "discovered address=**" + address.takeLast(5) +
                " rx=" + (rx != null) + " tx=" + (tx != null))
            if (rx == null || tx == null) {
                peripheral.cancelConnection()
                return
            }
            val started = peripheral.setNotify(tx, true)
            onDiagnostic("BLE_BLESSED_NOTIFY", "start=" + started + " address=**" + address.takeLast(5))
            if (!started) peripheral.cancelConnection()
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            val address = peripheral.address
            onDiagnostic("BLE_BLESSED_NOTIFY", "status=" + status + " address=**" + address.takeLast(5))
            if (characteristic.uuid != txUuid || status != GattStatus.SUCCESS) {
                if (status != GattStatus.SUCCESS) peripheral.cancelConnection()
                return
            }
            val started = peripheral.writeCharacteristic(serviceUuid, rxUuid, helloPayload(), WriteType.WITH_RESPONSE)
            onDiagnostic("BLE_BLESSED_HELLO", "write_started=" + started + " address=**" + address.takeLast(5))
            if (!started) {
                peripheral.cancelConnection()
            }
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (characteristic.uuid != txUuid) return
            val address = peripheral.address
            onDiagnostic("BLE_BLESSED_RX", "status=" + status + " bytes=" + value.size +
                " address=**" + address.takeLast(5))
            if (status == GattStatus.SUCCESS) onFragment(address, value.copyOf())
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (characteristic.uuid != rxUuid) return
            val address = peripheral.address
            val ok = status == GattStatus.SUCCESS
            onDiagnostic("BLE_BLESSED_WRITE", "status=" + status + " bytes=" + value.size +
                " address=**" + address.takeLast(5))
            if (value.contentEquals(helloPayload())) {
                if (ok) {
                    ready.add(address)
                    onReady(address, true)
                } else {
                    onReady(address, false)
                }
            } else {
                onWrite(address, ok)
            }
        }

        override fun onMtuChanged(peripheral: BluetoothPeripheral, mtu: Int, status: GattStatus) {
            onDiagnostic("BLE_BLESSED_MTU", "mtu=" + mtu + " status=" + status)
        }

        override fun onConnectionUpdated(peripheral: BluetoothPeripheral, interval: Int, latency: Int, timeout: Int, status: GattStatus) {
            onDiagnostic("BLE_BLESSED_CONNECTION", "interval=" + interval + " latency=" + latency +
                " timeout=" + timeout + " status=" + status)
        }
    }

    private val central: BluetoothCentralManager = BluetoothCentralManager(
        context.applicationContext,
        object : BluetoothCentralManagerCallback() {
            override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
                connected[peripheral.address] = peripheral
                onDiagnostic("BLE_BLESSED_CONNECT", "connected address=**" + peripheral.address.takeLast(5))
            }

            override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
                onDiagnostic("BLE_BLESSED_CONNECT", "failed status=" + status)
                onReady(peripheral.address, false)
                handler.postDelayed({ startScan() }, 1000L)
            }

            override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
                val address = peripheral.address
                connected.remove(address)
                ready.remove(address)
                onReady(address, false)
                onDiagnostic("BLE_BLESSED_CONNECT", "disconnected status=" + status +
                    " address=**" + address.takeLast(5))
                handler.postDelayed({ startScan() }, 500L)
            }

            override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
                if (connected.containsKey(peripheral.address)) return
                onDiagnostic("BLE_BLESSED_SCAN_MATCH", "rssi=" + scanResult.rssi +
                    " address=**" + peripheral.address.takeLast(5))
                central.stopScan()
                central.connectPeripheral(peripheral, peripheralCallback)
            }

            override fun onScanFailed(scanFailure: com.welie.blessed.ScanFailure) {
                onDiagnostic("BLE_BLESSED_SCAN", "failed code=" + scanFailure.value)
                handler.postDelayed({ startScan() }, 1000L)
            }
        },
        handler
    )

    fun start() {
        onDiagnostic("BLE_BLESSED", "start")
        startScan()
    }

    private fun startScan() {
        handler.post {
            runCatching {
                central.scanForPeripheralsWithServices(arrayOf(serviceUuid))
                onDiagnostic("BLE_BLESSED_SCAN", "started service_filter=true")
            }.onFailure {
                onDiagnostic("BLE_BLESSED_SCAN", "exception=" + it.javaClass.simpleName)
            }
        }
    }

    fun write(address: String, bytes: ByteArray): Boolean {
        val peripheral = connected[address] ?: return false
        if (!ready.contains(address)) return false
        return peripheral.writeCharacteristic(serviceUuid, rxUuid, bytes.copyOf(), WriteType.WITH_RESPONSE)
    }

    fun isReady(): Boolean = ready.isNotEmpty()
    fun readyAddresses(): Set<String> = ready.toSet()
    fun connectedCount(): Int = connected.size
    fun isRunning(): Boolean = true

    fun stop() {
        central.stopScan()
        connected.values.toList().forEach { runCatching { it.cancelConnection() } }
        connected.clear()
        ready.clear()
        onDiagnostic("BLE_BLESSED", "stopped")
        central.close()
    }
}
