package com.guzlik.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Показывает пользователю уведомление о завершении долгой обработки
 * (транскрибация, диаризация, Q&A, шаблон). Устраняет ситуацию «запустил
 * анализ, свернул, не понимаю готово ли».
 *
 * Уведомление open-clickable → возвращает в MainActivity с EXTRA_OPEN_ENTRY_TS.
 * MainActivity сам решает как показать запись (переключить вкладку на историю,
 * прокрутить к нужной дате и т.д.).
 */
object ProcessingNotifier {

    const val EXTRA_OPEN_ENTRY_TS = "open_entry_ts"

    private const val CHANNEL_ID = "processing_ready"
    private const val NOTIF_ID_BASE = 2000

    fun notifyReady(ctx: Context, entryTimestamp: Long, title: String,
                    subtitle: String = "Откройте, чтобы посмотреть") {
        ensureChannel(ctx)

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_ENTRY_TS, entryTimestamp)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            entryTimestamp.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // Разные timestamp → разные notification id, чтобы уведомления не затирали друг друга.
        val id = NOTIF_ID_BASE + (entryTimestamp % 10000L).toInt()
        nm.notify(id, notif)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Готовые обработки",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Уведомляет когда транскрипт, саммари или Q&A готовы"
        }
        nm.createNotificationChannel(channel)
    }
}
