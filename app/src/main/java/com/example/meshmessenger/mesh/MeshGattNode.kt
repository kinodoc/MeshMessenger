package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

/** Bidirectional BLE GATT transport with a durable store-and-forward queue. */
class MeshGattNode(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val localId: String,
    private val router: MeshRouter,
    private val queue: PendingMessageStore,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String, String) -> Unit
) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val peers = mutableMapOf<String, BluetoothGatt>()
    private val notifyReady = mutableSetOf<String>()
    private val service = MeshProtocol.SERVICE_UUID
    private val rx = MeshProtocol.RX_UUID
    private val tx = MeshProtocol.TX_UUID
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val rxCharacteristic = BluetoothGattCharacteristic(rx, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
    private val txCharacteristic = BluetoothGattCharacteristic(tx, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
    private val descriptor = BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (characteristic.uuid == rx) handleIncoming(device.address, value)
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (descriptor.uuid == cccd && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) notifyReady.add(device.address)
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState != BluetoothProfile.STATE_CONNECTED) notifyReady.remove(device.address)
            }
        })
        val gattService = BluetoothGattService(service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        txCharacteristic.addDescriptor(descriptor)
        gattService.addCharacteristic(rxCharacteristic)
        gattService.addCharacteristic(txCharacteristic)
        server?.addService(gattService)

        advertiser = adapter.bluetoothLeAdvertiser
        val adv = advertiser ?: run { onStatus("BLE advertising недоступен"); return }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(android.os.ParcelUuid(service)).build()
        adv.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { onStatus("Mesh активен • BLE relay готов") }
            override fun onStartFailure(errorCode: Int) { onStatus("BLE advertising error: $errorCode") }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (device.address == adapter.address || peers.containsKey(device.address)) return
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    peers[device.address] = g
                    g.requestMtu(247)
                    g.discoverServices()
                } else {
                    peers.remove(device.address)
                    notifyReady.remove(device.address)
                    g.close()
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val remoteService = g.getService(service) ?: return
                val remoteRx = remoteService.getCharacteristic(rx) ?: return
                val remoteTx = remoteService.getCharacteristic(tx) ?: return
                g.setCharacteristicNotification(remoteTx, true)
                val d = remoteTx.getDescriptor(cccd) ?: return
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
                peers[device.address] = g
                flushQueue()
            }
            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == cccd && status == BluetoothGatt.GATT_SUCCESS) {
                    notifyReady.add(device.address)
                    flushQueue()
                }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == tx) handleIncoming(device.address, characteristic.value)
            }
        }
        peers[device.address] = device.connectGatt(context, false, callback)
    }

    private fun handleIncoming(from: String, bytes: ByteArray) {
        val packet = MeshPacket.decode(bytes) ?: return
        val next = router.onReceive(packet)
        if (packet.destinationId == localId) {
            onMessage(router.decryptForLocal(packet) ?: "[не удалось расшифровать]", packet.sourceId)
            queue.remove(packet.messageId)
        } else if (next != null) {
            queue.enqueue(next)
            broadcast(next.encode(), except = from)
        }
    }

    @SuppressLint("MissingPermission")
    fun send(packet: MeshPacket) {
        queue.enqueue(packet)
        flushQueue()
    }

    @SuppressLint("MissingPermission")
    private fun flushQueue() {
        val ready = peers.filterKeys { notifyReady.contains(it) }.values.toList()
        if (ready.isEmpty()) return
        for (entry in queue.snapshot()) {
            val bytes = entry.bytes
            if (bytes.size > 180) continue
            var delivered = false
            for (gatt in ready) {
                val c = gatt.getService(service)?.getCharacteristic(rx) ?: continue
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = bytes
                if (runCatching { gatt.writeCharacteristic(c) }.getOrDefault(false)) delivered = true
            }
            if (delivered) queue.remove(entry.id)
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcast(bytes: ByteArray, except: String? = null) {
        if (bytes.size > 180) return
        for ((address, gatt) in peers.toMap()) {
            if (address == except || !notifyReady.contains(address)) continue
            val characteristic = txCharacteristic.apply { value = bytes }
            runCatching { server?.notifyCharacteristicChanged(gatt.device, characteristic, false) }
        }
    }

    fun stop() {
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        notifyReady.clear()
        server?.close()
        server = null
    }
}
