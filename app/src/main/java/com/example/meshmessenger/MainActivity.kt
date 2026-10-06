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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.example.meshmessenger.mesh.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.common.BitMatrix
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var sendEnabled = true
    private var adapter: BluetoothAdapter? = null
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var identity: IdentityStore
    private lateinit var router: MeshRouter
    private lateinit var contacts: ContactStore
    private lateinit var chats: ChatStore
    private lateinit var pendingOutbox: PendingMessageStore
    private lateinit var updater: UpdateManager
    private lateinit var peerStatus: TextView
    private lateinit var updateStatus: TextView
    private val updateHandler = Handler(Looper.getMainLooper())
    private val hourlyUpdateCheck = object : Runnable {
        override fun run() {
            checkForUpdates(false)
            updateHandler.postDelayed(this, 60L * 60L * 1000L)
        }
    }

    private var selected: ContactStore.Contact? = null
    private var pendingQrField: EditText? = null
    private var meshActive = false
    private var transportMode = "BOTH"
    private var meshActionPending = false
    private var pendingMeshTargetActive = false
    private lateinit var meshButton: Button
    private val meshActionHandler = Handler(Looper.getMainLooper())
    private val meshActionTimeout = Runnable {
        val targetActive = pendingMeshTargetActive
        meshActionPending = false
        meshButton.isEnabled = true

        // A transport may initialize asynchronously, but the UI must never stay
        // in a permanent "starting…" state when no definitive status arrived.
        if (targetActive && !meshActive) {
            meshButton.text = "▶ Запустить mesh"
            status.text = "Mesh не запустился"
            log.text = "Запуск Mesh не завершился за 35 секунд. Попробуй запустить ещё раз."
        } else {
            meshButton.text = if (meshActive) "■ Остановить mesh" else "▶ Запустить mesh"
        }

        startService(Intent(this, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_MESH_STATUS_REQUEST))
    }

    private fun beginMeshAction(targetActive: Boolean) {
        if (meshActionPending) return
        meshActionPending = true
        pendingMeshTargetActive = targetActive
        meshButton.isEnabled = false
        meshButton.text = if (targetActive) "MESH ЗАПУСКАЕТСЯ…" else "MESH ОСТАНАВЛИВАЕТСЯ…"
        meshActionHandler.removeCallbacks(meshActionTimeout)
        meshActionHandler.postDelayed(meshActionTimeout, 35_000L)
    }
    private var connectionStatusView: TextView? = null
    // Refresh the visible chat when a message arrives while its dialog is already open.
    private var refreshOpenChat: (() -> Unit)? = null
    private val contactStatusHandler = Handler(Looper.getMainLooper())

    private val contactUpdatedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_CONTACT_UPDATED) return
            val nodeId = intent.getStringExtra(MeshForegroundService.EXTRA_SOURCE_ID) ?: return
            val name = intent.getStringExtra(MeshForegroundService.EXTRA_CONTACT_NAME) ?: return
            if (selected?.nodeId == nodeId) {
                selected = contacts.get(nodeId) ?: selected
                refreshOpenChat?.invoke()
            }
            log.text = "Контакт обновлён: $name"
        }
    }
    private val contactStatusRunnable = object : Runnable {
        override fun run() {
            updateSelectedContactStatus()
            contactStatusHandler.postDelayed(this, 2000L)
        }
    }

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
            log.text = "Для mesh нужны Bluetooth и, на Android 11 и ниже, доступ к геопозиции для BT-сканирования."
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
        pendingOutbox = PendingMessageStore(this)
        transportMode = getSharedPreferences("mesh_runtime", MODE_PRIVATE).getString("transport_mode", "BOTH") ?: "BOTH"
        updater = UpdateManager(this)
        // log must exist before buildHome(): setting the network switch can invoke its listener.
        log = TextView(this)
        buildHome()
        requestNotificationPermission()
        requestBluetoothPermissionsIfNeeded()
        if (hasBluetoothRuntimePermissions()) startRuntimeNotification()
        // Старт не ждёт сеть: первая автопроверка через час, затем раз в час.
        updateHandler.postDelayed(hourlyUpdateCheck, 60L * 60L * 1000L)
    }
    catch (t: Throwable) {
            android.util.Log.e("MeshMessenger", "Startup failure", t)
            (application as? MeshMessengerApp)?.recordCriticalFailure("startup failure", t)
            android.app.AlertDialog.Builder(this)
                .setTitle("Ошибка запуска")
                .setMessage("${t.javaClass.simpleName}: ${t.message}")
                .setPositiveButton("Закрыть", null)
                .show()
        }
    }

    override fun onDestroy() {
        updateHandler.removeCallbacks(hourlyUpdateCheck)
        meshActionHandler.removeCallbacks(meshActionTimeout)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class TronVideoBackgroundView(context: Context) : android.widget.VideoView(context) {
        init {
            setBackgroundColor(Color.rgb(0, 3, 8))
            setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.tron_loop}"))
            setOnPreparedListener { player ->
                player.isLooping = true
                player.setVolume(0f, 0f)
                post {
                    val videoRatio = player.videoWidth / player.videoHeight.toFloat().coerceAtLeast(1f)
                    val viewRatio = width / height.toFloat().coerceAtLeast(1f)
                    if (videoRatio > viewRatio) scaleX = videoRatio / viewRatio
                    else if (videoRatio < viewRatio) scaleY = viewRatio / videoRatio
                    start()
                }
            }
            setOnErrorListener { _, _, _ ->
                setBackgroundColor(Color.rgb(0, 3, 8))
                true
            }
        }
        override fun onDetachedFromWindow() {
            runCatching { stopPlayback() }
            super.onDetachedFromWindow()
        }
    }
    private fun homeText(textValue: String, size: Float = 16f): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(Color.rgb(190, 235, 245))
            setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.rgb(0, 150, 180))
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun actionButton(label: String, icon: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextColor(Color.rgb(0, 240, 255))
            isAllCaps = false
            gravity = android.view.Gravity.CENTER
            minHeight = dp(56)
            minimumHeight = dp(56)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_home_button)
            setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = dp(14)
            // Keep the label itself centered on the screen; the icon is visually pinned to the left.
            val iconWidth = compoundDrawables[0]?.intrinsicWidth?.coerceAtLeast(0) ?: 0
            setPadding(dp(14), dp(8), dp(14) + iconWidth + dp(14), dp(8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { setMargins(0, dp(7), 0, dp(7)) }
        }

    private fun buildHome() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(22))
        }
        scroll.addView(root)
        val rootBackground = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(2, 8, 19))
            addView(TronVideoBackgroundView(this@MainActivity), FrameLayout.LayoutParams(-1, -1))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }

        status = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextColor(Color.rgb(0, 240, 255))
            setShadowLayer(dp(10).toFloat(), 0f, 0f, Color.rgb(0, 180, 255))
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        root.addView(status)
        root.addView(homeText("Мой Node ID: ${identity.nodeId}", 14f).apply {
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setShadowLayer(dp(6).toFloat(), 0f, 0f, Color.rgb(0, 240, 255))
        })

        root.addView(Button(this).apply {
            text = "ИМЯ: ${identity.displayName}"
            textSize = 16f
            setTextColor(Color.rgb(211, 229, 255))
            isAllCaps = false
            gravity = android.view.Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_name)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_person, 0, 0, 0)
            compoundDrawablePadding = dp(12)
            val iconWidth = compoundDrawables[0]?.intrinsicWidth?.coerceAtLeast(0) ?: 0
            setPadding(dp(16), dp(5), dp(16) + iconWidth + dp(12), dp(5))
            minHeight = dp(54)
            setOnClickListener { editOwnName() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
            ).apply { setMargins(0, dp(6), 0, dp(8)) }
        })

        root.addView(homeText("Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 15f))

        peerStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(105, 210, 255))
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_channel)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_channel_off, 0, 0, 0)
            compoundDrawablePadding = dp(12)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
            ).apply { setMargins(0, dp(5), 0, dp(8)) }
        }
        root.addView(peerStatus)

        root.addView(homeText("РЕЖИМ ПЕРЕДАЧИ", 13f).apply {
            setPadding(dp(2), dp(4), dp(2), dp(2))
        })
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF081824.toInt())
                setStroke(dp(1), 0xFF087E99.toInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                setMargins(0, dp(2), 0, dp(8))
            }
        }
        val modeButtons = linkedMapOf<String, Button>()
        fun renderModeButtons() {
            modeButtons.forEach { (mode, button) ->
                val active = transportMode == mode
                button.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(if (active) 0xFF087E99.toInt() else 0x00000000)
                }
                button.setTextColor(if (active) Color.WHITE else Color.rgb(105, 210, 255))
            }
        }
        listOf("BT" to "BLE", "BOTH" to "BLE + RELAY", "RELAY" to "RELAY").forEach { (mode, label) ->
            val button = Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                minHeight = dp(42)
                minWidth = 0
                setPadding(dp(3), 0, dp(3), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    if (transportMode == mode) return@setOnClickListener
                    transportMode = mode
                    getSharedPreferences("mesh_runtime", MODE_PRIVATE).edit().putString("transport_mode", mode).apply()
                    renderModeButtons()
                    if (mode != "RELAY" && !hasBluetoothRuntimePermissions()) {
                        requestMeshPermissions()
                    } else {
                        startService(Intent(this@MainActivity, MeshForegroundService::class.java).apply {
                            action = MeshForegroundService.ACTION_SET_MODE
                            putExtra(MeshForegroundService.EXTRA_TRANSPORT_MODE, mode)
                        })
                    }
                    log.text = "Режим передачи: $label"
                }
            }
            modeButtons[mode] = button
            modeRow.addView(button)
        }
        renderModeButtons()
        root.addView(modeRow)

        meshButton = actionButton(
            if (meshActive) "ОСТАНОВИТЬ MESH" else "ЗАПУСТИТЬ MESH",
            R.drawable.ic_stop
        ) {
            if (meshActionPending) return@actionButton
            if (meshActive) {
                beginMeshAction(false)
                startService(Intent(this@MainActivity, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_STOP))
            } else {
                requestMeshPermissions()
            }
        }
        root.addView(meshButton)

        root.addView(actionButton("СОСТОЯНИЕ ФОНОВОЙ РАБОТЫ", R.drawable.ic_battery) { showBatteryStatus() })
        root.addView(actionButton("ДОБАВИТЬ КОНТАКТ", R.drawable.ic_add) { addContactDialog() })
        root.addView(actionButton("МОЙ QR-КОД", R.drawable.ic_qr) { showOwnQr() })
        root.addView(actionButton("КОНТАКТЫ", R.drawable.ic_contacts) { contactsDialog() })
        root.addView(actionButton("ПРОВЕРИТЬ ОБНОВЛЕНИЕ", R.drawable.ic_update) { checkForUpdates(true) })
        root.addView(actionButton("ОТПРАВИТЬ BUGREPORT", R.drawable.ic_bug) { showBugReportScreen() })

        updateStatus = homeText("", 14f).apply {
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
        root.addView(updateStatus)

        root.addView(TextView(this).apply {
            text = "СДЕЛАЛ ДМИТРИЙ ШАЛИМОВ"
            textSize = 15f
            setTextColor(Color.rgb(211, 229, 255))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        })

        setContentView(rootBackground)
        status.text = if (meshActive) "MESH АКТИВЕН" else "MESH ВЫКЛЮЧЕН"
    }


    private fun showBugReportScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(24))
            setBackgroundColor(Color.rgb(2, 8, 19))
        }
        root.addView(TextView(this).apply {
            text = "ОТПРАВКА БАГРЕПОРТА"
            textSize = 22f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextColor(Color.rgb(0, 240, 255))
            setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.rgb(0, 150, 255))
            setPadding(0, 0, 0, dp(18))
        })
        root.addView(TextView(this).apply {
            text = "Приложение соберёт версию, сведения об устройстве, состояние Bluetooth и журнал диагностики. Личные сообщения и ключи в отчёт добавлять не нужно."
            textSize = 15f
            setTextColor(Color.rgb(211, 229, 255))
            setPadding(0, 0, 0, dp(18))
        })
        val result = TextView(this).apply {
            text = ""
            textSize = 15f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextColor(Color.rgb(211, 229, 255))
            setPadding(0, dp(12), 0, dp(12))
            contentDescription = "Результат отправки багрепорта"
        }
        lateinit var send: Button
        send = actionButton("СФОРМИРОВАТЬ И ОТПРАВИТЬ БАГРЕПОРТ", R.drawable.ic_bug) {
            if (!sendEnabled) return@actionButton
            sendEnabled = false
            send.isEnabled = false
            result.setTextColor(Color.rgb(211, 229, 255))
            result.text = "Формируем отчёт…"
            Thread {
                val outcome = runCatching {
                    val diagnostics = MeshDiagnostics(this)
                    val monitor = MeshBluetoothMonitor(this, adapter, diagnostics)
                    MeshBugReport.create(this, diagnostics, monitor.snapshot())
                }
                runOnUiThread {
                    outcome.onSuccess { report ->
                        result.text = "Отправляем отчёт на защищённый сервер…"
                        Thread {
                            val upload = runCatching {
                                when (MeshBugReportUploader.upload(report)) {
                                    "duplicate" -> "Такой баг уже зарегистрирован"
                                    else -> "Багрепорт отправлен"
                                }
                            }
                            runOnUiThread {
                                sendEnabled = true
                                send.isEnabled = true
                                upload.onSuccess { message ->
                                    result.setTextColor(Color.rgb(0, 240, 180))
                                    result.text = message
                                    report.delete()
                                }.onFailure { error ->
                                    result.setTextColor(Color.rgb(255, 190, 90))
                                    result.text = "Не удалось отправить. Архив сохранён в приложении; попробуй позже."
                                    android.util.Log.w("MeshMessenger", "Bugreport upload failed", error)
                                }
                            }
                        }.start()
                    }.onFailure { error ->
                        sendEnabled = true
                        send.isEnabled = true
                        result.setTextColor(Color.rgb(255, 100, 100))
                        result.text = "Не удалось сформировать багрепорт: ${error.message ?: error.javaClass.simpleName}"
                        android.util.Log.e("MeshMessenger", "Bugreport creation failed", error)
                    }
                }
            }.start()
        }
        root.addView(send)
        root.addView(result)
        root.addView(actionButton("НАЗАД", R.drawable.ic_channel_off) { buildHome() })
        setContentView(root)
    }

    private fun itIsNotUsed() { }

    private fun checkForUpdates(manual: Boolean) {
        if (isFinishing || isDestroyed) return
        if (manual) updateStatus.text = "Проверяем обновления…"
        updater.check { result ->
            // A slow network response can arrive after Honor's system has paused or
            // destroyed this Activity. Never update dead views or show a dialog then.
            if (isFinishing || isDestroyed) return@check
            runCatching {
                result.onSuccess { release ->
                    when {
                        release == null -> if (manual) {
                            updateStatus.text = "На сервере обновлений нет APK для установки"
                        }
                        !updater.isNewer(release) -> if (manual) {
                            updateStatus.text = "Установлена актуальная версия ${BuildConfig.VERSION_NAME}"
                        }
                        else -> {
                            updateStatus.text = "Доступно обновление"
                            showUpdateDialog(release)
                        }
                    }
                }.onFailure { error ->
                    if (manual) {
                        updateStatus.text = "Не удалось проверить обновление: ${error.message ?: error.javaClass.simpleName}"
                        android.util.Log.w("MeshMessenger", "Update check failed", error)
                    }
                }
            }.onFailure { error ->
                android.util.Log.e("MeshMessenger", "Update result UI failed", error)
                if (manual && !isFinishing && !isDestroyed) {
                    updateStatus.text = "Ошибка обработки ответа об обновлении"
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
        val missing = when {
            android.os.Build.VERSION.SDK_INT >= 31 -> listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            android.os.Build.VERSION.SDK_INT >= 23 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            else -> emptyList()
        }
        if (missing.isEmpty()) {
            startMeshIfAllowed()
        } else {
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun startMeshIfAllowed() {
        val bluetoothAdapter = adapter ?: run { status.text = "Bluetooth недоступен"; log.text = "На этом устройстве не найден Bluetooth-адаптер."; return }
        if (!bluetoothAdapter.isEnabled) { status.text = "Включи Bluetooth"; return }
        if (android.os.Build.VERSION.SDK_INT in 28..30 &&
            !(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled
        ) {
            status.text = "Включи геолокацию для BT"
            log.text = "На Android 9–11 система не выполняет BT-сканирование при выключенной геолокации."
            return
        }
        if (android.os.Build.VERSION.SDK_INT in 23..27 &&
            android.provider.Settings.Secure.getInt(
                contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF
            ) == android.provider.Settings.Secure.LOCATION_MODE_OFF
        ) {
            status.text = "Включи геолокацию для BT"
            log.text = "На Android 6–8 система не выполняет BT-сканирование при выключенной геолокации."
            return
        }
        beginMeshAction(true)
        startService(Intent(this, MeshForegroundService::class.java).setAction(MeshForegroundService.ACTION_START))
        status.text = "Mesh запускается..."
    }
    private fun handleIncoming(text: String, sourceId: String, packetId: String = "") {
        chats.addIncomingIfAbsent(sourceId, text, packetId)
        // The service persists incoming messages before broadcasting them. Refresh the
        // current conversation immediately; otherwise the saved message may remain
        // invisible until the user closes and reopens the chat.
        if (selected?.nodeId == sourceId) refreshOpenChat?.invoke()
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
                if (parts.size >= 3 && parts[0].isNotBlank() && publicKey.isNotBlank() && runCatching { CryptoManager.publicKeyFromBase64(publicKey) }.isSuccess) {
                    contacts.upsert(ContactStore.Contact(parts[0], name, publicKey))
                    log.text = "Контакт добавлен: $name • поиск через BT и Relay"
                } else log.text = "Неверная QR-карточка. Формат: NodeID|имя|publicKey"
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
                startService(Intent(this, MeshForegroundService::class.java).apply {
                    action = MeshForegroundService.ACTION_PROFILE_NAME_CHANGED
                    putExtra(MeshForegroundService.EXTRA_CONTACT_NAME, identity.displayName)
                })
                buildHome()

            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun editContactDialog(contact: ContactStore.Contact) {
        val input = EditText(this).apply {
            setText(contact.name)
            selectAll()
            hint = "Имя контакта"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Редактировать контакт")
            .setMessage("Node ID и ключ контакта нельзя изменить.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim().ifBlank { contact.nodeId.take(8) }
                contacts.rename(contact.nodeId, newName)
                selected = contacts.get(contact.nodeId) ?: contact.copy(name = newName)
                refreshOpenChat?.invoke()
                log.text = "Контакт переименован: $newName"
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteContact(contact: ContactStore.Contact, closeChat: (() -> Unit)? = null) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Удалить контакт?")
            .setMessage("Контакт «${contact.name}» будет удалён. История переписки останется на телефоне.")
            .setPositiveButton("Удалить") { _, _ ->
                contacts.delete(contact.nodeId)
                if (selected?.nodeId == contact.nodeId) selected = null
                closeChat?.invoke()
                log.text = "Контакт удалён: ${contact.name}"
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showContactActions(contact: ContactStore.Contact, closeChat: (() -> Unit)? = null) {
        val items = arrayOf("Переименовать", "Редактировать контакт", "Удалить контакт")
        android.app.AlertDialog.Builder(this)
            .setTitle(contact.name)
            .setItems(items) { _, which ->
                when (which) {
                    0, 1 -> editContactDialog(contact)
                    2 -> deleteContact(contact, closeChat)
                }
            }
            .show()
    }

    private fun contactsDialog() {
        val dialog = android.app.Dialog(this)
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
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(18, 15, 18, 15)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18f
                    setColor(0xFF07131F.toInt())
                    setStroke(1, 0xFF17647A.toInt())
                }
                elevation = dp(3).toFloat()
                setOnClickListener { dialog.dismiss(); openChat(contact) }

                val avatar = TextView(this@MainActivity).apply {
                    text = contact.name.trim().firstOrNull()?.uppercase() ?: "?"
                    textSize = 22f
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(0xFF00E5FF.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14f
                        setColor(0xFF0A2531.toInt())
                        setStroke(1, 0xFF00AFC9.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                        rightMargin = dp(15)
                    }
                }

                val info = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                info.addView(TextView(this@MainActivity).apply {
                    text = contact.name
                    textSize = 18f
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    setTextColor(0xFFE7F7FF.toInt())
                })
                info.addView(TextView(this@MainActivity).apply {
                    text = "NODE  ${contact.nodeId.take(16)}"
                    textSize = 10f
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    setTextColor(0xFF6B8799.toInt())
                    setPadding(0, dp(4), 0, 0)
                })
                info.addView(TextView(this@MainActivity).apply {
                    val lastSeen = contact.lastSeenAt
                    val online = lastSeen > 0L && System.currentTimeMillis() - lastSeen <= 30_000L
                    text = if (online) "● В СЕТИ" else "● НЕ В СЕТИ"
                    textSize = 11f
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    setTextColor(if (online) 0xFF00FF9D.toInt() else 0xFF607C8D.toInt())
                    setPadding(0, dp(5), 0, 0)
                })
                addView(avatar)
                addView(info)
            }.also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, dp(10))
            })
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
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.TRANSPARENT)
        }

        lateinit var chatDialog: android.app.Dialog

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_home_card)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = contact.name
            textSize = 21f
            setTextColor(Color.rgb(0, 240, 255))
            setShadowLayer(dp(5).toFloat(), 0f, 0f, Color.rgb(0, 150, 180))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val nodeInfo = TextView(this).apply {
            text = "NODE ${contact.nodeId.take(16)}"
            textSize = 12f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFF8AA7B8.toInt())
            setPadding(0, dp(5), 0, 0)
        }

        connectionStatusView = TextView(this).apply {
            textSize = 11f
            setPadding(0, 5, 0, 0)
        }
        updateSelectedContactStatus()
        contactStatusHandler.removeCallbacks(contactStatusRunnable)
        contactStatusHandler.post(contactStatusRunnable)

        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Button(this).apply {
            text = "⋮"
            textSize = 24f
            setTextColor(0xFF00E5FF.toInt())
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener {
                showContactActions(contact) { chatDialog.dismiss() }
            }
        })
        header.addView(titleRow)
        header.addView(nodeInfo)
        header.addView(connectionStatusView)

        val history = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
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
            setPadding(dp(6), dp(10), dp(6), dp(6))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_home_card)
        }

        val input = EditText(this).apply {
            hint = "СООБЩЕНИЕ..."
            setHintTextColor(0xFF527080.toInt())
            setTextColor(0xFFE7F7FF.toInt())
            textSize = 15f
            setSingleLine(false)
            maxLines = 4
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF07131F.toInt())
                setStroke(dp(1), 0xFF164B61.toInt())
            }
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
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF0D202B.toInt())
                setStroke(dp(1), 0xFF087E99.toInt())
            }
            minWidth = dp(58)
            minHeight = dp(58)
        }

        inputRow.addView(input)
        inputRow.addView(send)

        fun refresh() {
            history.removeAllViews()

            chats.messages(contact.nodeId).forEach { message ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (message.mine) android.view.Gravity.END else android.view.Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                val bubble = TextView(this).apply {
                    text = if (message.mine) android.text.SpannableStringBuilder().apply { append(message.text); append("  "); val start = length; append(when (message.delivery) { ChatStore.Delivery.DELIVERED, ChatStore.Delivery.SENT -> "➤"; ChatStore.Delivery.WAITING -> "◷"; ChatStore.Delivery.NOT_SENT -> "×" }); setSpan(android.text.style.ForegroundColorSpan(when (message.delivery) { ChatStore.Delivery.DELIVERED, ChatStore.Delivery.SENT -> 0xFFFF9800.toInt(); ChatStore.Delivery.WAITING -> 0xFF6B8799.toInt(); ChatStore.Delivery.NOT_SENT -> 0xFFFF5555.toInt() }), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } else message.text
                    textSize = 16f
                    setTextColor(0xFFE7F7FF.toInt())
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    maxWidth = (resources.displayMetrics.widthPixels * 0.80f).toInt()
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(if (message.mine) 0xFF082B38.toInt() else 0xFF122333.toInt())
                        setStroke(dp(1), if (message.mine) 0xFF00D9FF.toInt() else 0xFF234B66.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                row.addView(bubble)
                history.addView(row)
            }

            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            runCatching {
                val public = CryptoManager.publicKeyFromBase64(contact.publicKeyBase64)
                val packet = router.createEncryptedMessage(contact.nodeId, public, text, identity.displayName)

                pendingOutbox.enqueue(packet)

                startService(Intent(this, MeshForegroundService::class.java).apply {
                    action = MeshForegroundService.ACTION_SEND_MESH
                    putExtra(MeshForegroundService.EXTRA_PACKET, packet.encode())
                })
                chats.add(contact.nodeId, text, true, ChatStore.Delivery.WAITING, packet.messageId.toString())
                log.text = "◷ Ожидает подтверждения получателя"

                input.text.clear()
                refresh()
            }.onFailure { error ->
                android.util.Log.e("MeshMessenger", "Message send failed", error)
                chats.add(contact.nodeId, text, true, ChatStore.Delivery.NOT_SENT)
                log.text = "⚠ Не удалось отправить сообщение. Проверь контакт и подключение."
                refresh()
            }
        }

        refreshOpenChat = {
            if (selected?.nodeId == contact.nodeId) {
                val current = contacts.get(contact.nodeId)
                if (current != null) {
                    title.text = current.name
                    refresh()
                }
            }
        }

        root.addView(header)
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(inputRow)

        val chatFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(2, 8, 19))
            addView(TronVideoBackgroundView(this@MainActivity), FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }

        chatDialog = android.app.Dialog(this)
        chatDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        chatDialog.setContentView(chatFrame)
        chatDialog.setCanceledOnTouchOutside(false)
        chatDialog.setOnDismissListener {
            if (selected?.nodeId == contact.nodeId) refreshOpenChat = null
        }
        chatDialog.setOnShowListener {
            chatDialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(0xFF05080D.toInt())
            )
            chatDialog.window?.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        chatDialog.show()
        chatDialog.window?.setLayout(
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
            val packetId = intent.getStringExtra(MeshForegroundService.EXTRA_PACKET_ID).orEmpty()
            handleIncoming(text, sourceId, packetId)
        }
    }

    private val relayMessageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_RELAY_MESSAGE) return
            val text = intent.getStringExtra(MeshForegroundService.EXTRA_RELAY_TEXT) ?: return
            val sourceId = intent.getStringExtra(MeshForegroundService.EXTRA_SOURCE_ID) ?: return
            val packetId = intent.getStringExtra(MeshForegroundService.EXTRA_PACKET_ID).orEmpty()
            handleIncoming(text, sourceId, packetId)
        }
    }

    private val meshStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_MESH_STATUS) return
            val active = intent.getBooleanExtra(MeshForegroundService.EXTRA_MESH_ACTIVE, false)
            meshActive = active
            if (meshActionPending && active == pendingMeshTargetActive) {
                meshActionPending = false
                meshActionHandler.removeCallbacks(meshActionTimeout)
                meshButton.isEnabled = true
            }
            status.text = if (active) "Mesh активен" else "Mesh выключен"
            if (!meshActionPending) {
                meshButton.text = if (active) "■ Остановить mesh" else "▶ Запустить mesh"
                meshButton.isEnabled = true
            }
            updateSelectedContactStatus()
        }
    }

    private fun updateSelectedContactStatus() {
        val view = connectionStatusView ?: return
        val contact = selected ?: run {
            view.text = "● СЕТЬ НЕ ПОДКЛЮЧЕНА"
            view.setTextColor(0xFF6B8799.toInt())
            return
        }
        val lastSeen = contacts.get(contact.nodeId)?.lastSeenAt ?: 0L
        // Presence is transport-independent and is refreshed by BT and Relay.
        val online = lastSeen > 0L && System.currentTimeMillis() - lastSeen <= 30_000L
        view.text = if (online) "● В СЕТИ" else "● НЕ В СЕТИ"
        view.setTextColor(if (online) 0xFF00FF9D.toInt() else 0xFF6B8799.toInt())
    }

    private val peerStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_PEER_STATUS) return
            val bt = intent.getIntExtra(MeshForegroundService.EXTRA_BT_COUNT, 0)
            val relayPeers = intent.getIntExtra(MeshForegroundService.EXTRA_RELAY_COUNT, 0)
            val bluetoothIcon = if (bt > 0) R.drawable.ic_bluetooth_mesh else 0
            val relayIcon = if (relayPeers > 0) R.drawable.ic_relay_radio else 0
            // These counters represent connected peers, not whether a transport is available.
            peerStatus.text = when {
                bt > 0 && relayPeers > 0 -> "УЗЛЫ: BT $bt • RELAY $relayPeers"
                bt > 0 -> "УЗЛЫ BT: $bt • RELAY: 0"
                relayPeers > 0 -> "УЗЛЫ BT: 0 • RELAY: $relayPeers"
                else -> "НЕТ СОЕДИНЁННЫХ УЗЛОВ"
            }
            peerStatus.setCompoundDrawablesWithIntrinsicBounds(bluetoothIcon, 0, relayIcon, 0)
        }
    }

    private val deliveryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeshForegroundService.ACTION_MESH_DELIVERED) return
            val packetId = intent.getStringExtra(MeshForegroundService.EXTRA_DELIVERED_PACKET_ID) ?: return
            android.util.Log.i("MeshBluetoothDiag", "ui_delivery_status packetId=$packetId")
            chats.updateDelivery(packetId, ChatStore.Delivery.DELIVERED)
            refreshOpenChat?.invoke()
            log.text = "➤ Доставлено: получатель подтвердил сообщение"
        }
    }


    override fun onResume() {
        super.onResume()
        if (::updater.isInitialized) updater.resumePendingInstall()
    }

    override fun onStart() {
        super.onStart()
        contactStatusHandler.removeCallbacks(contactStatusRunnable)
        contactStatusHandler.post(contactStatusRunnable)
        try {
            androidx.core.content.ContextCompat.registerReceiver(this, deliveryReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_MESH_DELIVERED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, meshMessageReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_MESH_MESSAGE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, relayMessageReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_RELAY_MESSAGE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, meshStatusReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_MESH_STATUS), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, peerStatusReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_PEER_STATUS), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, contactUpdatedReceiver, android.content.IntentFilter(MeshForegroundService.ACTION_CONTACT_UPDATED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
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
        contactStatusHandler.removeCallbacks(contactStatusRunnable)
        runCatching { unregisterReceiver(deliveryReceiver) }
        runCatching { unregisterReceiver(meshMessageReceiver) }
        runCatching { unregisterReceiver(relayMessageReceiver) }
        runCatching { unregisterReceiver(meshStatusReceiver) }
        runCatching { unregisterReceiver(peerStatusReceiver) }
        runCatching { unregisterReceiver(contactUpdatedReceiver) }
        super.onStop()
    }


    private fun showOwnQr() {
        val card = "${identity.nodeId}|${identity.displayName}|${identity.publicKeyBase64}"
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