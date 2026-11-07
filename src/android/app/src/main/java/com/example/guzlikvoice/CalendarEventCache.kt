package com.example.guzlikvoice

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Локальный кэш событий календаря. TTL = 24 часа.
 * Хранится в filesDir/calendar_events.json.
 *
 * Никакие данные записей никогда не отправляются в календарь — только читаем.
 */
object CalendarEventCache {
    private const val TAG = "CalendarEventCache"
    private const val FILE_NAME = "calendar_events.json"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    private val gson = Gson()
    private val lock = Any()

    private data class Cache(
        val fetchedAt: Long,
        val events: List<CalendarEvent>
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun save(ctx: Context, events: List<CalendarEvent>): Unit = synchronized(lock) {
        val target = file(ctx)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            val cache = Cache(System.currentTimeMillis(), events)
            tmp.writeText(gson.toJson(cache))
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            runCatching { tmp.delete() }
        }
    }

    /** Возвращает кэшированные события, если ещё не устарели, иначе null. */
    fun getFresh(ctx: Context): List<CalendarEvent>? = synchronized(lock) {
        try {
            val f = file(ctx)
            if (!f.exists()) return null
            val cache = gson.fromJson<Cache>(f.readText(), object : TypeToken<Cache>() {}.type) ?: return null
            val age = System.currentTimeMillis() - cache.fetchedAt
            if (age > TTL_MS) return null
            cache.events
        } catch (e: Exception) {
            Log.e(TAG, "read failed", e)
            null
        }
    }

    fun getAny(ctx: Context): List<CalendarEvent> = synchronized(lock) {
        try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            val cache = gson.fromJson<Cache>(f.readText(), object : TypeToken<Cache>() {}.type) ?: return emptyList()
            cache.events
        } catch (_: Exception) { emptyList() }
    }

    fun clear(ctx: Context) = synchronized(lock) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
