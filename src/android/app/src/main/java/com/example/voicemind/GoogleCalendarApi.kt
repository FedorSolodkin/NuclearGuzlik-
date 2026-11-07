package com.example.voicemind

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * REST-клиент Google Calendar API v3. Только чтение primary календаря.
 *
 * Никакие данные записей никогда не отправляются в календарь — только GET.
 */
object GoogleCalendarApi {
    private const val TAG = "GoogleCalendarApi"
    private const val BASE = "https://www.googleapis.com/calendar/v3"

    /**
     * Получает события primary календаря в диапазоне [from, to].
     * Возвращает нормализованные CalendarEvent, отсортированные по времени начала.
     * Блокирующий вызов — только с IO-диспетчера.
     *
     * При 401 пытается обновить access_token один раз.
     */
    fun listPrimaryEvents(ctx: Context, fromMs: Long, toMs: Long, maxResults: Int = 50): List<CalendarEvent>? {
        val access = GoogleCalendarOAuth.getValidAccessToken(ctx) ?: return null
        val events = fetchEvents(ctx, access, fromMs, toMs, maxResults)
        return events ?: run {
            // 401? Попробуем refresh + retry
            val fresh = GoogleCalendarOAuth.refreshAccessToken(ctx) ?: return null
            fetchEvents(ctx, fresh, fromMs, toMs, maxResults)
        }
    }

    private fun fetchEvents(ctx: Context, accessToken: String, fromMs: Long, toMs: Long, maxResults: Int): List<CalendarEvent>? {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val params = mapOf(
            "timeMin"      to iso.format(Date(fromMs)),
            "timeMax"      to iso.format(Date(toMs)),
            "singleEvents" to "true",
            "orderBy"      to "startTime",
            "maxResults"   to maxResults.toString()
        ).map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.joinToString("&")

        val url = "$BASE/calendars/primary/events?$params"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 15_000
                readTimeout    = 15_000
            }
            val code = conn.responseCode
            if (code == 401) {
                conn.disconnect()
                return null
            }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) {
                // Тело ответа Google содержит машинный reason (accessNotConfigured,
                // insufficientPermissions, forbidden и т.п.) — это единственный способ
                // отличить «API не включён» от «scope недостаточен» от «пользователь
                // не в списке тестовых».
                Log.w(TAG, "Events fetch failed: HTTP $code")
                return emptyList()
            }
            parseEventsJson(text)
        } catch (e: Exception) {
            Log.e(TAG, "fetchEvents threw", e)
            emptyList()
        }
    }

    private fun parseEventsJson(json: String): List<CalendarEvent> {
        return try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<CalendarEvent>(items.length())
            val rfc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val id = it.optString("id")
                if (id.isBlank()) continue
                val title = it.optString("summary", "(без названия)")
                val start = parseTime(it.optJSONObject("start"), rfc, dateOnly) ?: continue
                val end   = parseTime(it.optJSONObject("end"),   rfc, dateOnly) ?: (start + 60L * 60 * 1000)
                val loc   = it.optString("location", "").ifBlank { null }
                val descr = it.optString("description", "").ifBlank { null }
                val organizer = it.optJSONObject("organizer")?.optString("email")?.ifBlank { null }
                val attendeesArr = it.optJSONArray("attendees")
                val attendees = if (attendeesArr != null) {
                    (0 until attendeesArr.length()).mapNotNull { j ->
                        attendeesArr.optJSONObject(j)?.optString("email")?.ifBlank { null }
                    }
                } else emptyList()

                out += CalendarEvent(
                    id          = id,
                    title       = title,
                    startMillis = start,
                    endMillis   = end,
                    location    = loc,
                    description = descr,
                    organizer   = organizer,
                    attendees   = attendees,
                    provider    = "google",
                    calendarId  = "primary"
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "parse threw", e)
            emptyList()
        }
    }

    private fun parseTime(obj: JSONObject?, rfc: SimpleDateFormat, dateOnly: SimpleDateFormat): Long? {
        if (obj == null) return null
        val dt = obj.optString("dateTime", "")
        if (dt.isNotBlank()) return try { rfc.parse(dt)?.time } catch (_: Exception) { null }
        val d = obj.optString("date", "")
        if (d.isNotBlank()) return try { dateOnly.parse(d)?.time } catch (_: Exception) { null }
        return null
    }
}
