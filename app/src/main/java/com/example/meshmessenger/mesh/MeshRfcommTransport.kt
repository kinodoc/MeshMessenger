package com.example.meshmessenger.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Minimal Classic Bluetooth RFCOMM chat: one socket and length-prefixed frames. */
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
    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptTask: Future<*>? = null

    fun isRunning(): Boolean = running && serverSocket != null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            onDiagnostic("BLUETOOTH", "rfcomm_missing_connect_permission")
            return
        }
        running = true
        runCatching {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, serviceUuid)
            onDiagnostic("BLUETOOTH", "rfcomm_server_opened")
            acceptTask = io.submit { acceptLoop() }
        }.onFailure {
            running = false
            serverSocket = null
            onDiagnostic("BLUETOOTH", "rfcomm_server_failed=" + it.javaClass.simpleName)
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
    fun connect(device: BluetoothDevice) {
        if (!running || !hasConnectPermission()) return
        val address = device.address
        if (sockets.containsKey(address)) return
        io.submit {
            var socket: BluetoothSocket? = null
            try {
                adapter.cancelDiscovery()
                onDiagnostic("BLUETOOTH", "rfcomm_connect_start address=**" + address.takeLast(5))
                socket = device.createInsecureRfcommSocketToServiceRecord(serviceUuid)
                socket.connect()
                attach(socket, "outgoing")
            } catch (t: Throwable) {
                runCatching { socket?.close() }
                onDiagnostic("BLUETOOTH", "rfcomm_connect_failed address=**" +
                    address.takeLast(5) + " error=" + t.javaClass.simpleName)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attach(socket: BluetoothSocket, direction: String) {
        val address = runCatching { socket.remoteDevice.address }.getOrDefault("unknown")
        val previous = sockets.putIfAbsent(address, socket)
        if (previous != null) {
            runCatching { socket.close() }
            return
        }
        writers[address] = Any()
        onDiagnostic("BLUETOOTH", "rfcomm_connected direction=" + direction +
            " address=**" + address.takeLast(5))
        onConnected(address)
        reads[address] = io.submit { readLoop(address, socket) }
        send(address, helloPayload())
    }

    private fun readLoop(address: String, socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            while (running && socket.isConnected) {
                val header = ByteArray(4)
                readFully(input, header)
                val length = ByteBuffer.wrap(header).int
                if (length <= 0 || length > MAX_FRAME) throw IllegalArgumentException("bad_frame_length")
                val payload = ByteArray(length)
                readFully(input, payload)
                onFrame(address, payload)
            }
        } catch (_: EOFException) {
        } catch (t: Throwable) {
            if (running) onDiagnostic("BLUETOOTH", "rfcomm_read_failed address=**" +
                address.takeLast(5) + " error=" + t.javaClass.simpleName)
        } finally {
            remove(address, socket)
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }

    @SuppressLint("MissingPermission")
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
                true
            }.getOrElse {
                remove(address, socket)
                false
            }
        }
    }

    fun markReady(address: String): Boolean {
        if (!sockets.containsKey(address)) return false
        ready.add(address)
        return true
    }

    fun readyAddresses(): Set<String> = ready.filter { sockets.containsKey(it) }.toSet()

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun remove(address: String, socket: BluetoothSocket) {
        if (sockets.remove(address, socket)) {
            ready.remove(address)
            writers.remove(address)
            reads.remove(address)?.cancel(false)
            runCatching { socket.close() }
            onDisconnected(address)
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
    }
}
