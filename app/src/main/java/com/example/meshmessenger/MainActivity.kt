package com.example.meshmessenger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.meshmessenger.mesh.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.common.BitMatrix
import java.nio.charset.StandardCharsets
import java.util.Base64

class MainActivity : ComponentActivity() {
    private lateinit var adapter: BluetoothAdapter
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var identity: IdentityStore
    private lateinit var router: MeshRouter
    private lateinit var queue: PendingMessageStore
    private lateinit var contacts: ContactStore
    private lateinit var chats: ChatStore
    private lateinit var netBird: NetBirdGuard
    private lateinit var netBirdStatus: TextView
    private var node: MeshGattNode? = null
    private var selected: ContactStore.Contact? = null
    private var pendingQrField: EditText? = null

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim()
        if (raw.isNullOrEmpty()) return@registerForActivityResult
        pendingQrField?.setText(raw)
        log.text = "QR-код считан. Проверь данные и нажми «Добавить»."
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startMeshIfAllowed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        identity = IdentityStore(this)
        router = MeshRouter(identity.nodeId, identity.keyPair.private).also { it.identityPublicBytes = identity.keyPair.public.encoded }
        queue = PendingMessageStore(this)
        contacts = ContactStore(this)
        chats = ChatStore(this)
        netBird = NetBirdGuard(this)
        buildHome()
        updateNetBirdStatus()
    }
    catch (t: Throwable) {
            android.util.Log.e("MeshMessenger", "Startup failure", t)
            android.app.AlertDialog.Builder(this)
                .setTitle("Ошибка запуска")
                .setMessage("${t.javaClass.simpleName}: ${t.message}")
                .setPositiveButton("Закрыть", null)
                .show()
        }
    }

    private fun buildHome() {
        status = TextView(this).apply { textSize = 17f; setPadding(24, 24, 24, 12) }
        log = TextView(this).apply { setPadding(24, 12, 24, 12) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 16) }
        root.addView(status)
        root.addView(TextView(this).apply { text = "Мой Node ID: ${identity.nodeId}" })

        netBirdStatus = TextView(this).apply { setPadding(0, 10, 0, 6); textSize = 16f }
        root.addView(netBirdStatus)
        root.addView(Switch(this).apply {
            text = "Только внутренняя сеть"
            isChecked = netBird.onlyNetBird
            setOnCheckedChangeListener { _, checked ->
                netBird.onlyNetBird = checked
                updateNetBirdStatus()
                log.text = if (checked) "Режим только внутренней сети включён: IP-трафик разрешён только через внутреннюю сеть." else "Режим только внутренней сети выключен."
            }
        })

        root.addView(Button(this).apply { text = "▶ Запустить mesh"; setOnClickListener { requestMeshPermissions() } })
        root.addView(Button(this).apply { text = "🔋 Состояние фоновой работы"; setOnClickListener { showBatteryStatus() } })
        root.addView(Button(this).apply { text = "＋ Добавить контакт"; setOnClickListener { addContactDialog() } })
        root.addView(Button(this).apply { text = "▣ Мой QR-код"; setOnClickListener { showOwnQr() } })
        root.addView(Button(this).apply { text = "Контакты"; setOnClickListener { contactsDialog() } })
        root.addView(log)
        setContentView(root)
        status.text = "Mesh Messenger готов • контактов: ${contacts.all().size}"
    }


    private fun updateNetBirdStatus() {
        val state = netBird.status()
        val label = when (state) {
            NetBirdGuard.Status.CONNECTED -> "● Внутренняя сеть: подключена"
            NetBirdGuard.Status.VPN_PRESENT -> "● VPN обнаружен, внутренняя сеть не подтверждена"
            NetBirdGuard.Status.OFFLINE -> "○ Внутренняя сеть: не подключена"
        }
        netBirdStatus.text = if (netBird.onlyNetBird) "$label • режим только внутренней сети" else label
    }

    private fun showBatteryStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = android.os.Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
        val state = if (unrestricted) "● Оптимизация батареи отключена для приложения" else "○ Android может ограничивать фоновую работу"
        val message = "$state\n\nДля постоянной работы mesh рекомендуется разрешить приложению работу без ограничений батареи. Это особенно важно для relay-узла."
        val builder = android.app.AlertDialog.Builder(this).setTitle("Фоновая работа").setMessage(message)
        if (!unrestricted && android.os.Build.VERSION.SDK_INT >= 23) {
            builder.setPositiveButton("Открыть настройки") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                runCatching { startActivity(intent) }.getOrElse {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        builder.setNegativeButton("Закрыть", null).show()
    }

    private fun requestMeshPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)) else startMeshIfAllowed()
    }

    private fun startMeshIfAllowed() {
        if (!adapter.isEnabled) { status.text = "Включи Bluetooth"; return }
        if (node != null) return
        startService(Intent(this, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_START))
        node = MeshGattNode(this, adapter, identity.nodeId, router, queue,
            { status.text = it },
            { packetText -> handleIncoming(packetText) }
        ).also { it.start() }
        val scanner = adapter.bluetoothLeScanner
        val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filter = android.bluetooth.le.ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(MeshProtocol.SERVICE_UUID)).build()
        scanner.startScan(listOf(filter), settings, object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) { node?.connect(result.device) }
            override fun onScanFailed(errorCode: Int) { status.text = "BLE scan error: $errorCode" }
        })
    }

    private fun handleIncoming(text: String) {
        // Transport MVP currently exposes decrypted text. Peer identification will be carried by the chat envelope in the next protocol revision.
        log.text = "Получено: $text"
    }

    private fun addContactDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 4, 24, 4)
        }

        val name = EditText(this).apply {
            hint = "Имя контакта"
        }

        val card = EditText(this).apply {
            hint = "Вставь данные QR-карточки"
            minLines = 4
        }

        pendingQrField = card

        val scan = Button(this).apply {
            text = "▣ СКАНИРОВАТЬ QR КАМЕРОЙ"
            setOnClickListener {
                qrScanner.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Наведите камеру на QR-код контакта")
                        setBeepEnabled(true)
                        setOrientationLocked(false)
                    }
                )
            }
        }

        box.addView(name)
        box.addView(card)
        box.addView(scan)

        AlertDialogBuilder(box, "Добавить контакт") { }
    }

    private fun AlertDialogBuilder(view: LinearLayout, title: String, ignored: () -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle(title).setView(view)
            .setPositiveButton("Добавить") { _, _ ->
                val fields = view.children().toList().filterIsInstance<EditText>()
                val name = fields[0].text.toString().ifBlank { "Контакт" }
                val raw = fields[1].text.toString().trim()
                val parts = raw.split("|", limit = 3)
                if (parts.size == 3 && runCatching { CryptoManager.publicKeyFromBase64(parts[2]) }.isSuccess) {
                    contacts.upsert(ContactStore.Contact(parts[0], name, parts[2]))
                    log.text = "Контакт добавлен: $name"
                } else log.text = "Неверная QR-карточка. Используй строку NodeID|имя|publicKey"
            }.setNegativeButton("Отмена", null).show()
    }

    private fun contactsDialog() {
        val list = contacts.all()
        if (list.isEmpty()) { android.app.AlertDialog.Builder(this).setTitle("Контакты").setMessage("Пока нет контактов. Добавь контакт через QR-карточку.").setPositiveButton("OK", null).show(); return }
        val labels = list.map { "${it.name} • ${it.nodeId.take(12)}" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Выбери контакт").setItems(labels) { _, which -> openChat(list[which]) }.setNegativeButton("Закрыть", null).show()
    }

    private fun openChat(contact: ContactStore.Contact) {
        selected = contact

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(0xFF05080D.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 14)
            setBackgroundColor(0xFF0A111A.toInt())
        }

        val title = TextView(this).apply {
            text = contact.name
            textSize = 21f
            setTextColor(0xFF00E5FF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val nodeInfo = TextView(this).apply {
            text = "NODE ${contact.nodeId.take(16)}"
            textSize = 11f
            setTextColor(0xFF6B8799.toInt())
            setPadding(0, 4, 0, 0)
        }

        val connection = TextView(this).apply {
            text = "● MESH CONNECTED"
            textSize = 11f
            setTextColor(0xFF00FF9D.toInt())
            setPadding(0, 5, 0, 0)
        }

        header.addView(title)
        header.addView(nodeInfo)
        header.addView(connection)

        val history = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFE7F7FF.toInt())
            setPadding(14, 18, 14, 18)
            gravity = android.view.Gravity.BOTTOM
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(history)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(6, 10, 6, 6)
            setBackgroundColor(0xFF0A111A.toInt())
        }

        val input = EditText(this).apply {
            hint = "СООБЩЕНИЕ..."
            hintTextColor = 0xFF527080.toInt()
            setTextColor(0xFFE7F7FF.toInt())
            textSize = 15f
            setSingleLine(false)
            maxLines = 4
            setPadding(16, 10, 16, 10)
            setBackgroundColor(0xFF101B26.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(0, 0, 10, 0)
            }
        }

        val send = Button(this).apply {
            text = "➤"
            textSize = 22f
            setTextColor(0xFF00E5FF.toInt())
            setBackgroundColor(0xFF0D202B.toInt())
            minWidth = 58
            minHeight = 58
        }

        inputRow.addView(input)
        inputRow.addView(send)

        fun refresh() {
            val messages = chats.messages(contact.nodeId)
            history.text = messages.joinToString("\n\n") { message ->
                if (message.mine) {
                    "                         YOU\n                         ${message.text}"
                } else {
                    "${contact.name.uppercase()}\n${message.text}"
                }
            }
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val public = CryptoManager.publicKeyFromBase64(contact.publicKeyBase64)
            val packet = router.createEncryptedMessage(contact.nodeId, public, text)
            node?.send(packet) ?: queue.enqueue(packet)
            chats.add(contact.nodeId, text, true)

            input.text.clear()
            refresh()
            log.text = "Сообщение сохранено и отправлено в mesh"
        }

        root.addView(header)
        root.addView(scroll)
        root.addView(inputRow)

        android.app.AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("ЗАКРЫТЬ", null)
            .show()

        refresh()
    }

    private fun showOwnQr() {
        val card = "${identity.nodeId}|Я|${identity.publicKeyBase64}"
        val image = makeQr(card, 720)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(ImageView(this).apply { setImageBitmap(image); adjustViewBounds = true })
        box.addView(TextView(this).apply { text = "Можно также скопировать строку и передать её другому устройству:\n$card" })
        android.app.AlertDialog.Builder(this).setTitle("Моя контактная карточка").setView(box)
            .setPositiveButton("Поделиться") { _, _ -> share(card) }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Передать контакт"))
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun onDestroy() { node?.stop(); super.onDestroy() }
}

private fun LinearLayout.children(): Sequence<android.view.View> = (0 until childCount).asSequence().map { getChildAt(it) }
