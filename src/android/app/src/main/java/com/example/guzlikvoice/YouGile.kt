package com.example.guzlikvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouGile REST API v2 — отправка протокола/расшифровки в задачу.
 *
 * Отправка только по явной кнопке: по умолчанию всё остаётся на телефоне.
 * Адрес сервера настраивается — для коробочной YouGile данные не покидают компанию.
 * Храним только API-ключ компании (в [SecurePrefs]), пароль не сохраняется.
 *
 * Ключ: POST /auth/companies {login, password} → компании; POST /auth/keys
 * {login, password, companyId} → {key}. Дальше заголовок `Authorization: Bearer <key>`.
 * Лимит API — 50 запросов в минуту на компанию; вызовы блокирующие, только не с UI-потока.
 */
object YouGile {
    const val DEFAULT_SERVER = "https://yougile.com"
    private const val PREFS = "yougile"
    private const val K_SERVER = "server"
    private const val K_KEY = "api_key"
    private const val K_COMPANY = "company"
    private const val K_COLUMN_ID = "column_id"
    private const val K_COLUMN_TITLE = "column_title"

    data class Item(val id: String, val title: String)
    class ApiException(message: String) : Exception(message)

    // ── Настройки ────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)

    fun isConfigured(ctx: Context): Boolean = !prefs(ctx).getString(K_KEY).isNullOrBlank()
    fun server(ctx: Context): String = prefs(ctx).getString(K_SERVER) ?: DEFAULT_SERVER
    fun companyName(ctx: Context): String = prefs(ctx).getString(K_COMPANY).orEmpty()
    fun defaultColumn(ctx: Context): Item? {
        val id = prefs(ctx).getString(K_COLUMN_ID) ?: return null
        return Item(id, prefs(ctx).getString(K_COLUMN_TITLE).orEmpty())
    }
    fun setDefaultColumn(ctx: Context, column: Item, path: String) {
        prefs(ctx).edit(K_COLUMN_ID to column.id, K_COLUMN_TITLE to path)
    }
    fun disconnect(ctx: Context) = prefs(ctx).clear()

    /** Адрес сервера: только https, без хвоста /api-v2. */
    fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/').removeSuffix("/api-v2")
        if (s.isEmpty()) s = DEFAULT_SERVER
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        if (s.startsWith("http://")) throw ApiException("Нужен адрес с https://")
        return s
    }

    // ── Вход ─────────────────────────────────────────────────────────────────

    fun companies(server: String, login: String, password: String): List<Item> {
        val body = JSONObject().put("login", login).put("password", password)
        return items(call("$server/api-v2/auth/companies", "POST", null, body))
    }

    /** Получает ключ компании и сохраняет его (пароль не сохраняется). */
    fun connect(ctx: Context, server: String, login: String, password: String, company: Item) {
        val body = JSONObject().put("login", login).put("password", password).put("companyId", company.id)
        val res = call("$server/api-v2/auth/keys", "POST", null, body)
        val key = when (res) {
            is JSONObject -> res.optString("key")
            is JSONArray -> res.optString(0)
            else -> res?.toString().orEmpty().trim('"')
        }
        if (key.isBlank()) throw ApiException("YouGile не выдал ключ")
        prefs(ctx).edit(K_SERVER to server, K_KEY to key, K_COMPANY to company.title)
    }

    // ── Справочники ──────────────────────────────────────────────────────────

    fun projects(ctx: Context): List<Item> = items(get(ctx, "projects", mapOf("limit" to "1000")))
    fun boards(ctx: Context, projectId: String): List<Item> =
        items(get(ctx, "boards", mapOf("projectId" to projectId, "limit" to "1000")))
    fun columns(ctx: Context, boardId: String): List<Item> =
        items(get(ctx, "columns", mapOf("boardId" to boardId, "limit" to "1000")))

    /** Задачи по части названия (в выбранной колонке, если она есть). */
    fun searchTasks(ctx: Context, title: String, columnId: String?): List<Item> {
        val q = mutableMapOf("title" to title, "limit" to "30")
        if (columnId != null) q["columnId"] = columnId
        return items(get(ctx, "task-list", q))
    }

    // ── Запись ───────────────────────────────────────────────────────────────

    /** Новая задача; [checklist] — пункты чек-листа «Задачи». Возвращает id задачи. */
    fun createTask(ctx: Context, columnId: String, title: String, description: String, checklist: List<String>): String {
        val body = JSONObject().put("title", title.take(250)).put("columnId", columnId)
            .put("description", description)
        if (checklist.isNotEmpty()) {
            val items = JSONArray()
            checklist.forEach { items.put(JSONObject().put("title", it.take(250)).put("isCompleted", false)) }
            body.put("checklists", JSONArray().put(JSONObject().put("title", "Задачи").put("items", items)))
        }
        val res = call(url(ctx, "tasks", emptyMap()), "POST", key(ctx), body) as? JSONObject
        return res?.optString("id").orEmpty().ifEmpty { throw ApiException("YouGile не вернул id задачи") }
    }

    /** Сообщение в чат задачи (id чата задачи = id задачи). Длинный текст — несколькими сообщениями. */
    fun postToTask(ctx: Context, taskId: String, text: String) {
        for (part in split(text, 8000)) {
            call(url(ctx, "chats/$taskId/messages", emptyMap()), "POST", key(ctx), JSONObject().put("text", part))
        }
    }

    /** Пункты из раздела «Задачи» протокола: строки «- [ ] …». */
    fun tasksFromProtocol(protocol: String): List<String> {
        val out = mutableListOf<String>()
        var inTasks = false
        for (line in protocol.lines()) {
            val t = line.trim()
            if (t.startsWith("#")) { inTasks = t.lowercase().contains("задач"); continue }
            if (!inTasks) continue
            val item = t.removePrefix("-").removePrefix("*").trim().removePrefix("[ ]").removePrefix("[x]").trim()
                .removePrefix("☐").removePrefix("•").trim()
            if (item.isNotEmpty() && !item.lowercase().startsWith("не обсуждал") && !item.lowercase().startsWith("явных")) out += item
        }
        return out
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun key(ctx: Context) = prefs(ctx).getString(K_KEY) ?: throw ApiException("YouGile не подключён")

    private fun url(ctx: Context, path: String, query: Map<String, String>): String {
        val q = query.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return "${server(ctx)}/api-v2/$path" + if (q.isEmpty()) "" else "?$q"
    }

    private fun get(ctx: Context, path: String, query: Map<String, String>) = call(url(ctx, path, query), "GET", key(ctx), null)

    private fun items(res: Any?): List<Item> {
        val arr = when (res) {
            is JSONArray -> res
            is JSONObject -> res.optJSONArray("content") ?: JSONArray().put(res)
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("deleted")) return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            Item(id, o.optString("title").ifEmpty { o.optString("name") })
        }
    }

    private fun call(url: String, method: String, bearer: String?, body: JSONObject?): Any? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(when (code) {
                401 -> "YouGile: неверный логин, пароль или ключ"
                403 -> "YouGile: нет доступа к этому разделу"
                404 -> "YouGile: не найдено"
                429 -> "YouGile: слишком много запросов, попробуйте через минуту"
                else -> "YouGile ответил ошибкой $code"
            })
            val t = text.trim()
            return when {
                t.startsWith("{") -> JSONObject(t)
                t.startsWith("[") -> JSONArray(t)
                else -> t
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val cut = rest.lastIndexOf('\n', max).takeIf { it > max / 2 } ?: max
            out += rest.substring(0, cut); rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
