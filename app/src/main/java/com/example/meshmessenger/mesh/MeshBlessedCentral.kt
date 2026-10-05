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
    private val localId: String,
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
    private val helloWriteSucceeded = mutableSetOf<String>()
    private val ready = mutableSetOf<String>()
    private val recoveryAttempts = mutableMapOf<String, Int>()
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
                    helloWriteSucceeded.add(address)
                    onDiagnostic("BLE_BLESSED_HELLO", "write_success_waiting_for_peer_hello address=**" + address.takeLast(5))
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
                recoveryAttempts.remove(peripheral.address)
                onDiagnostic("BLE_BLESSED_CONNECT", "connected_services_ready address=**" + peripheral.address.takeLast(5))
            }

            override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
                val address = peripheral.address
                val attempt = (recoveryAttempts[address] ?: 0) + 1
                recoveryAttempts[address] = attempt
                onDiagnostic("BLE_BLESSED_CONNECT", "failed status=" + status + " attempt=" + attempt + " address=**" + address.takeLast(5))
                onReady(address, false)
                if (attempt <= 1) {
                    handler.postDelayed({
                        onDiagnostic("BLE_BLESSED_RECOVERY", "auto_connect address=**" + address.takeLast(5))
                        central.autoConnectPeripheral(peripheral, peripheralCallback)
                    }, 700L)
                } else {
                    handler.postDelayed({ startScan() }, 1000L)
                }
            }

            override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
                val address = peripheral.address
                val wasReady = ready.contains(address)
                connected.remove(address)
                ready.remove(address)
                helloWriteSucceeded.remove(address)
                onReady(address, false)
                onDiagnostic("BLE_BLESSED_CONNECT", "disconnected status=" + status + " was_ready=" + wasReady +
                    " address=**" + address.takeLast(5))
                if (!wasReady) {
                    val attempt = (recoveryAttempts[address] ?: 0) + 1
                    recoveryAttempts[address] = attempt
                    if (attempt <= 1) {
                        handler.postDelayed({
                            onDiagnostic("BLE_BLESSED_RECOVERY", "auto_connect_after_disconnect address=**" + address.takeLast(5))
                            central.autoConnectPeripheral(peripheral, peripheralCallback)
                        }, 700L)
                        return
                    }
                }
                handler.postDelayed({ startScan() }, 500L)
            }

            override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
                if (connected.containsKey(peripheral.address)) return

                val advertisedNodeId = scanResult.scanRecord
                    ?.getServiceData(android.os.ParcelUuid(serviceUuid))
                    ?.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    ?.takeIf { it.length == 16 }

                onDiagnostic(
                    "BLE_BLESSED_SCAN_MATCH",
                    "rssi=" + scanResult.rssi +
                        " address=**" + peripheral.address.takeLast(5) +
                        " node=" + (advertisedNodeId ?: "unknown")
                )

                // Both Mesh phones advertise the same GATT service and both run
                // a central scanner. If both call connectPeripheral() at once,
                // Android/OEM stacks can race and one side commonly fails with
                // GATT status 133. Use the stable Mesh Node ID as a deterministic
                // initiator election: only the lexicographically smaller node
                // opens the central connection; the other side stays peripheral
                // and accepts the incoming GATT connection.
                if (advertisedNodeId == null) {
                    onDiagnostic(
                        "BLE_BLESSED_ARBITRATION",
                        "skip_missing_node_id address=**" + peripheral.address.takeLast(5)
                    )
                    return
                }
                if (advertisedNodeId == localId) {
                    onDiagnostic(
                        "BLE_BLESSED_ARBITRATION",
                        "skip_self node=" + advertisedNodeId
                    )
                    return
                }
                if (localId.lowercase() > advertisedNodeId.lowercase()) {
                    onDiagnostic(
                        "BLE_BLESSED_ARBITRATION",
                        "passive local=" + localId + " peer=" + advertisedNodeId
                    )
                    return
                }

                onDiagnostic(
                    "BLE_BLESSED_ARBITRATION",
                    "initiator local=" + localId + " peer=" + advertisedNodeId
                )
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

    fun markPeerHello(address: String) {
        if (!connected.containsKey(address) || !helloWriteSucceeded.contains(address)) return
        if (ready.add(address)) {
            onDiagnostic("BLE_BLESSED_READY", "peer_hello=true address=**" + address.takeLast(5))
            onReady(address, true)
            handler.postDelayed({ startScan() }, 300L)
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
        helloWriteSucceeded.clear()
        ready.clear()
        recoveryAttempts.clear()
        onDiagnostic("BLE_BLESSED", "stopped")
        central.close()
    }
}
