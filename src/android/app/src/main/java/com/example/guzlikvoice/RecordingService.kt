package com.example.guzlikvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps the process alive during background recording
 * AND during long post-processing (transcription, diarization, Q&A, template).
 *
 * Two independent modes reference-count activity:
 *  - recording (0/1): live AudioRecord capture in RecordFragment.
 *  - processing (0..N): счётчик активных post-обработок (несколько параллельных
 *    Q&A/шаблонов возможны).
 *
 * Service живёт пока activeProcessing > 0 || recording. Как только оба падают в 0
 * — гасится. Notification автоматически перерисовывается в зависимости от режима.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.example.guzlikvoice.ACTION_RECORDING_START"
        const val ACTION_STOP  = "com.example.guzlikvoice.ACTION_RECORDING_STOP"
        const val ACTION_PROCESSING_START = "com.example.guzlikvoice.ACTION_PROCESSING_START"
        const val ACTION_PROCESSING_STOP  = "com.example.guzlikvoice.ACTION_PROCESSING_STOP"

        private const val CHANNEL_ID   = "recording_channel"
        private const val NOTIF_ID     = 1001

        // Refresh the wake lock periodically so recordings > 1h don't get killed.
        private const val WAKE_LOCK_CHUNK_MS   = 60L * 60L * 1000L   // 1 hour
        private const val WAKE_LOCK_REFRESH_MS = 30L * 60L * 1000L   // renew every 30 min

        // Shared reference counters — читаются onStartCommand при перерисовке notification.
        // Публичный доступ через inc/dec helpers ниже.
        internal val recordingActive = AtomicInteger(0)
        internal val processingActive = AtomicInteger(0)

        fun start(ctx: Context) {
            val intent = Intent(ctx, RecordingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(intent)
            else
                ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP))
        }

        /** Отметить начало долгой обработки (транскрибация, диаризация, Q&A и т.д.).
         *  Гарантирует, что foreground service жив, пока идёт обработка. */
        private val processingLeases = ProcessingLeases()
        fun processingStarted(ctx: Context): Long {
            val token = processingLeases.begin()
            val intent = Intent(ctx, RecordingService::class.java).setAction(ACTION_PROCESSING_START).putExtra("processing_token",token)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch(e: Exception) { processingLeases.finish(token); throw e }
            return token
        }

        /** Отметить завершение обработки. Если больше нет активных задач и запись
         *  не идёт — service остановится. */
        fun processingFinished(ctx: Context, token: Long) {
            if (processingLeases.finish(token) == null) return
            try { ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_PROCESSING_STOP)) }
            catch(e: Exception) {
                // The system may have removed the service while the app was in the background.
                processingActive.set(processingLeases.count())
                android.util.Log.w("RecordingService", "Processing service already stopped: ${e.javaClass.simpleName}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val wl = wakeLock ?: return
            try {
                if (wl.isHeld) wl.release()
                wl.acquire(WAKE_LOCK_CHUNK_MS)
            } catch (_: Exception) {}
            refreshHandler.postDelayed(this, WAKE_LOCK_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        processingActive.set(processingLeases.count())
        when (intent?.action) {
            ACTION_START -> {
                recordingActive.set(1)
                promoteForeground()
                acquireWakeLock()
            }
            ACTION_STOP -> {
                recordingActive.set(0)
                if (processingActive.get() == 0) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    // Запись закончилась, но постобработка идёт — перерисовываем notif.
                    promoteForeground()
                }
            }
            ACTION_PROCESSING_START -> {
                // A cached/cancelled job can finish before Android delivers this
                // start command. Every startForegroundService request still needs
                // its foreground acknowledgement, even for an expired token.
                promoteForeground(processingStart = true)
                if (recordingActive.get()==0 && processingActive.get()==0) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                acquireWakeLock()
            }
            ACTION_PROCESSING_STOP -> {
                if (recordingActive.get() == 0 && processingActive.get() == 0) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    promoteForeground()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteForeground(processingStart: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 29) {
            val recording = recordingActive.get() > 0
            val processing = processingActive.get() > 0 || (processingStart && !recording)
            val types = ProcessingServiceTypes.forState(recording, processing, Build.VERSION.SDK_INT)
            startForeground(NOTIF_ID, buildNotification(), types)
        } else startForeground(NOTIF_ID, buildNotification())
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits long dataSync jobs. Preserve checkpoints before stopping.
        processingLeases.expire()
        DiarizationJobRunner.pauseForSystem()
        if (WearableService.snapshot.processing)
            WearableService.action(this, WearableService.CANCEL_PROCESSING)
        processingActive.set(0)
        if (recordingActive.get() > 0) promoteForeground()
        else { releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        recordingActive.set(0)
        processingActive.set(0)
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            recordingActive.get() > 0 -> "Запись идёт..."
            processingActive.get() > 0 -> "Обрабатываю запись..."
            else -> "GuzlikVoice активен"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GuzlikVoice")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись и обработка",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Показывается во время записи и постобработки" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuzlikVoice:recording").also {
            it.acquire(WAKE_LOCK_CHUNK_MS)
        }
        refreshHandler.postDelayed(refreshRunnable, WAKE_LOCK_REFRESH_MS)
    }

    private fun releaseWakeLock() {
        refreshHandler.removeCallbacks(refreshRunnable)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
