package com.guzlik.voice

import android.content.Context

/**
 * Единая точка входа для чтения событий календаря независимо от провайдера.
 *
 * Порядок приоритета:
 *  1. Google, если подключён OAuth-токен.
 *  2. Яндекс, если сохранены CalDAV-креды.
 *  3. Ничего.
 *
 * Оба провайдера могут быть подключены одновременно — тогда события
 * складываются и дедуплицируются по (title + startMillis).
 */
object CalendarProvider {

    /** Есть ли хотя бы один подключённый провайдер. */
    fun isAnyConnected(ctx: Context): Boolean =
        CalendarTokenStore.isConnected(ctx) || YandexCaldavStore.isConnected(ctx)

    /**
     * Читает события из всех подключённых провайдеров в диапазоне [fromMs, toMs).
     * Блокирующий вызов — использовать только из фонового потока.
     */
    fun fetchEvents(ctx: Context, fromMs: Long, toMs: Long, maxPerProvider: Int = 50): List<CalendarEvent> {
        val out = mutableListOf<CalendarEvent>()

        if (CalendarTokenStore.isConnected(ctx)) {
            GoogleCalendarApi.listPrimaryEvents(ctx, fromMs, toMs, maxPerProvider)?.let { out += it }
        }
        if (YandexCaldavStore.isConnected(ctx)) {
            YandexCaldavClient.listEvents(ctx, fromMs, toMs)?.let { out += it }
        }

        // Дедупликация: событие может повторяться, если пользователь синхронизировал
        // Google↔Яндекс сторонним инструментом.
        val seen = mutableSetOf<String>()
        return out.filter { seen.add(dedupeKey(it)) }.sortedBy { it.startMillis }
    }

    private fun dedupeKey(e: CalendarEvent): String =
        "${e.title.trim().lowercase()}|${e.startMillis / 60_000}"

    /** Короткая метка про подключённых провайдеров для UI. */
    fun statusLabel(ctx: Context): String {
        val g = CalendarTokenStore.get(ctx)?.email
        val y = YandexCaldavStore.get(ctx)?.email
        return when {
            g != null && y != null -> "Google: $g · Яндекс: $y"
            g != null              -> "Google: $g"
            y != null              -> "Яндекс: $y"
            else                   -> "Не подключён"
        }
    }
}
