package com.example.meshmessenger.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import java.util.concurrent.FutureTask

/**
 * MeshMessenger's new Classic Bluetooth RFCOMM I/O layer.
 *
 * Responsibilities are deliberately limited to socket ownership, framing,
 * serialized writes, and deterministic cleanup. Discovery and retry policy
 * live in MeshBluetoothNode, so there is only one authority deciding when to
 * start a connection attempt.
 *
 * This is an independent implementation using Android APIs; it is not copied
 * from Briar/Bramble. It carries MeshMessenger's existing frame and HELLO
 * payload for compatibility with the current node.
 */
class MeshClassicBluetoothTransport(
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
    }

    private enum class Direction { INCOMING, OUTGOING }

    private class Session(
        val address: String,
        val socket: BluetoothSocket,
        val direction: Direction
    ) {
        val writerLock = Any()
        @Volatile var closed = false
    }

    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "MeshClassicBt-IO").apply { isDaemon = true }
    }
    private val connectIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MeshClassicBt-Connect").apply { isDaemon = true }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val pendingSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val connectTasks = ConcurrentHashMap<String, FutureTask<Unit>>()
    private val ready = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var acceptTask: Future<*>? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (
            Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            onDiagnostic("BT_TRANSPORT", "start_skipped=missing_connect_permission")
            return
        }

        running = true
        try {
            val listener = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, serviceUuid)
            serverSocket = listener
            acceptTask = io.submit { acceptLoop(listener) }
            onDiagnostic("BT_TRANSPORT", "server=ready mode=insecure_rfcomm")
        } catch (error: Exception) {
            running = false
            closeQuietly(serverSocket)
            serverSocket = null
            onDiagnostic("BT_TRANSPORT", "server_failed=\${error.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(listener: BluetoothServerSocket) {
        while (running) {
            val socket = try {
                listener.accept()
            } catch (error: Exception) {
                if (running) {
                    onDiagnostic("BT_TRANSPORT", "accept_failed=\${error.javaClass.simpleName}")
                }
                break
            }

            if (!running) {
                closeQuietly(socket)
                break
            }
            registerSocket(socket, Direction.INCOMING)
        }
    }

    /**
     * Starts exactly one attempt for this address. Failed attempts are reported
     * to the node; this class intentionally does not schedule its own retries.
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String, peerServiceUuid: UUID) {
        if (!running || address.isBlank()) return
        if (sessions.containsKey(address) || !connecting.add(address)) {
            onDiagnostic(
                "BT_CONNECT_SKIP",
                "reason=\${if (sessions.containsKey(address)) "socket_exists" else "attempt_exists"} address=**\${address.takeLast(5)}"
            )
            return
        }

        val startedAt = System.nanoTime()
        lateinit var task: FutureTask<Unit>
        task = FutureTask(Runnable {
            var socket: BluetoothSocket? = null
            var registered = false
            try {
                if (!running || sessions.containsKey(address)) return@Runnable

                if (runCatching { adapter.isDiscovering }.getOrDefault(false)) {
                    runCatching { adapter.cancelDiscovery() }
                }

                val device = adapter.getRemoteDevice(address)
                val created = device.createInsecureRfcommSocketToServiceRecord(peerServiceUuid)
                socket = created

                // Re-check after the blocking object creation. An incoming
                // connection may have won the race while this attempt started.
                if (!running || sessions.containsKey(address)) {
                    closeQuietly(created)
                    socket = null
                    return@Runnable
                }

                val existingPending = pendingSockets.putIfAbsent(address, created)
                if (existingPending != null) {
                    closeQuietly(created)
                    socket = null
                    onDiagnostic("BT_CONNECT_SKIP", "reason=pending_socket_exists address=**\${address.takeLast(5)}")
                    return@Runnable
                }

                onDiagnostic(
                    "BT_CONNECT_STATE",
                    "address=**\${address.takeLast(5)} discovery=\${runCatching { adapter.isDiscovering }.getOrDefault(false)}"
                )
                created.connect()

                if (!running) {
                    closeQuietly(created)
                    socket = null
                    return@Runnable
                }

                // If an incoming socket removed/closed this pending socket,
                // do not register a second connection for the same peer.
                if (pendingSockets.remove(address, created)) {
                    val session = registerSocket(created, Direction.OUTGOING)
                    registered = session != null && isConnected(address)
                    if (registered) socket = null
                } else {
                    closeQuietly(created)
                    socket = null
                }
            } catch (error: Exception) {
                if (running) {
                    onDiagnostic(
                        "BT_CONNECT_FAIL",
                        "address=**\${address.takeLast(5)} error=\${error.javaClass.simpleName} elapsed_ms=\${elapsedMs(startedAt)}"
                    )
                }
            } finally {
                socket?.let {
                    pendingSockets.remove(address, it)
                    closeQuietly(it)
                }
                connecting.remove(address)
                connectTasks.remove(address, task)
                onDiagnostic(
                    "BT_CONNECT_COMPLETE",
                    "address=**\${address.takeLast(5)} result=\${if (registered) "connected" else "finished"} elapsed_ms=\${elapsedMs(startedAt)} " + snapshot()
                )
                onConnectFinished(address, registered)
            }
        }, Unit)

        if (connectTasks.putIfAbsent(address, task) != null) {
            connecting.remove(address)
            return
        }

        try {
            connectIo.execute(task)
            onDiagnostic("BT_CONNECT_QUEUE", "address=**\${address.takeLast(5)} " + snapshot())
        } catch (error: Exception) {
            connectTasks.remove(address, task)
            connecting.remove(address)
            onDiagnostic("BT_CONNECT_FAIL", "address=**\${address.takeLast(5)} error=\${error.javaClass.simpleName}")
            onConnectFinished(address, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSocket(socket: BluetoothSocket, direction: Direction): Session? {
        val address = runCatching { socket.remoteDevice.address }.getOrDefault("unknown")
        if (address.isBlank() || address == "unknown") {
            closeQuietly(socket)
            onDiagnostic("BT_TRANSPORT", "socket_rejected=missing_remote_address")
            return null
        }

        if (sessions.containsKey(address)) {
            closeQuietly(socket)
            onDiagnostic("BT_TRANSPORT", "duplicate_socket_closed direction=\${direction.name.lowercase()} address=**\${address.takeLast(5)}")
            return null
        }

        if (direction == Direction.INCOMING) {
            // Closing a pending outbound socket unblocks BluetoothSocket.connect()
            // when an inbound connection for the same peer has already won.
            pendingSockets.remove(address)?.let {
                closeQuietly(it)
                onDiagnostic("BT_TRANSPORT", "outgoing_attempt_cancelled_by_incoming address=**\${address.takeLast(5)}")
            }
        }

        val session = Session(address, socket, direction)
        val old = sessions.putIfAbsent(address, session)
        if (old != null) {
            closeQuietly(socket)
            onDiagnostic("BT_TRANSPORT", "duplicate_socket_race_closed address=**\${address.takeLast(5)}")
            return null
        }

        onDiagnostic(
            "BT_TRANSPORT",
            "socket_connected direction=\${direction.name.lowercase()} address=**\${address.takeLast(5)} " + snapshot()
        )

        // Start the reader before sending the first HELLO/frame.
        io.submit { readLoop(session) }
        onConnected(address)

        val hello = helloPayload()
        if (sendSession(session, hello)) {
            onDiagnostic("BT_HELLO_TX", "initial address=**\${address.takeLast(5)} bytes=\${hello.size}")
        }
        return session
    }

    private fun readLoop(session: Session) {
        try {
            val input = session.socket.inputStream
            while (running && !session.closed && session.socket.isConnected) {
                val length = readInt(input)
                if (length !in 1..MAX_FRAME) {
                    throw IllegalArgumentException("bad_frame_length=\${length}")
                }
                val payload = ByteArray(length)
                readFully(input, payload)
                if (sessions[session.address] !== session) break
                onDiagnostic("BT_FRAME_RX", "address=**\${session.address.takeLast(5)} bytes=\${length}")
                onFrame(session.address, payload)
            }
        } catch (error: Exception) {
            if (running && !session.closed) {
                onDiagnostic(
                    "BT_TRANSPORT",
                    "read_end address=**\${session.address.takeLast(5)} error=\${error.javaClass.simpleName}"
                )
            }
        } finally {
            closeSession(session, "reader_finished")
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
        val session = sessions[address] ?: return false
        if (session.closed || !session.socket.isConnected) return false
        ready.add(address)
        onDiagnostic("BT_TRANSPORT", "peer_ready=true address=**\${address.takeLast(5)}")
        return true
    }

    fun send(address: String, bytes: ByteArray): Boolean {
        val session = sessions[address] ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_FRAME) {
            onDiagnostic("BT_TRANSPORT", "write_rejected address=**\${address.takeLast(5)} bytes=\${bytes.size}")
            return false
        }
        return sendSession(session, bytes)
    }

    private fun sendSession(session: Session, bytes: ByteArray): Boolean {
        if (session.closed || !session.socket.isConnected || bytes.isEmpty() || bytes.size > MAX_FRAME) return false

        return synchronized(session.writerLock) {
            if (session.closed || sessions[session.address] !== session) return@synchronized false
            try {
                val output: OutputStream = session.socket.outputStream
                output.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                output.write(bytes)
                output.flush()
                onDiagnostic("BT_FRAME_TX", "address=**\${session.address.takeLast(5)} bytes=\${bytes.size}")
                true
            } catch (error: Exception) {
                onDiagnostic(
                    "BT_TRANSPORT",
                    "write_failed address=**\${session.address.takeLast(5)} error=\${error.javaClass.simpleName}"
                )
                closeSession(session, "write_failed")
                false
            }
        }
    }

    fun readyAddresses(): Set<String> =
        ready.filter { isConnected(it) }.toSet()

    fun isConnected(address: String): Boolean {
        val session = sessions[address] ?: return false
        return !session.closed && runCatching { session.socket.isConnected }.getOrDefault(false)
    }

    fun close(address: String, reason: String = "requested") {
        val session = sessions[address] ?: return
        closeSession(session, reason)
    }

    private fun closeSession(session: Session, reason: String) {
        if (!sessions.remove(session.address, session)) return
        session.closed = true
        ready.remove(session.address)
        closeQuietly(session.socket)
        onDisconnected(session.address)
        onDiagnostic("BT_TRANSPORT", "socket_closed reason=\${reason} address=**\${session.address.takeLast(5)} " + snapshot())
    }

    private fun snapshot(): String =
        "running=\${running} connecting=\${connecting.size} queued=\${connectTasks.size} sockets=\${sessions.size} ready=\${ready.size}"

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    fun stop() {
        if (!running) return
        running = false

        closeQuietly(serverSocket)
        serverSocket = null
        acceptTask?.cancel(true)
        acceptTask = null

        pendingSockets.values.forEach { closeQuietly(it) }
        pendingSockets.clear()
        connectTasks.values.forEach { it.cancel(true) }
        connectTasks.clear()
        connecting.clear()

        sessions.values.forEach {
            it.closed = true
            closeQuietly(it.socket)
        }
        sessions.clear()
        ready.clear()

        connectIo.shutdownNow()
        io.shutdownNow()
        onDiagnostic("BT_TRANSPORT", "stopped")
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        runCatching { socket?.close() }
    }

    private fun closeQuietly(socket: BluetoothServerSocket?) {
        runCatching { socket?.close() }
    }
}
