package com.example.guzlikvoice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** System Settings owns discovery and secure pairing; GuzlikVoice selects a bonded MAC, never a name. */
class WearableActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var devices: Spinner
    private lateinit var connect: MaterialButton
    private lateinit var record: MaterialButton
    private lateinit var disconnect: MaterialButton
    private var addresses = emptyList<String>()
    private var selectionMessage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshDevices()
    }
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 300) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding((24*density).toInt(), (24*density).toInt(), (24*density).toInt(), (24*density).toInt())
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, 12, 0, 12); body.addView(this)
        }
        fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
            this.text = text; setOnClickListener { action() }; body.addView(this)
        }
        button("Назад") { finish() }
        label("Внешний микрофон GuzlikVoice", 23f)
        label("Звук передаётся по Bluetooth и обрабатывается на телефоне. Интернет после установки моделей не нужен. Устройство: ESP32, прошивка GuzlikVoice Transport 2.")
        status = label("Устройство отключено")
        devices = Spinner(this).also { body.addView(it) }
        button("Сопряжение в настройках Bluetooth") {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { selectionMessage = "На этом устройстве нет настроек Bluetooth" }
        }
        button("Обновить список устройств") { requestBluetooth() }
        connect = button("Подключить") {
            val address = addresses.getOrNull(devices.selectedItemPosition) ?: return@button
            MaterialAlertDialogBuilder(this).setTitle("Подключить внешний микрофон?")
                .setMessage("После подключения запись можно запускать здесь или кнопкой на устройстве. Предупредите участников и получите согласие на запись. LED на устройстве должен гореть всё время записи.")
                .setNegativeButton("Отмена", null).setPositiveButton("Подключить") { _, _ ->
                    perform(WearableService.CONNECT, address)
                }.show()
        }
        record = button("Начать запись") {
            perform(if (WearableService.snapshot.recording) WearableService.STOP else WearableService.START)
        }
        disconnect = button("Отключить устройство") { perform(WearableService.DISCONNECT) }
        label("Готовая запись появится в истории: текст, ключевые мысли, редактирование, поиск, экспорт и разделение по голосам. При потере аудио покажем предупреждение. WAV занимает около 1,92 МБ в минуту.", 14f)
        val scroll = ScrollView(this).apply { addView(body) }
        setContentView(scroll)
        requestBluetooth()
    }
    private fun perform(action: String, address: String? = null) {
        try { WearableService.action(this, action, address) }
        catch (e: Exception) { selectionMessage = e.message ?: "Не удалось запустить подключение" }
    }
    private fun requestBluetooth() {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) required += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) required += Manifest.permission.POST_NOTIFICATIONS
        if (required.isNotEmpty()) permissions.launch(required.toTypedArray()) else refreshDevices()
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            addresses = emptyList()
            selectionMessage = "Разрешите доступ к устройствам поблизости, затем обновите список"; return
        }
        selectionMessage = null
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            val bonded = adapter?.bondedDevices?.sortedBy { it.name ?: it.address }.orEmpty()
            addresses = bonded.map { it.address }
            devices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                bonded.map { "${it.name ?: "Устройство"} • ${it.address}" })
            if (adapter?.isEnabled != true) { addresses = emptyList(); selectionMessage = "Включите Bluetooth в настройках" }
            else if (bonded.isEmpty()) selectionMessage = "Сначала выполните сопряжение с GuzlikVoice в настройках Bluetooth"
        } catch (e: SecurityException) { addresses = emptyList(); selectionMessage = "Нет разрешения Bluetooth: обновите список" }
    }
    private fun render() {
        val state = WearableService.snapshot
        val message = if (!state.active) selectionMessage ?: state.message else state.message
        status.text = message + if (state.recording || state.samples > 0)
            "\n${state.samples / 16000} с • пропуски ${state.missing * 1000 / 16000} мс" else ""
        state.battery?.let { status.append("\nБатарея устройства: $it%") }
        connect.isEnabled = addresses.isNotEmpty() && !state.active && WhisperHelper.active?.isReady == true
        record.isEnabled = (state.connected || state.recording) && !state.processing
        record.text = if (state.recording) "Остановить запись" else "Начать запись"
        disconnect.isEnabled = state.active
    }
    override fun onResume() { super.onResume(); refreshDevices(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
