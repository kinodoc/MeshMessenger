package com.example.meshmessenger.mesh

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import android.net.Network
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MeshIpTransport(
    private val localId: String,
    private val router: MeshRouter,
    private val queue: PendingMessageStore,
    private val guard: NetBirdGuard,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String, String, MeshPacket, String) -> Unit,
    private val onDeliveryAck: (String) -> Unit = {}
) {

    companion object {
        const val PORT = 42424
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val MAX_FRAME_SIZE = 8 * 1024 * 1024
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        executor.execute {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(PORT))
                    server = socket

                    onStatus("IP: TCP/$PORT готов")

                    while (running.get()) {
                        val client = try {
                            socket.accept()
                        } catch (_: Exception) {
                            if (running.get()) {
                                onStatus("IP: ошибка входящего соединения")
                            }
                            break
                        }

                        executor.execute {
                            handleIncoming(client)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    onStatus("IP: listener: ${e.message ?: "ошибка"}")
                }
            } finally {
                server = null
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    fun send(packet: MeshPacket, host: String): Boolean {
        if (!running.get()) {
            onStatus("IP: транспорт не запущен")
            return false
        }

        // The UI may detect the NetBird interface by its 100.x address even when
        // Android reports the VPN transport state differently. Use the actual
        // local NetBird address as the authoritative signal for the IP path.
        if (guard.localNetBirdIp().isNullOrBlank()) {
            onStatus("IP: внутренняя сеть NetBird не подключена")
            return false
        }

        val address = runCatching {
            InetAddress.getByName(host)
        }.getOrNull()

        if (address !is Inet4Address || !guard.isNetBirdAddress(address)) {
            onStatus("IP: адрес не является NetBird IPv4")
            return false
        }

        val bytes = packet.encode()

        if (bytes.size > MAX_FRAME_SIZE) {
            onStatus("IP: пакет слишком большой")
            return false
        }

        return runCatching {
            val network = guard.netBirdNetwork()
                ?: throw IllegalStateException("NetBird network unavailable")
            Socket().use { socket ->
                network.bindSocket(socket)
                socket.connect(
                    InetSocketAddress(address, PORT),
                    CONNECT_TIMEOUT_MS
                )

                val output = DataOutputStream(
                    BufferedOutputStream(socket.getOutputStream())
                )

                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }

            onStatus("IP: отправлено через NetBird")
            true
        }.getOrElse {
            onStatus("IP: ошибка отправки: ${it.message ?: "соединение"}")
            false
        }
    }

    private fun handleIncoming(socket: Socket) {
        socket.use {
            val remote = it.inetAddress

            if (!guard.isNetBirdAddress(remote)) {
                onStatus("IP: отклонено соединение не из NetBird")
                return
            }

            val input = DataInputStream(
                BufferedInputStream(it.getInputStream())
            )

            val length = runCatching {
                input.readInt()
            }.getOrNull() ?: return

            if (length <= 0 || length > MAX_FRAME_SIZE) {
                onStatus("IP: недопустимый размер пакета")
                return
            }

            val bytes = ByteArray(length)
            input.readFully(bytes)

            val packet = MeshPacket.decode(bytes) ?: run {
                onStatus("IP: повреждённый пакет")
                return
            }

            val next = router.onReceive(packet)

            if (packet.destinationId == localId) {
                val text = router.decryptForLocal(packet)
                    ?: "[не удалось расшифровать]"

                if (text.startsWith(MeshRouter.DELIVERY_ACK_PREFIX)) {
                    onDeliveryAck(text.removePrefix(MeshRouter.DELIVERY_ACK_PREFIX))
                } else {
                    onMessage(text, packet.sourceId, packet, remote.hostAddress)
                    val ack = runCatching { router.createDeliveryAck(packet) }.getOrNull()
                    if (ack != null) send(ack, remote.hostAddress)
                }
                queue.remove(packet.messageId)

                onStatus("IP: получено сообщение")
            } else if (next != null) {
                queue.enqueue(next)
                onStatus("IP: получен транзитный пакет")
            }
        }
    }
}
