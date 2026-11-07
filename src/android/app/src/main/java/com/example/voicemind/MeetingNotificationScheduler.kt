package com.example.voicemind

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Планировщик напоминаний о встречах из подключённого календаря.
 *
 * За 2 минуты до начала события AlarmManager будит [MeetingReminderReceiver],
 * который показывает уведомление. Используется неточный allowWhileIdle —
 * SCHEDULE_EXACT_ALARM не требуется.
 *
 * Идемпотентно: перед созданием новых алармов отменяет все старые
 * (по стабильному ID = event.id.hashCode()).
 */
object MeetingNotificationScheduler {
    private const val TAG = "MeetingScheduler"
    private const val PREFS = "voicemind_meeting_alarms"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"

    /** Как заранее показать уведомление (мс). */
    private const val LEAD_MS = 2L * 60 * 1000

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun buildPendingIntent(ctx: Context, notifId: Int, title: String, timeLabel: String): PendingIntent {
        val intent = Intent(ctx, MeetingReminderReceiver::class.java).apply {
            putExtra(MeetingReminderReceiver.EXTRA_TITLE, title)
            putExtra(MeetingReminderReceiver.EXTRA_TIME_LABEL, timeLabel)
            putExtra(MeetingReminderReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            ctx, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Перепланирует все будущие события. Отменяет ранее зарегистрированные,
     * запоминает новые ID в SharedPreferences.
     */
    fun rescheduleAll(ctx: Context, events: List<CalendarEvent>) {
        cancelAll(ctx)

        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val ids = mutableSetOf<String>()
        val am = am(ctx)

        for (event in events) {
            val fireAt = event.startMillis - LEAD_MS
            if (fireAt <= now) continue
            val notifId = event.id.hashCode()
            val pi = buildPendingIntent(ctx, notifId, event.title, fmt.format(Date(event.startMillis)))
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
                ids.add(event.id)
            } catch (e: SecurityException) {
                Log.w(TAG, "setAndAllowWhileIdle denied: ${e.message}")
            }
        }

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_SCHEDULED_IDS, ids)
            .apply()
    }

    fun cancelAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()
        val am = am(ctx)
        for (id in ids) {
            val notifId = id.hashCode()
            val pi = PendingIntent.getBroadcast(
                ctx, notifId, Intent(ctx, MeetingReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) am.cancel(pi)
        }
        prefs.edit().remove(KEY_SCHEDULED_IDS).apply()
    }
}
