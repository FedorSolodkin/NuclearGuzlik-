package com.example.guzlikvoice

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Минимальный CalDAV-клиент для Яндекс Календаря.
 *
 * Endpoint: https://caldav.yandex.ru
 * Auth:     Basic email + application password
 *
 * Флоу:
 *  1. PROPFIND на /calendars/{email}/ с Depth=1 → находим URL первого calendar.
 *  2. REPORT calendar-query на этом URL с time-range → массив VEVENT.
 *
 * Не поддерживает: rrule expansion, timezones кроме UTC/локальной.
 * Для MVP этого достаточно — Яндекс отдаёт большинство событий уже в expanded виде.
 */
object YandexCaldavClient {
    private const val TAG = "YandexCaldav"
    private const val DEFAULT_BASE = "https://caldav.yandex.ru"

    /**
     * Пытается автоопределить URL первого календаря пользователя.
     * Возвращает URL или null при ошибке.
     * Блокирующий вызов.
     */
    fun discoverCalendarUrl(email: String, appPassword: String): String? {
        val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:resourcetype/>
                    <d:displayname/>
                </d:prop>
            </d:propfind>""".trimIndent()

        val homeUrl = "$DEFAULT_BASE/calendars/$email/"
        val resp = request(homeUrl, "PROPFIND", email, appPassword, propfindBody,
            extraHeaders = mapOf("Depth" to "1", "Content-Type" to "application/xml"))
        if (resp == null) return null

        // Ищем первый <href> внутри response с ресурсом типа calendar.
        // Простой регекс-парсер — Yandex отдаёт достаточно однородный XML.
        val hrefRegex = Regex("<(?:d:)?href>([^<]+)</(?:d:)?href>", RegexOption.IGNORE_CASE)
        val hrefs = hrefRegex.findAll(resp).map { it.groupValues[1] }.toList()
        // Первый href обычно = сам collection, следующие — вложенные.
        // Календари обычно имеют путь вида /calendars/{email}/events-{id}/
        val calendarHref = hrefs.firstOrNull { h ->
            h.contains("/calendars/") && h.endsWith("/") && !h.equals(URL(homeUrl).path, ignoreCase = true)
        } ?: return null

        return if (calendarHref.startsWith("http")) calendarHref
        else DEFAULT_BASE + calendarHref
    }

    /**
     * Возвращает события в диапазоне [from, to] c данного calendar URL.
     * Блокирующий вызов, только IO.
     */
    fun listEvents(ctx: Context, fromMs: Long, toMs: Long): List<CalendarEvent>? {
        val creds = YandexCaldavStore.get(ctx) ?: return null
        return try {
            val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val start = utc.format(Date(fromMs))
            val end   = utc.format(Date(toMs))

            val body = """<?xml version="1.0" encoding="utf-8"?>
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag/>
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:time-range start="$start" end="$end"/>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>""".trimIndent()

            val resp = request(creds.calendarUrl, "REPORT", creds.email, creds.appPassword, body,
                extraHeaders = mapOf("Depth" to "1", "Content-Type" to "application/xml")) ?: return emptyList()

            IcalParser.parseVEvents(resp)
        } catch (e: Exception) {
            Log.e(TAG, "listEvents threw", e)
            emptyList()
        }
    }

    private fun request(
        url: String, method: String, user: String, pass: String, body: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doInput = true
                if (body != null) doOutput = true
                setRequestProperty("Authorization", "Basic " +
                    Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP))
                for ((k, v) in extraHeaders) setRequestProperty(k, v)
                connectTimeout = 15_000
                readTimeout    = 20_000
            }
            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "$method → HTTP $code")
                return null
            }
            text
        } catch (e: Exception) {
            Log.e(TAG, "$method threw: ${e.javaClass.simpleName}")
            null
        }
    }
}
