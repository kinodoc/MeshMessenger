package com.example.meshmessenger.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * BLE L2CAP Credit Based Connection transport.
 *
 * Mirrors the Briar Public Mesh research model:
 * - server opens a dynamic L2CAP PSM;
 * - the PSM is advertised in BLE service data;
 * - peers connect with createL2capChannel(psm);
 * - application frames use a small length prefix over the socket.
 *
 * GATT remains the compatibility fallback for API < 29 or peers that do not
 * advertise an L2CAP PSM.
 */
class MeshL2capTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val localId: String,
    private val helloPayload: () -> ByteArray,
    private val onDiagnostic: (String, String) -> Unit,
    private val onFrame: (String, ByteArray) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    companion object {
        private const val FRAME_HEADER_SIZE = 4
        private const val MAX_FRAME = 8192
    }

    private val io = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MeshL2cap").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val writers = ConcurrentHashMap<String, Any>()
    private val reads = ConcurrentHashMap<String, Future<*>>()
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptTask: Future<*>? = null

    @Volatile private var running = false
    @Volatile var psm: Int = -1
        private set

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @SuppressLint("MissingPermission")
    fun start() {
        if (!isSupported() || running) return
        // BLUETOOTH_CONNECT is a runtime permission only on Android 12+
        // (API 31). Android 10/11 can use the L2CAP CoC API without this
        // runtime gate; requiring it there silently disables L2CAP and leaves
        // the transport stuck at psm=-1.
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onDiagnostic("BLE_L2CAP", "skipped_missing_connect_permission")
            return
        }

        running = true
        runCatching {
            serverSocket = adapter.listenUsingL2capChannel()
            psm = serverSocket?.psm ?: -1
            onDiagnostic("BLE_L2CAP", "server_opened psm=$psm")
            acceptTask = io.submit { acceptLoop() }
        }.onFailure {
            running = false
            psm = -1
            onDiagnostic("BLE_L2CAP", "server_failed=${it.javaClass.simpleName}:${it.message}")
        }
    }

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
    fun connect(device: BluetoothDevice, remotePsm: Int) {
        if (!isSupported() || !running || remotePsm <= 0) return
        val address = device.address
        if (sockets.containsKey(address)) return
        io.submit {
            val socket = runCatching {
                onDiagnostic("BLE_L2CAP", "connect_start address=**${address.takeLast(5)} psm=$remotePsm")
                device.createL2capChannel(remotePsm).also { it.connect() }
            }.getOrElse {
                onDiagnostic("BLE_L2CAP", "connect_failed address=**${address.takeLast(5)} psm=$remotePsm error=${it.javaClass.simpleName}")
                return@submit
            }
            attach(socket, "outgoing")
        }
    }

    @SuppressLint("MissingPermission")
    private fun attach(socket: BluetoothSocket, direction: String) {
        val address = runCatching { socket.remoteDevice.address }.getOrDefault("unknown")
        val old = sockets.putIfAbsent(address, socket)
        if (old != null) {
            runCatching { socket.close() }
            onDiagnostic("BLE_L2CAP", "duplicate_socket_closed address=**${address.takeLast(5)}")
            return
        }
        writers[address] = Any()
        onDiagnostic("BLE_L2CAP", "socket_connected direction=$direction address=**${address.takeLast(5)}")
        onConnected(address)

        val readTask = io.submit { readLoop(address, socket) }
        reads[address] = readTask
        send(address, helloPayload())
    }

    private fun readLoop(address: String, socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            while (running && socket.isConnected) {
                val length = readInt(input)
                if (length <= 0 || length > MAX_FRAME) throw IllegalArgumentException("bad_frame_length=$length")
                val payload = ByteArray(length)
                readFully(input, payload)
                onDiagnostic("BLE_L2CAP_RX", "address=**${address.takeLast(5)} bytes=$length")
                onFrame(address, payload)
            }
        } catch (e: Throwable) {
            if (running) {
                onDiagnostic("BLE_L2CAP", "read_end address=**${address.takeLast(5)} error=${e.javaClass.simpleName}")
            }
        } finally {
            remove(address, socket)
        }
    }

    private fun readInt(input: InputStream): Int {
        val header = ByteArray(FRAME_HEADER_SIZE)
        readFully(input, header)
        return ByteBuffer.wrap(header).int
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
    }

    fun send(address: String, bytes: ByteArray): Boolean {
        val socket = sockets[address] ?: return false
        if (!socket.isConnected) return false
        if (bytes.isEmpty() || bytes.size > MAX_FRAME) return false
        val lock = writers[address] ?: return false
        return synchronized(lock) {
            runCatching {
                val output: OutputStream = socket.outputStream
                output.write(ByteBuffer.allocate(FRAME_HEADER_SIZE).putInt(bytes.size).array())
                output.write(bytes)
                output.flush()
                onDiagnostic("BLE_L2CAP_TX", "address=**${address.takeLast(5)} bytes=${bytes.size}")
                true
            }.getOrElse {
                onDiagnostic("BLE_L2CAP", "write_failed address=**${address.takeLast(5)} error=${it.javaClass.simpleName}")
                false
            }
        }
    }

    /** Marks the socket application-ready after the Mesh HELLO exchange. */
    fun markReady(address: String): Boolean {
        if (!sockets.containsKey(address)) return false
        ready.add(address)
        onDiagnostic("BLE_L2CAP", "ready=true address=**" + address.takeLast(5))
        return true
    }

    fun sendAll(bytes: ByteArray): Int {
        var count = 0
        for (address in sockets.keys.toList()) {
            if (send(address, bytes)) count++
        }
        return count
    }

    fun readyAddresses(): Set<String> = ready.filter { sockets.containsKey(it) }.toSet()
    fun isReady(): Boolean = ready.any { sockets.containsKey(it) }

    private fun remove(address: String, socket: BluetoothSocket) {
        if (sockets.remove(address, socket)) {
            ready.remove(address)
            writers.remove(address)
            reads.remove(address)?.cancel(false)
            runCatching { socket.close() }
            onDisconnected(address)
            onDiagnostic("BLE_L2CAP", "socket_closed address=**${address.takeLast(5)}")
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
        psm = -1
        onDiagnostic("BLE_L2CAP", "stopped")
    }
}
