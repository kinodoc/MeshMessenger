package com.example.meshmessenger.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Classic Bluetooth RFCOMM transport following Briar's Bluetooth transport model. */
class MeshRfcommTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val serviceUuid: UUID,
    private val helloPayload: () -> ByteArray,
    private val onDiagnostic: (String, String) -> Unit,
    private val onFrame: (String, ByteArray) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    companion object {
        private const val SERVICE_NAME = "MeshMessenger"
        private const val MAX_FRAME = 1024 * 1024
    }

    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "MeshRfcomm").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val writers = ConcurrentHashMap<String, Any>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val reads = ConcurrentHashMap<String, Future<*>>()
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptTask: Future<*>? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onDiagnostic("BLE_RFCOMM", "skipped_missing_connect_permission")
            return
        }
        running = true
        runCatching {
            // Briar uses insecure RFCOMM so an unpaired peer can connect.
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, serviceUuid)
            onDiagnostic("BLE_RFCOMM", "server_opened mode=insecure")
            acceptTask = io.submit { acceptLoop() }
        }.onFailure {
            running = false
            onDiagnostic("BLE_RFCOMM", "server_failed=${it.javaClass.simpleName}:${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (running) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: break
            if (!running) {
                runCatching { socket.close() }
                break
            }
            attach(socket, "incoming")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, peerServiceUuid: UUID) {
        if (!running) return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        if (sockets.containsKey(address)) return
        io.submit {
            var socket: BluetoothSocket? = null
            try {
                onDiagnostic(
                    "BLE_RFCOMM",
                    "connect_start mode=insecure uuid=" + peerServiceUuid + " bonded=" +
                        runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false) +
                        " address=**" + address.takeLast(5)
                )
                adapter.cancelDiscovery()

                // Do not try secure RFCOMM first. Briar deliberately uses the
                // insecure API because it does not require Bluetooth pairing.
                socket = device.createInsecureRfcommSocketToServiceRecord(peerServiceUuid)
                socket.connect()
                attach(socket, "outgoing")
            } catch (t: Throwable) {
                runCatching { socket?.close() }
                onDiagnostic("BLE_RFCOMM", "connect_failed address=**${address.takeLast(5)} error=${t.javaClass.simpleName}:${t.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attach(socket: BluetoothSocket, direction: String) {
        val address = runCatching { socket.remoteDevice.address }.getOrDefault("unknown")
        val old = sockets.putIfAbsent(address, socket)
        if (old != null) {
            runCatching { socket.close() }
            onDiagnostic("BLE_RFCOMM", "duplicate_socket_closed address=**${address.takeLast(5)}")
            return
        }
        writers[address] = Any()
        onDiagnostic("BLE_RFCOMM", "socket_connected direction=${direction} address=**${address.takeLast(5)}")
        onConnected(address)
        send(address, helloPayload())
        reads[address] = io.submit { readLoop(address, socket) }
    }

    private fun readLoop(address: String, socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            while (running && socket.isConnected) {
                val length = readInt(input)
                if (length <= 0 || length > MAX_FRAME) throw IllegalArgumentException("bad_frame_length=${length}")
                val payload = ByteArray(length)
                readFully(input, payload)
                onDiagnostic("BLE_RFCOMM_RX", "address=**${address.takeLast(5)} bytes=${length}")
                onFrame(address, payload)
            }
        } catch (t: Throwable) {
            if (running) onDiagnostic("BLE_RFCOMM", "read_end address=**${address.takeLast(5)} error=${t.javaClass.simpleName}")
        } finally {
            remove(address, socket)
        }
    }

    private fun readInt(input: InputStream): Int {
        val h = ByteArray(4)
        readFully(input, h)
        return ByteBuffer.wrap(h).int
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var off = 0
        while (off < target.size) {
            val n = input.read(target, off, target.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    fun markReady(address: String): Boolean {
        if (!sockets.containsKey(address)) return false
        ready.add(address)
        onDiagnostic("BLE_RFCOMM", "ready=true address=**${address.takeLast(5)}")
        return true
    }

    fun send(address: String, bytes: ByteArray): Boolean {
        val socket = sockets[address] ?: return false
        if (!socket.isConnected || bytes.isEmpty() || bytes.size > MAX_FRAME) return false
        val lock = writers[address] ?: return false
        return synchronized(lock) {
            runCatching {
                val out: OutputStream = socket.outputStream
                out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                out.write(bytes)
                out.flush()
                onDiagnostic("BLE_RFCOMM_TX", "address=**${address.takeLast(5)} bytes=${bytes.size}")
                true
            }.getOrElse {
                onDiagnostic("BLE_RFCOMM", "write_failed address=**${address.takeLast(5)} error=${it.javaClass.simpleName}")
                false
            }
        }
    }

    fun readyAddresses(): Set<String> = ready.filter { sockets.containsKey(it) }.toSet()

    private fun remove(address: String, socket: BluetoothSocket) {
        if (sockets.remove(address, socket)) {
            ready.remove(address)
            writers.remove(address)
            reads.remove(address)?.cancel(false)
            runCatching { socket.close() }
            onDisconnected(address)
            onDiagnostic("BLE_RFCOMM", "socket_closed address=**${address.takeLast(5)}")
        }
    }

    fun stop() {
        running = false
        acceptTask?.cancel(true)
        acceptTask = null
        reads.values.forEach { it.cancel(true) }
        reads.clear()
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        ready.clear()
        writers.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        onDiagnostic("BLE_RFCOMM", "stopped")
    }
}
