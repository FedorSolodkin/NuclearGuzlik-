package com.guzlik.voice

import android.content.Context

/**
 * Автопривязка записей к событиям календаря.
 *
 * Стратегия: событие «покрывает» запись, если её timestamp попадает в интервал
 * [start - GRACE_MS, end + GRACE_MS]. Из подходящих выбираем самое короткое
 * (более специфичное), чтобы общее собрание «День» не побеждало конкретную встречу.
 */
object CalendarLinker {
    private const val GRACE_MS = 5L * 60 * 1000

    fun findMatchingEvent(ctx: Context, timestampMs: Long): CalendarEvent? {
        val events = CalendarEventCache.getAny(ctx)
        if (events.isEmpty()) return null
        return events
            .filter { timestampMs in (it.startMillis - GRACE_MS)..(it.endMillis + GRACE_MS) }
            .minByOrNull { it.endMillis - it.startMillis }
    }
}
