package com.example.meshmessenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.core.content.ContextCompat
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors

class RfcommDiagnosticActivity : Activity() {
    companion object {
        private val UUID_SERVICE = UUID.fromString("7d2a1004-8b4f-4f10-9d3e-8b7d6a2f0001")
        private const val SERVICE_NAME = "MeshMessenger"
    }

    private lateinit var adapter: BluetoothAdapter
    private lateinit var logView: TextView
    private lateinit var list: LinearLayout
    private val io = Executors.newCachedThreadPool()
    private var server: BluetoothServerSocket? = null
    @Volatile private var running = false
    private val devices = linkedMapOf<String, BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    devices[d.address] = d
                    rebuildList()
                    log("DISCOVERY " + (runCatching { d.name }.getOrNull() ?: "unknown") + " " + d.address)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> log("DISCOVERY finished")
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        buildUi()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })
        startServer()
        discover()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        root.addView(TextView(this).apply {
            text = "RFCOMM DIRECT TEST"; textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Изолированный classic Bluetooth RFCOMM. Без BLE/GATT/L2CAP/RELAY/Mesh."
            textSize = 15f; setPadding(0,12,0,16)
        })
        root.addView(Button(this).apply { text = "Сканировать classic Bluetooth"; setOnClickListener { discover() } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    @SuppressLint("MissingPermission")
    private fun rebuildList() {
        list.removeAllViews()
        devices.values.forEach { d ->
            val name = runCatching { d.name }.getOrNull() ?: "unknown"
            list.addView(Button(this).apply {
                text = "CONNECT " + name + "\n" + d.address
                setOnClickListener { connect(d) }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover() {
        if (!hasBtPermission()) { log("ERROR missing Bluetooth runtime permission"); return }
        runCatching {
            adapter.cancelDiscovery(); devices.clear(); rebuildList()
            log("DISCOVERY started=" + adapter.startDiscovery())
        }.onFailure { log("DISCOVERY error=" + it.javaClass.simpleName + ":" + it.message) }
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
        (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
         ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    private fun startServer() {
        if (!hasBtPermission()) return
        running = true
        io.submit {
            try {
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, UUID_SERVICE)
                log("SERVER opened uuid=" + UUID_SERVICE)
                while (running) {
                    val socket = server?.accept() ?: break
                    attach(socket, "INCOMING")
                }
            } catch (t: Throwable) {
                if (running) log("SERVER error=" + t.javaClass.simpleName + ":" + t.message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        io.submit {
            var socket: BluetoothSocket? = null
            try {
                adapter.cancelDiscovery()
                log("CLIENT connect_start " + device.address + " bonded=" + (device.bondState == BluetoothDevice.BOND_BONDED))
                socket = device.createInsecureRfcommSocketToServiceRecord(UUID_SERVICE)
                socket.connect()
                attach(socket, "OUTGOING")
            } catch (t: Throwable) {
                runCatching { socket?.close() }
                log("CLIENT connect_failed " + t.javaClass.simpleName + ":" + t.message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attach(socket: BluetoothSocket, direction: String) {
        val address = runCatching { socket.remoteDevice.address }.getOrDefault("?")
        log("SOCKET_CONNECTED direction=" + direction + " address=" + address)
        send(socket, "RFCOMM_TEST_HELLO")
        io.submit { readLoop(socket, address) }
    }

    private fun readLoop(socket: BluetoothSocket, address: String) {
        try {
            val input = socket.inputStream
            while (running && socket.isConnected) {
                val len = readInt(input)
                if (len <= 0 || len > 4096) throw IllegalArgumentException("bad_length=" + len)
                val payload = ByteArray(len); readFully(input, payload)
                val text = payload.toString(Charsets.UTF_8)
                log("RX " + address + " " + text)
                if (text == "RFCOMM_TEST_HELLO") send(socket, "RFCOMM_TEST_ACK")
            }
        } catch (t: Throwable) {
            if (running) log("READ_END " + address + " " + t.javaClass.simpleName + ":" + t.message)
        } finally { runCatching { socket.close() } }
    }

    @SuppressLint("MissingPermission")
    private fun send(socket: BluetoothSocket, text: String) {
        runCatching {
            val b = text.toByteArray(Charsets.UTF_8)
            val out: OutputStream = socket.outputStream
            out.write(ByteBuffer.allocate(4).putInt(b.size).array()); out.write(b); out.flush()
            log("TX " + runCatching { socket.remoteDevice.address }.getOrDefault("?") + " " + text)
        }.onFailure { log("TX_ERROR " + it.javaClass.simpleName + ":" + it.message) }
    }

    private fun readInt(input: InputStream): Int {
        val b = ByteArray(4); readFully(input,b); return ByteBuffer.wrap(b).int
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var off=0
        while (off < b.size) { val n=input.read(b,off,b.size-off); if(n<0) throw EOFException(); off+=n }
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n") }
        android.util.Log.i("RfcommDiagnostic", s)
    }

    override fun onDestroy() {
        running=false; runCatching { unregisterReceiver(receiver) }; runCatching { server?.close() }
        io.shutdownNow(); super.onDestroy()
    }
}
