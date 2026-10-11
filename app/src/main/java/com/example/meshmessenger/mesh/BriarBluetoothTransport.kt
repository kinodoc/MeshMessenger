package com.example.meshmessenger.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Briar-style Classic Bluetooth transport for MeshMessenger.
 *
 * Independent implementation of the transport contract used by Briar/Bramble:
 * RFCOMM listener, controlled discovery handoff, one connection per peer,
 * serialized writes, bounded frames and bounded reconnect backoff.
 * MeshMessenger keeps its own packet format and end-to-end crypto.
 *
 * Upstream reference: https://github.com/briar/briar
 * Briar/Bramble source is GPLv3; this class is not a source copy.
 */
class BriarBluetoothTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val serviceUuid: UUID,
    private val helloPayload: () -> ByteArray,
    private val onDiagnostic: (String, String) -> Unit,
    private val onFrame: (String, ByteArray) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onConnectFinished: (String, Boolean) -> Unit = { _, _ -> }
) {
    companion object {
        private const val SERVICE_NAME = "RFCOMM"
        private const val MAX_FRAME = 1024 * 1024
        private const val INITIAL_RETRY_MS = 1_500L
        private const val MAX_RETRY_MS = 30_000L
    }

    private val io = Executors.newCachedThreadPool { r -> Thread(r, "BriarBtTransport").apply { isDaemon = true } }
    // BluetoothSocket.connect() is a blocking platform call. Keep it on a dedicated
    // single-thread executor so discovery/retry can never create a connection storm.
    private val connectIo = Executors.newSingleThreadExecutor { r -> Thread(r, "BriarBtConnect").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "BriarBtRetry").apply { isDaemon = true } }
    private val sockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val writers = ConcurrentHashMap<String, Any>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val retryDelay = ConcurrentHashMap<String, Long>()
    private val retryTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val connectTasks = ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptTask: java.util.concurrent.Future<*>? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onDiagnostic("BT_BRIAR", "start_skipped=missing_connect_permission")
            return
        }
        running = true
        runCatching {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, serviceUuid)
            acceptTask = io.submit { acceptLoop() }
            onDiagnostic("BT_BRIAR", "server=ready mode=insecure_rfcOMM")
        }.onFailure {
            running = false
            onDiagnostic("BT_BRIAR", "server_failed=" + it.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (running) {
            val socket = try { serverSocket?.accept() } catch (_: Throwable) { null } ?: break
            if (!running) { closeQuietly(socket); break }
            attach(socket, "incoming")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, peerServiceUuid: UUID) {
        if (!running || address.isBlank()) return
        if (sockets.containsKey(address) || !connecting.add(address)) {
            if (sockets.containsKey(address)) onDiagnostic("BT_CONNECT_SKIP", "already_connected address=**" + address.takeLast(5))
            return
        }
        cancelRetry(address)
        onDiagnostic("BT_CONNECT_QUEUE", "address=**" + address.takeLast(5) + " " + snapshot())
        val startedAt = System.nanoTime()
        val task = connectIo.submit {
            var socket: BluetoothSocket? = null
            var connected = false
            try {
                if (!running) return@submit
                val discovering = runCatching { adapter.isDiscovering }.getOrDefault(false)
                onDiagnostic("BT_CONNECT_STATE", "address=**" + address.takeLast(5) + " discovery=" + discovering + " " + snapshot())
                if (discovering) runCatching { adapter.cancelDiscovery() }
                val device = adapter.getRemoteDevice(address)
                onDiagnostic("BT_BRIAR", "connect_start address=**" + address.takeLast(5))
                socket = device.createInsecureRfcommSocketToServiceRecord(peerServiceUuid)
                socket.connect()
                if (!running) {
                    closeQuietly(socket)
                    return@submit
                }
                attach(socket, "outgoing")
                connected = true
                retryDelay.remove(address)
                onDiagnostic("BT_CONNECT_COMPLETE", "address=**" + address.takeLast(5) + " result=connected elapsed_ms=" + ((System.nanoTime() - startedAt) / 1_000_000L) + " " + snapshot())
            } catch (t: Throwable) {
                closeQuietly(socket)
                onDiagnostic("BT_CONNECT_FAIL", "address=**" + address.takeLast(5) + " error=" + t.javaClass.simpleName + " elapsed_ms=" + ((System.nanoTime() - startedAt) / 1_000_000L) + " " + snapshot())
                scheduleRetry(address, peerServiceUuid)
            } finally {
                onConnectFinished(address, connected)
                connecting.remove(address)
                connectTasks.remove(address)
                onDiagnostic("BT_CONNECT_COMPLETE", "address=**" + address.takeLast(5) + " result=finished elapsed_ms=" + ((System.nanoTime() - startedAt) / 1_000_000L) + " " + snapshot())
            }
        }
        connectTasks[address] = task
    }

    @SuppressLint("MissingPermission")
    private fun attach(socket: BluetoothSocket, direction: String) {
        val address = runCatching { socket.remoteDevice.address }.getOrDefault("unknown")
        val old = sockets.putIfAbsent(address, socket)
        if (old != null) { closeQuietly(socket); onDiagnostic("BT_BRIAR", "duplicate_connection address=**" + address.takeLast(5)); return }
        writers[address] = Any()
        connecting.remove(address)
        cancelRetry(address)
        onDiagnostic("BT_BRIAR", "connected direction=" + direction + " address=**" + address.takeLast(5) + " " + snapshot())
        // Start the reader before notifying the node and before sending HELLO.
        // This prevents the first inbound frame from racing the connection callback.
        io.submit { readLoop(address, socket) }
        onConnected(address)
        val hello = helloPayload()
        if (send(address, hello)) {
            onDiagnostic("BT_HELLO_TX", "initial address=**" + address.takeLast(5) + " bytes=" + hello.size)
        }
    }

    private fun readLoop(address: String, socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            while (running && socket.isConnected) {
                val length = readInt(input)
                require(length in 1..MAX_FRAME) { "bad_frame_length=" + length }
                val payload = ByteArray(length)
                readFully(input, payload)
                onDiagnostic("BT_BRIAR_RX", "address=**" + address.takeLast(5) + " bytes=" + length)
                onFrame(address, payload)
            }
        } catch (t: Throwable) {
            if (running) onDiagnostic("BT_BRIAR", "read_end address=**" + address.takeLast(5) + " error=" + t.javaClass.simpleName)
        } finally {
            remove(address, socket)
        }
    }

    private fun readInt(input: InputStream): Int {
        val header = ByteArray(4)
        readFully(input, header)
        return ByteBuffer.wrap(header).int
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }

    fun markReady(address: String): Boolean {
        if (!sockets.containsKey(address)) return false
        ready.add(address)
        retryDelay.remove(address)
        cancelRetry(address)
        onDiagnostic("BT_BRIAR", "ready=true address=**" + address.takeLast(5))
        return true
    }

    fun send(address: String, bytes: ByteArray): Boolean {
        val socket = sockets[address] ?: return false
        if (!socket.isConnected || bytes.isEmpty() || bytes.size > MAX_FRAME) return false
        val lock = writers[address] ?: return false
        return synchronized(lock) {
            runCatching {
                val output: OutputStream = socket.outputStream
                output.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                output.write(bytes)
                output.flush()
                onDiagnostic("BT_BRIAR_TX", "address=**" + address.takeLast(5) + " bytes=" + bytes.size)
                true
            }.getOrElse {
                onDiagnostic("BT_BRIAR", "write_failed address=**" + address.takeLast(5) + " error=" + it.javaClass.simpleName)
                remove(address, socket)
                false
            }
        }
    }

    fun readyAddresses(): Set<String> = ready.filter { sockets.containsKey(it) }.toSet()
    fun isConnected(address: String): Boolean = sockets[address]?.isConnected == true

    private fun scheduleRetry(address: String, serviceUuid: UUID) {
        if (!running || sockets.containsKey(address) || retryTasks.containsKey(address)) return
        val previous = retryDelay[address] ?: INITIAL_RETRY_MS
        retryDelay[address] = min(previous * 2, MAX_RETRY_MS)
        val task = scheduler.schedule({
            retryTasks.remove(address)
            if (running && !sockets.containsKey(address)) connect(address, serviceUuid)
        }, previous, TimeUnit.MILLISECONDS)
        retryTasks[address] = task
        onDiagnostic("BT_BRIAR", "retry_scheduled address=**" + address.takeLast(5) + " delay_ms=" + previous)
    }

    private fun cancelRetry(address: String) { retryTasks.remove(address)?.cancel(false) }

    private fun snapshot(): String = "running=" + running +
        " connecting=" + connecting.size +
        " queued=" + connectTasks.size +
        " sockets=" + sockets.size +
        " ready=" + ready.size +
        " retries=" + retryTasks.size

    private fun remove(address: String, socket: BluetoothSocket) {
        if (!sockets.remove(address, socket)) return
        ready.remove(address)
        writers.remove(address)
        closeQuietly(socket)
        onDisconnected(address)
        onDiagnostic("BT_BRIAR", "disconnected address=**" + address.takeLast(5) + " " + snapshot())
    }

    fun stop() {
        running = false
        retryTasks.values.forEach { it.cancel(false) }
        retryTasks.clear()
        connectTasks.values.forEach { it.cancel(true) }
        connectTasks.clear()
        retryDelay.clear()
        acceptTask?.cancel(true)
        acceptTask = null
        sockets.values.forEach { closeQuietly(it) }
        sockets.clear()
        writers.clear()
        ready.clear()
        connecting.clear()
        closeQuietly(serverSocket)
        serverSocket = null
        // Keep the executors alive so a foreground-service stop/start cycle can
        // restart the transport without constructing a new MeshBluetoothNode.
        onDiagnostic("BT_BRIAR", "stopped")
    }

    private fun closeQuietly(socket: BluetoothSocket?) { runCatching { socket?.close() } }
    private fun closeQuietly(socket: BluetoothServerSocket?) { runCatching { socket?.close() } }
}
