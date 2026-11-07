package com.guzlik.voice

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Простой парсер iCalendar (RFC 5545) для MVP.
 *
 * Умеет:
 *  - вытащить все VEVENT блоки из ответа CalDAV (в котором iCal-текст
 *    может быть завёрнут в <c:calendar-data>);
 *  - развернуть line-continuations (RFC 5545: строка, начинающаяся с
 *    пробела/таба — продолжение предыдущей);
 *  - раскодировать SUMMARY / LOCATION / DESCRIPTION / UID / ORGANIZER;
 *  - распарсить DTSTART/DTEND в трёх формах:
 *      * `20260906T120000Z`   → UTC
 *      * `20260906T120000`    → локальное (или TZID=Europe/Moscow)
 *      * `VALUE=DATE:20260906` → all-day
 *
 * Не умеет: RRULE expansion (Яндекс обычно уже отдаёт expanded серии),
 * VTIMEZONE, вложенные компоненты, escape-последовательности типа `\,`.
 */
object IcalParser {
    private const val TAG = "IcalParser"

    private val CALENDAR_DATA_REGEX = Regex(
        "<(?:[a-zA-Z]+:)?calendar-data[^>]*>([\\s\\S]*?)</(?:[a-zA-Z]+:)?calendar-data>",
        RegexOption.IGNORE_CASE
    )

    /**
     * Принимает XML-ответ CalDAV (или сам iCal-текст) и возвращает список событий.
     */
    fun parseVEvents(caldavXmlOrIcal: String): List<CalendarEvent> {
        val icalTexts = extractIcalTexts(caldavXmlOrIcal)
        val out = mutableListOf<CalendarEvent>()
        for (ical in icalTexts) {
            val unfolded = unfoldLines(unescapeXml(ical))
            val blocks = extractVEventBlocks(unfolded)
            for (block in blocks) {
                parseSingleVEvent(block)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun extractIcalTexts(text: String): List<String> {
        val matches = CALENDAR_DATA_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        if (matches.isNotEmpty()) return matches
        // Если это уже сырой iCal — возвращаем как есть.
        return if (text.contains("BEGIN:VCALENDAR", ignoreCase = true)) listOf(text) else emptyList()
    }

    private fun unescapeXml(s: String): String =
        s.replace("&lt;", "<")
         .replace("&gt;", ">")
         .replace("&amp;", "&")
         .replace("&quot;", "\"")
         .replace("&apos;", "'")

    /** RFC 5545 line unfolding: следующая строка начинается с ' ' или '\t' → приклеиваем. */
    private fun unfoldLines(text: String): String {
        val sb = StringBuilder()
        val lines = text.replace("\r\n", "\n").split("\n")
        for (line in lines) {
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                sb.append(line.substring(1))
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        return sb.toString()
    }

    private fun extractVEventBlocks(ical: String): List<String> {
        val out = mutableListOf<String>()
        val lines = ical.split("\n")
        var inside = false
        val cur = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VEVENT", ignoreCase = true)) {
                inside = true; cur.setLength(0); cur.append(trimmed).append('\n')
            } else if (trimmed.equals("END:VEVENT", ignoreCase = true)) {
                cur.append(trimmed)
                if (inside) out.add(cur.toString())
                inside = false; cur.setLength(0)
            } else if (inside) {
                cur.append(trimmed).append('\n')
            }
        }
        return out
    }

    private fun parseSingleVEvent(block: String): CalendarEvent? {
        val props = mutableMapOf<String, Pair<Map<String, String>, String>>()
        for (line in block.split("\n")) {
            if (line.isBlank() || line.startsWith("BEGIN:") || line.startsWith("END:")) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val head = line.substring(0, colonIdx)
            val value = line.substring(colonIdx + 1)
            val parts = head.split(';')
            val name = parts[0].uppercase(Locale.US)
            val params = mutableMapOf<String, String>()
            for (i in 1 until parts.size) {
                val eq = parts[i].indexOf('=')
                if (eq > 0) params[parts[i].substring(0, eq).uppercase(Locale.US)] =
                    parts[i].substring(eq + 1)
            }
            props[name] = params to value
        }

        val uid = props["UID"]?.second ?: return null
        val summary = decodeText(props["SUMMARY"]?.second ?: "(без названия)")
        val (startMs, _) = parseIcalDate(props["DTSTART"]) ?: return null
        val (endMs, _)   = parseIcalDate(props["DTEND"])   ?: return null
        val location = props["LOCATION"]?.second?.let { decodeText(it) }?.ifBlank { null }
        val description = props["DESCRIPTION"]?.second?.let { decodeText(it) }?.ifBlank { null }
        val organizer = props["ORGANIZER"]?.second?.removePrefix("MAILTO:")?.removePrefix("mailto:")?.ifBlank { null }

        return CalendarEvent(
            id = uid,
            title = summary,
            startMillis = startMs,
            endMillis = endMs,
            location = location,
            description = description,
            organizer = organizer,
            attendees = emptyList(),
            provider = "yandex",
            calendarId = "primary"
        )
    }

    /** Возвращает (millis, isAllDay) или null. */
    private fun parseIcalDate(prop: Pair<Map<String, String>, String>?): Pair<Long, Boolean>? {
        if (prop == null) return null
        val (params, value) = prop
        val tzid = params["TZID"]
        val isDate = params["VALUE"]?.equals("DATE", ignoreCase = true) == true || value.length == 8

        return try {
            if (isDate) {
                val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                fmt.parse(value)?.let { it.time to true }
            } else if (value.endsWith("Z")) {
                val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                fmt.parse(value)?.let { it.time to false }
            } else {
                val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                    timeZone = if (tzid != null) TimeZone.getTimeZone(tzid) else TimeZone.getDefault()
                }
                fmt.parse(value)?.let { it.time to false }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseIcalDate failed for '$value': ${e.message}")
            null
        }
    }

    private fun decodeText(s: String): String =
        s.replace("\\n", "\n")
         .replace("\\N", "\n")
         .replace("\\,", ",")
         .replace("\\;", ";")
         .replace("\\\\", "\\")
}
