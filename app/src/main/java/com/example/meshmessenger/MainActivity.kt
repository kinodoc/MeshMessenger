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
import android.os.Handler
import android.os.Looper
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
    private var adapter: BluetoothAdapter? = null
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var identity: IdentityStore
    private lateinit var router: MeshRouter
    private lateinit var contacts: ContactStore
    private lateinit var chats: ChatStore
    private lateinit var pendingIp: PendingIpMessageStore
    private lateinit var netBird: NetBirdGuard
    private lateinit var updater: UpdateManager
    private lateinit var netBirdStatus: TextView
    private lateinit var peerStatus: TextView
    private lateinit var updateStatus: TextView
    private val netBirdHandler = Handler(Looper.getMainLooper())
    private val updateHandler = Handler(Looper.getMainLooper())
    private val hourlyUpdateCheck = object : Runnable {
        override fun run() {
            checkForUpdates(false)
            updateHandler.postDelayed(this, 60L * 60L * 1000L)
        }
    }
    private val netBirdCheckRunnable = object : Runnable {
        override fun run() {
            updateNetBirdStatus()
            netBirdHandler.postDelayed(this, 5000L)
        }
    }
    private var selected: ContactStore.Contact? = null
    private var pendingQrField: EditText? = null
    private var meshActive = false
    private lateinit var meshButton: Button
    private var connectionStatusView: TextView? = null

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim()
        if (raw.isNullOrEmpty()) return@registerForActivityResult
        pendingQrField?.setText(raw)
        log.text = "QR-код считан. Проверь данные и нажми «Добавить»."
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            startRuntimeNotification()
            startMeshIfAllowed()
        } else {
            status.text = "Не все разрешения предоставлены"
            log.text = "Для mesh нужны Bluetooth и, на Android 11 и ниже, доступ к геопозиции для BLE-сканирования."
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchQrScanner()
        else log.text = "Камера не разрешена: сканирование QR-кода недоступно."
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) log.text = "Уведомления не разрешены: сообщения и работа mesh в фоне могут отображаться без уведомлений."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        identity = IdentityStore(this)
        router = MeshRouter(identity.nodeId, identity.keyPair.private).also { it.identityPublicBytes = identity.keyPair.public.encoded }
        contacts = ContactStore(this)
        chats = ChatStore(this)
        pendingIp = PendingIpMessageStore(this)
        netBird = NetBirdGuard(this)
        updater = UpdateManager(this)
        buildHome()
        requestNotificationPermission()
        requestBluetoothPermissionsIfNeeded()
        if (hasBluetoothRuntimePermissions()) startRuntimeNotification()
        // Старт не ждёт сеть: первая автопроверка через час, затем раз в час.
        updateNetBirdStatus()
        updateHandler.postDelayed(hourlyUpdateCheck, 60L * 60L * 1000L)
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

    override fun onDestroy() {
        updateHandler.removeCallbacks(hourlyUpdateCheck)
        super.onDestroy()
    }

    private fun startRuntimeNotification() {
        runCatching {
            val intent = Intent(this, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_APP_START)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                androidx.core.content.ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        }.onFailure {
            android.util.Log.e("MeshMessenger", "Unable to start runtime notification", it)
        }
    }

    private fun buildHome() {
        status = TextView(this).apply { textSize = 17f; setPadding(24, 24, 24, 12) }
        log = TextView(this).apply { setPadding(24, 12, 24, 12) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 16) }
        root.addView(status)
        root.addView(TextView(this).apply { text = "Мой Node ID: ${identity.nodeId}" })
        root.addView(Button(this).apply { text = "Имя: ${identity.displayName}"; setOnClickListener { editOwnName() } })
        root.addView(TextView(this).apply { text = "Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"; setPadding(0, 4, 0, 4) })

        netBirdStatus = TextView(this).apply { setPadding(0, 10, 0, 6); textSize = 16f }
        root.addView(netBirdStatus)
        peerStatus = TextView(this).apply { setPadding(0, 2, 0, 8); textSize = 15f; text = "BLE 0   •   NetBird 0" }
        root.addView(peerStatus)
        root.addView(Switch(this).apply {
            text = "Только внутренняя сеть"
            isChecked = netBird.onlyNetBird
            setOnCheckedChangeListener { _, checked ->
                netBird.onlyNetBird = checked
                updateNetBirdStatus()
                log.text = if (checked) "Режим только внутренней сети включён: IP-трафик разрешён только через внутреннюю сеть." else "Режим только внутренней сети выключен."
            }
        })

        meshButton = Button(this).apply { text = "▶ Запустить mesh"; setOnClickListener { if (meshActive) { startService(Intent(this@MainActivity, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_STOP)) } else { requestMeshPermissions() } } }; root.addView(meshButton)
        root.addView(Button(this).apply { text = "🔋 Состояние фоновой работы"; setOnClickListener { showBatteryStatus() } })
        root.addView(Button(this).apply { text = "＋ Добавить контакт"; setOnClickListener { addContactDialog() } })
        root.addView(Button(this).apply { text = "▣ Мой QR-код"; setOnClickListener { showOwnQr() } })
        root.addView(Button(this).apply { text = "Контакты"; setOnClickListener { contactsDialog() } })
        root.addView(Button(this).apply { text = "↻ Проверить обновление"; setOnClickListener { checkForUpdates(true) } })
        updateStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, 2, 0, 8)
            text = ""
        }
        root.addView(updateStatus)
        setContentView(root)
        status.text = "Mesh Messenger готов • контактов: ${contacts.all().size}"
    }


    private fun checkForUpdates(manual: Boolean) {
        if (manual) updateStatus.text = "Проверяем обновления…"
        updater.check { result ->
            result.onSuccess { release ->
                when {
                    release == null -> if (manual) {
                        updateStatus.text = "В GitHub Release нет APK для установки"
                    }
                    !updater.isNewer(release.version) -> if (manual) {
                        updateStatus.text = "Установлена актуальная версия ${BuildConfig.VERSION_NAME}"
                    }
                    else -> {
                        updateStatus.text = "Доступно обновление"
                        showUpdateDialog(release)
                    }
                }
            }.onFailure { error ->
                if (manual) {
                    val cause = error.cause?.let { "\nПричина: ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
                    updateStatus.text = "Не удалось проверить обновление: ${error.javaClass.simpleName}: ${error.message ?: "без сообщения"}$cause"
                }
            }
        }
    }

    private fun showUpdateDialog(release: UpdateManager.ReleaseInfo) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Доступно обновление ${release.version}")
            .setMessage("Текущая версия: ${BuildConfig.VERSION_NAME}\nНовая версия будет загружена напрямую из GitHub и запущена штатным установщиком Android. Настройки и контакты приложения при обычном обновлении сохраняются.")
            .setPositiveButton("Скачать и установить") { _, _ ->
                updateStatus.text = "Загрузка обновления ${release.version}…"
                updater.downloadAndInstall(release) { error ->
                    updateStatus.text = "Ошибка загрузки обновления: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            .setNegativeButton("Позже", null)
            .show()
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

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasBluetoothRuntimePermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            ).all {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val required = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            val missing = required.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        } else if (android.os.Build.VERSION.SDK_INT >= 23 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun requestMeshPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        } else {
            startMeshIfAllowed()
        }
    }

    private fun startMeshIfAllowed() {
        val bluetoothAdapter = adapter ?: run { status.text = "Bluetooth недоступен"; log.text = "На этом устройстве не найден Bluetooth-адаптер."; return }
        if (!bluetoothAdapter.isEnabled) { status.text = "Включи Bluetooth"; return }
        startService(Intent(this, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_START))
        status.text = "Mesh запускается..."
    }
    private fun handleIncoming(text: String, sourceId: String) {
        chats.add(sourceId, text, false)
        val contact = contacts.all().firstOrNull { it.nodeId == sourceId }
        log.text = if (contact != null) {
            "Получено от ${contact.name}: $text"
        } else {
            "Получено от ${sourceId.take(12)}: $text"
        }
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
                if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchQrScanner()
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
        }

        box.addView(name)
        box.addView(card)
        box.addView(scan)

        AlertDialogBuilder(box, "Добавить контакт") { }
    }

    private fun launchQrScanner() {
        qrScanner.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Наведите камеру на QR-код контакта")
                setBeepEnabled(true)
                setOrientationLocked(true)
            }
        )
    }

    private fun AlertDialogBuilder(view: LinearLayout, title: String, ignored: () -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle(title).setView(view)
            .setPositiveButton("Добавить") { _, _ ->
                val fields = view.children().toList().filterIsInstance<EditText>()
                val name = fields[0].text.toString().ifBlank { "Контакт" }
                val raw = fields[1].text.toString().trim()
                val parts = raw.split("|", limit = 4)
                val publicKey = parts.getOrNull(2).orEmpty()
                val netBirdIp = parts.getOrNull(3)?.trim().orEmpty()
                val validIp = netBirdIp.isNotBlank() && runCatching {
                    val address = java.net.InetAddress.getByName(netBirdIp)
                    address is java.net.Inet4Address && netBird.isNetBirdAddress(address)
                }.getOrDefault(false)
                if (parts.size == 4 && parts[0].isNotBlank() && publicKey.isNotBlank() && validIp && runCatching { CryptoManager.publicKeyFromBase64(publicKey) }.isSuccess) {
                    contacts.upsert(ContactStore.Contact(parts[0], name, publicKey, netBirdIp))
                    log.text = "Контакт добавлен: $name • внутренняя сеть: $netBirdIp"
                } else log.text = "Неверная QR-карточка. Используй строку NodeID|имя|publicKey|NetBirdIP"
            }.setNegativeButton("Отмена", null).show()
    }

    private fun editOwnName() {
        val input = EditText(this).apply {
            setText(identity.displayName)
            selectAll()
            hint = "Как тебя видят другие"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Имя в Mesh")
            .setMessage("Имя хранится только на этом телефоне и передаётся участникам вместе с Node ID.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                identity.displayName = input.text.toString()
                buildHome()

            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun contactsDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(0xFF05080D.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 14)
        }
        header.addView(TextView(this).apply {
            text = "КОНТАКТЫ"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF00E5FF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(Button(this).apply {
            text = "+"
            setOnClickListener { dialog.dismiss(); addContactDialog() }
        })
        root.addView(header)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contacts.all().forEach { contact ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(0xFF0A111A.toInt())
                    setStroke(1, 0xFF164B61.toInt())
                }
                setOnClickListener { dialog.dismiss(); openChat(contact) }
                addView(TextView(this@MainActivity).apply {
                    text = contact.name
                    textSize = 18f
                    setTextColor(0xFFE7F7FF.toInt())
                })
                addView(TextView(this@MainActivity).apply {
                    text = "NODE ID  ${contact.nodeId}"
                    textSize = 10f
                    setTextColor(0xFF6B8799.toInt())
                    setPadding(0, 4, 0, 0)
                })
            }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 10) })
        }
        if (contacts.all().isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Контакты появятся автоматически после первого сообщения.\n\nNode ID используется как уникальный идентификатор."
                textSize = 16f
                setTextColor(0xFF6B8799.toInt())
                setPadding(16, 32, 16, 32)
            })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "ЗАКРЫТЬ"; setOnClickListener { dialog.dismiss() } })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF05080D.toInt()))
        dialog.window?.setLayout(-1, -1)
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

        connectionStatusView = TextView(this).apply {
            text = "● MESH CONNECTED"
            textSize = 11f
            setTextColor(0xFF00FF9D.toInt())
            setPadding(0, 5, 0, 0)
        }

        header.addView(title)
        header.addView(nodeInfo)
        header.addView(connectionStatusView)

        val history = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 14, 12, 14)
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
            setHintTextColor(0xFF527080.toInt())
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
            history.removeAllViews()

            chats.messages(contact.nodeId).forEach { message ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (message.mine) android.view.Gravity.END else android.view.Gravity.START
                }
                val bubble = TextView(this).apply {
                    text = if (message.mine) android.text.SpannableStringBuilder().apply { append(message.text); append("  "); val start = length; append(when (message.delivery) { ChatStore.Delivery.DELIVERED, ChatStore.Delivery.SENT -> "➤"; ChatStore.Delivery.WAITING -> "◷"; ChatStore.Delivery.NOT_SENT -> "×" }); setSpan(android.text.style.ForegroundColorSpan(when (message.delivery) { ChatStore.Delivery.DELIVERED, ChatStore.Delivery.SENT -> 0xFFFF9800.toInt(); ChatStore.Delivery.WAITING -> 0xFF6B8799.toInt(); ChatStore.Delivery.NOT_SENT -> 0xFFFF5555.toInt() }), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } else message.text
                    textSize = 16f
                    setTextColor(0xFFE7F7FF.toInt())
                    setPadding(18, 12, 18, 12)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 18f
                        setColor(if (message.mine) 0xFF082B38.toInt() else 0xFF122333.toInt())
                        setStroke(1, if (message.mine) 0xFF00D9FF.toInt() else 0xFF234B66.toInt())
                    }
                }
                row.addView(bubble)
                history.addView(row)
            }

            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val public = CryptoManager.publicKeyFromBase64(contact.publicKeyBase64)
            val packet = router.createEncryptedMessage(contact.nodeId, public, text, identity.displayName)

            if (netBird.onlyNetBird) {
                val ip = contact.netBirdIp.trim()
                val connected = netBird.status() == NetBirdGuard.Status.CONNECTED || !netBird.localNetBirdIp().isNullOrBlank()

                if (ip.isBlank()) {
                    chats.add(contact.nodeId, text, true, ChatStore.Delivery.NOT_SENT, packet.messageId.toString())
                    log.text = "⚠ Не отправлено: у контакта нет внутреннего IP"
                } else {
                    pendingIp.enqueue(packet, ip)
                    chats.add(contact.nodeId, text, true, if (connected) ChatStore.Delivery.WAITING else ChatStore.Delivery.NOT_SENT, packet.messageId.toString())

                    if (connected) {
                        startService(Intent(this, MeshForegroundService::class.java).apply {
                            action = MeshForegroundService.ACTION_SEND_IP
                            putExtra(MeshForegroundService.EXTRA_IP, ip)
                            putExtra(MeshForegroundService.EXTRA_PACKET, packet.encode())
                        })
                        log.text = "◷ Ожидает отправки через внутреннюю сеть"
                    } else {
                        log.text = "⚠ Не отправлено: внутренняя сеть не подключена"
                    }
                }
            } else {
                startService(Intent(this, MeshForegroundService::class.java).apply { action = MeshForegroundService.ACTION_SEND_MESH; putExtra(MeshForegroundService.EXTRA_PACKET, packet.encode()) })
                chats.add(contact.nodeId, text, true, ChatStore.Delivery.WAITING, packet.messageId.toString())
                log.text = "◷ Ожидает подтверждения получателя"
            }

            input.text.clear()
            refresh()
        }

        root.addView(header)
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(inputRow)

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(0xFF05080D.toInt())
            )
            dialog.window?.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )

        refresh()
    }

    private val meshMessageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_MESH_MESSAGE) return
            val text = intent.getStringExtra(MeshForegroundService.EXTRA_MESH_TEXT) ?: return
            val sourceId = intent.getStringExtra(MeshForegroundService.EXTRA_SOURCE_ID) ?: return
            handleIncoming(text, sourceId)
        }
    }

    private val meshStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_MESH_STATUS) return
            val active = intent.getBooleanExtra(MeshForegroundService.EXTRA_MESH_ACTIVE, false)
            meshActive = active
            status.text = if (active) "Mesh активен" else "Mesh выключен"; meshButton.text = if (active) "■ Остановить mesh" else "▶ Запустить mesh"; connectionStatusView?.apply { text = if (active) "● MESH ACTIVE" else "● MESH OFFLINE"; setTextColor(if (active) 0xFF00FF9D.toInt() else 0xFF6B8799.toInt()) }
        }
    }

    private val peerStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_PEER_STATUS) return
            val ble = intent.getIntExtra(MeshForegroundService.EXTRA_BLE_COUNT, 0)
            val netBirdPeers = intent.getIntExtra(MeshForegroundService.EXTRA_NETBIRD_COUNT, 0)
            peerStatus.text = "BLE $ble   •   NetBird $netBirdPeers"
        }
    }

    private val deliveryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_MESH_DELIVERED) return
            val packetId = intent.getStringExtra(MeshForegroundService.EXTRA_DELIVERED_PACKET_ID) ?: return
            chats.updateDelivery(packetId, ChatStore.Delivery.DELIVERED)
            log.text = "➤ Доставлено: получатель подтвердил сообщение"
        }
    }

    private val ipStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_IP_STATUS) return

            val packetId = intent.getStringExtra(MeshForegroundService.EXTRA_PACKET_ID) ?: return
            val success = intent.getBooleanExtra(MeshForegroundService.EXTRA_IP_SUCCESS, false)

            if (!success) {
                chats.updateDelivery(packetId, ChatStore.Delivery.NOT_SENT)
                log.text = "⚠ Не отправлено: внутренняя сеть недоступна"
            } else {
                log.text = "◷ Доставляется через внутреннюю сеть"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::updater.isInitialized) updater.resumePendingInstall()
    }

    override fun onStart() {
        super.onStart()
        netBirdHandler.removeCallbacks(netBirdCheckRunnable)
        netBirdCheckRunnable.run()
        try {
            androidx.core.content.ContextCompat.registerReceiver(this, deliveryReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_MESH_DELIVERED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, ipStatusReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_IP_STATUS), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, meshMessageReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_MESH_MESSAGE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, meshStatusReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_MESH_STATUS), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, peerStatusReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_PEER_STATUS), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            startService(Intent(this, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_MESH_STATUS_REQUEST))
        } catch (t: Throwable) {
            android.util.Log.e("MeshMessenger", "onStart failure", t)
            android.app.AlertDialog.Builder(this)
                .setTitle("Ошибка onStart")
                .setMessage("${t.javaClass.simpleName}: ${t.message}")
                .setPositiveButton("Закрыть", null)
                .show()
        }
    }

    override fun onStop() {
        netBirdHandler.removeCallbacks(netBirdCheckRunnable)
        runCatching { unregisterReceiver(deliveryReceiver) }
        runCatching { unregisterReceiver(ipStatusReceiver) }
        runCatching { unregisterReceiver(meshMessageReceiver) }
        runCatching { unregisterReceiver(meshStatusReceiver) }
        runCatching { unregisterReceiver(peerStatusReceiver) }
        super.onStop()
    }


    private fun showOwnQr() {
        val ip = netBird.localNetBirdIp()
        if (ip.isNullOrBlank()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Внутренняя сеть")
                .setMessage("Нельзя создать контактную карточку: устройство не подключено к внутренней сети.")
                .setPositiveButton("ОК", null)
                .show()
            return
        }

        val card = "${identity.nodeId}|${identity.displayName}|${identity.publicKeyBase64}|$ip"
        val image = makeQr(card, 720)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(image)
            adjustViewBounds = true
        })
        box.addView(TextView(this).apply {
            text = "Нажми на ключ ниже, чтобы скопировать его и передать в другое приложение:" + System.lineSeparator() + card
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 8)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Mesh Messenger", card))
                Toast.makeText(this@MainActivity, "Ключ скопирован", Toast.LENGTH_SHORT).show()
            }
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Моя контактная карточка")
            .setView(box)
            .setPositiveButton("Поделиться") { _, _ -> share(card) }
            .setNegativeButton("Закрыть", null)
            .show()
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

}

private fun LinearLayout.children(): Sequence<android.view.View> = (0 until childCount).asSequence().map { getChildAt(it) }
