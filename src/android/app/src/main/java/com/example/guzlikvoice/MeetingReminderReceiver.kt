package com.example.guzlikvoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Ловит будильник AlarmManager перед началом встречи и показывает уведомление
 * с action «Записать» — открывает MainActivity сразу на вкладке записи.
 */
class MeetingReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID   = "meeting_reminders"
        const val CHANNEL_NAME = "Напоминания о встречах"

        const val EXTRA_TITLE       = "title"
        const val EXTRA_TIME_LABEL  = "time_label"
        const val EXTRA_NOTIF_ID    = "notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        ensureChannel(context)

        val title      = intent.getStringExtra(EXTRA_TITLE)     ?: "Встреча"
        val timeLabel  = intent.getStringExtra(EXTRA_TIME_LABEL) ?: ""
        val notifId    = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_tab", "record")
        }
        val pi = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Скоро встреча: $title")
            .setContentText(if (timeLabel.isNotBlank()) "Начало в $timeLabel" else "Открыть запись")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Записать", pi)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(notifId, notif)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Напоминания за 2 минуты до начала встреч из календаря"
        }
        nm.createNotificationChannel(channel)
    }
}
