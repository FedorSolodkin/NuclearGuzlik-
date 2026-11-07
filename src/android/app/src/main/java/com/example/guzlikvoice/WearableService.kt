package com.example.guzlikvoice

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The service, not a Fragment, owns the socket, file and model use lease. No network API here. */
// Synchronous journal commits run on the worker and must survive process death.
@android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
open class WearableService : Service() {
    data class Snapshot(val message: String = "Устройство отключено", val connected: Boolean = false,
        val recording: Boolean = false, val processing: Boolean = false, val samples: Long = 0,
        val missing: Long = 0, val battery: Int? = null, val active: Boolean = false)
    companion object {
        const val CONNECT = "wearable.connect"
        const val START = "wearable.start"
        const val STOP = "wearable.stop"
        const val DISCONNECT = "wearable.disconnect"
        const val CANCEL_PROCESSING = "wearable.cancelProcessing"
        private const val CHANNEL = "wearable_recording"
        private const val NOTIFICATION = 1011
        @Volatile var snapshot = Snapshot(); private set
        @Volatile var recordingEntryId = 0L; private set
        fun action(context: Context, action: String, address: String? = null) {
            val intent = Intent(context, WearableService::class.java).setAction(action).putExtra("address", address)
            if (action == CONNECT) context.startForegroundService(intent) else context.startService(intent)
        }
    }
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "GuzlikVoice-wearable") }
    private val asrWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "GuzlikVoice-wearable-ASR") }
    private val incoming = ArrayBlockingQueue<Pair<Long, GuzlikVoiceWire.Packet>>(256)
    @Volatile private var link: WearableLink? = null
    private var epoch = 0L
    private var sequence = 0
    private var address = ""
    private var closing = false
    @Volatile private var lastRx = 0L
    private var retryAt = 0L
    private var connectionDeadline = 0L
    private var writer: WearableWavWriter? = null
    private var entryId = 0L
    private var entryText = ""
    private var lease: UserDataJobs.Lease? = null
    private var engine: WearableRecognizer? = null
    @Volatile private var processing = false
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var destroyed = false
    private var nextPublish = 0L
    private var lastEntryCheck = 0L
    private var lastCheck = 0L
    private var message = "Подключение…"
    private val session = WearableSession(
        send = { type, bytes ->
            val seq = sequence++ and 65535
            link?.send(seq, type, bytes) ?: error("Нет соединения")
            seq
        },
        writePcm = { writer?.write(it) ?: error("Аудиофайл закрыт") },
        finished = { reason -> finishRecording(reason) },
        clock = { SystemClock.elapsedRealtime() })

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Внешний микрофон", NotificationManager.IMPORTANCE_LOW))
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuzlikVoice:wearable")
        worker.scheduleWithFixedDelay({ guarded { poll() } }, 0, 50, TimeUnit.MILLISECONDS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CONNECT) {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
        }
        if (intent?.action == CONNECT) snapshot = Snapshot("Подключение…", active = true)
        worker.execute { guarded {
            when (intent?.action) {
                CONNECT -> {
                    if (link != null || lease != null) return@guarded
                    WearableRecovery.recover(this, force = true)
                    address = intent.getStringExtra("address").orEmpty()
                    require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) { "Выберите устройство" }
                    engine = acquireRecognizer()
                    lease = UserDataJobs.begin() ?: error("Дождитесь удаления данных")
                    lease?.onCancel { cancelled = true; link?.close(); post { shutdown() } }
                    connectionDeadline = SystemClock.elapsedRealtime() + 30_000
                    openLink()
                }
                START -> startRecording()
                STOP -> session.stop()
                CANCEL_PROCESSING -> {
                    message = "Обработка остановлена системой. Аудио сохранено в истории"
                    cancelled = true; shutdown()
                }
                DISCONNECT -> { closing = true; if (session.sessionId != null) session.stop() else shutdown() }
            }
            publish()
        } }
        return START_NOT_STICKY
    }
    protected open fun acquireRecognizer(): WearableRecognizer {
        val helper = WhisperHelper.active ?: error("Откройте экран записи и дождитесь загрузки распознавания")
        check(helper.retain()) { "Распознавание ещё не готово" }
        return LocalWearableRecognizer(this, helper)
    }
    protected open fun createLink(address: String, onConnected: () -> Unit,
        onDisconnected: (String) -> Unit, onPacket: (GuzlikVoiceWire.Packet) -> Unit): WearableLink =
        GuzlikVoiceBtReceiver(this, address, onConnected, onDisconnected, onPacket)

    private fun openLink() {
        link?.close(); incoming.clear()
        val current = ++epoch
        val now = SystemClock.elapsedRealtime()
        lastRx = now; retryAt = 0
        message = "Подключение…"
        link = createLink(address,
            onConnected = { post { if (current == epoch) message = "Проверка формата аудио…" } },
            onDisconnected = { reason -> post { if (current == epoch) lost(reason) } },
            onPacket = { packet ->
                if (!destroyed) { lastRx = SystemClock.elapsedRealtime(); incoming.offer(current to packet) }
            }).also { it.connect() }
    }
    private fun lost(reason: String) {
        link?.close(); link = null; epoch++; incoming.clear(); session.disconnected()
        message = "Связь потеряна: $reason. Подключаю повторно…"
        retryAt = SystemClock.elapsedRealtime() + 1000
    }
    private fun poll() {
        if (cancelled || destroyed) return
        repeat(64) {
            val pair = incoming.poll() ?: return@repeat
            if (pair.first == epoch) {
                if (pair.second.type == GuzlikVoiceWire.BUTTON && pair.second.payload.contentEquals(byteArrayOf(1))) {
                    if (!processing && !closing) {
                        if (session.sessionId != null) session.stop() else if (session.ready) startRecording()
                    }
                } else session.accept(pair.second)
            }
        }
        session.tick()
        val now = SystemClock.elapsedRealtime()
        if (!closing && lease != null) {
            if (link != null && now - lastRx > 7000) lost("Нет ответа устройства")
            if (retryAt != 0L && now >= retryAt) openLink()
            if (connectionDeadline != 0L && now > connectionDeadline && !session.ready && session.sessionId == null && !processing) {
                message = "Не удалось подключить устройство. Проверьте питание, сопряжение и прошивку"; shutdown(); return
            }
            if (session.ready) connectionDeadline = now + 30_000
        }
        if (writer != null && now - lastEntryCheck >= 1000) {
            lastEntryCheck = now
            val entry = RecordStorage.findEntryByTimestamp(this, entryId)?.second
            if (entry == null || entry.isEncrypted) {
                cancelled = true
                shutdown()
                return
            }
        }
        if (now - lastCheck >= 10_000) {
            lastCheck = now
            wakeLock?.let {
                if (it.isHeld) it.release()
                if (writer != null || processing) it.acquire(60 * 60 * 1000L)
            }
            if (writer != null && filesDir.freeSpace < 10L * 1024 * 1024) {
                message = "Мало памяти: завершаю запись"; session.stop()
            }
        }
        if (now >= nextPublish) { publish(); nextPublish = now + if (writer != null) 250 else 1000 }
    }
    private fun startRecording() {
        check(!processing && !closing && writer == null && session.ready) { "Устройство ещё не готово к записи" }
        check(RecordingService.recordingActive.get() == 0) { "Сначала остановите микрофон телефона" }
        lease?.check() ?: error("Сначала подключите устройство")
        check(filesDir.freeSpace >= 50L * 1024 * 1024) { "Для записи нужно хотя бы 50 МБ свободной памяти" }
        val file = File(filesDir, "wearable_${System.currentTimeMillis()}.wav")
        writer = WearableWavWriter(file)
        entryText = "Запись с внешнего микрофона. Обработка ещё не завершена."
        entryId = RecordStorage.addToToday(this, entryText, "voice", null, file.absolutePath)
        recordingEntryId = entryId
        wakeLock?.let { if (!it.isHeld) it.acquire(60 * 60 * 1000L) }
        check(getSharedPreferences("wearable", MODE_PRIVATE).edit().putString("pending_path", file.absolutePath)
            .putLong("pending_entry", entryId).commit()) { "Не удалось сохранить журнал записи" }
        val id = java.security.SecureRandom().nextInt().toLong() and 0xFFFF_FFFFL
        session.start(if (id == 0L) 1 else id)
        message = "Запускаю запись…"
        // START is only requested here; notification becomes recording after its ACK.
    }
    private fun finishRecording(reason: String?) {
        val fileWriter = writer ?: return
        if (reason != null) { closing = true; link?.close(); link = null }
        if (destroyed) return
        writer = null
        fileWriter.close()
        recordingEntryId = 0L
        val file = fileWriter.file
        val timestamp = entryId
        val expected = entryText
        val missing = session.missingSamples
        val total = session.samples
        val captureMetrics = org.json.JSONObject()
            .put("sampleRate", 16000).put("samples", total).put("receivedSamples", session.receivedSamples)
            .put("missingSamples", missing).put("endReason", reason ?: "acknowledged")
        RecordStorage.saveWearableMetrics(this, timestamp, captureMetrics.toString())
        getSharedPreferences("wearable", MODE_PRIVATE).edit().remove("pending_path").remove("pending_entry").commit()
        message = reason ?: "Аудио сохранено. Распознаю…"
        signal("Запись остановлена; аудио сохранено")
        if (cancelled || lease?.cancelled == true) { shutdown(); return }
        if (total == 0L) {
            RecordStorage.saveWearableTranscript(this, timestamp, expected,
                reason ?: "Устройство не передало аудио. Проверьте микрофон.", emptyList())
            if (closing) shutdown()
            return
        }
        val jobLease = UserDataJobs.begin() ?: run { shutdown(); return }
        val helper = engine
        if (helper == null || !helper.retain()) { jobLease.close(); error("Распознавание недоступно") }
        val token = try { RecordingService.processingStarted(this) }
        catch (e: Exception) { helper.release(); jobLease.close(); throw e }
        processing = true
        jobLease.onCancel { cancelled = true }
        // This use lease is acquired before enqueue, so destruction cannot free a queued job's model.
        asrWorker.execute {
            val started = SystemClock.elapsedRealtime()
            try {
                jobLease.check()
                val segments = helper.transcribe(file) { cancelled || jobLease.cancelled }
                jobLease.check()
                val warning = buildString {
                    if (reason != null) append("⚠ $reason. Конец записи может быть неполным.\n")
                    if (missing > 0) append("⚠ Потеряно ${missing * 1000 / 16000} мс аудио; пропуски заменены тишиной.\n")
                }
                val text = warning + TextFormatter.format(segments.joinToString(" ") { it.text })
                val saved = RecordStorage.saveWearableTranscript(this, timestamp, expected, text, segments)
                val json = org.json.JSONObject(captureMetrics.toString()).put("processingMs", SystemClock.elapsedRealtime() - started)
                    .put("rtf", (SystemClock.elapsedRealtime() - started).toDouble() / (total / 16.0))
                    .put("model", helper.modelName)
                RecordStorage.saveWearableMetrics(this, timestamp, json.toString())
                post {
                    message = if (saved) "Заметка готова в истории" else "Аудио сохранено; изменения заметки сохранены"
                    ProcessingNotifier.notifyReady(this, timestamp, "Запись с устройства обработана")
                }
            } catch (e: Exception) {
                if (!cancelled) {
                    RecordStorage.saveWearableTranscript(this, timestamp, expected,
                        "Аудио сохранено. Распознавание не завершено: ${e.message}. Повторите обработку в записи.", emptyList())
                    post { message = "Аудио в истории. Распознавание не завершено: ${e.message}" }
                }
            } finally {
                helper.release()
                jobLease.close()
                RecordingService.processingFinished(this, token)
                post { processing = false; if (closing || cancelled) shutdown() else publish() }
            }
        }
    }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            message = e.message ?: "Ошибка внешнего микрофона"
            try { session.abort(message) } catch (_: Exception) { }
            closing = true
            if (!processing) shutdown() else { link?.close(); link = null }
        }
    }
    private fun shutdown() {
        closing = true; link?.close(); link = null; epoch++
        if (session.sessionId != null) session.abort("Соединение закрыто")
        if (processing) return
        try { writer?.close() } finally {
            writer = null; recordingEntryId = 0L
            engine?.release(); engine = null; lease?.close(); lease = null
            snapshot = Snapshot(message)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun post(action: () -> Unit) {
        if (destroyed) return
        try { worker.execute { if (!destroyed) guarded(action) } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* Service already destroyed. */ }
    }
    private fun publish() {
        if (destroyed || closing && lease == null) return
        if (writer == null && !processing) wakeLock?.let { if (it.isHeld) it.release() }
        val text = when {
            processing -> "Аудио сохранено. Распознаю…"
            session.state == WearableSession.State.RECORDING -> "Запись с устройства идёт"
            session.state == WearableSession.State.STOPPING -> "Завершаю запись на устройстве…"
            session.state == WearableSession.State.STARTING -> "Ожидаю подтверждение записи…"
            session.ready && message.startsWith("Проверка") -> "Устройство готово"
            else -> message
        }
        snapshot = Snapshot(text, session.ready, session.sessionId != null, processing,
            session.samples, session.missingSamples, session.battery, active = lease != null)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, WearableActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, WearableService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle("GuzlikVoice — внешний микрофон")
            .setContentText(snapshot.message).setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(R.drawable.ic_mic, "Остановить запись", stop).build()
    }
    private fun signal(text: String) {
        getSystemService(NotificationManager::class.java).notify(1012,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle("GuzlikVoice")
                .setContentText(text).setAutoCancel(true).build())
    }
    override fun onDestroy() {
        destroyed = true; cancelled = true; link?.close()
        worker.execute {
            try { writer?.close() } catch (_: Exception) { /* Pending WAV is recovered on next startup. */ } finally {
                writer = null; recordingEntryId = 0L
                // ASR owns its own retain while running; its completion releases the processing lease.
                engine?.release(); engine = null; lease?.close(); lease = null
                snapshot = Snapshot("Устройство отключено. Сохранённое аудио — в истории")
                asrWorker.shutdown()
            }
        }
        worker.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }
}
