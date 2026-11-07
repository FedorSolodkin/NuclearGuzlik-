package com.guzlik.voice

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Клиент бэкенда подписки. Синхронный — вызывать только из фонового потока. */
object SubscriptionApi {
    // Адрес задаётся при сборке (gradle-свойство guzlikvoiceApiUrl), чтобы переезд
    // на свой сервер не требовал правок кода. См. server/README.md.
    private val BASE_URL = BuildConfig.API_BASE_URL.trimEnd('/')

    data class ApiResult<T>(
        val success: Boolean,
        val data: T? = null,
        val error: String? = null,
        val httpCode: Int? = null,
    )

    data class AuthResult(val token: String)
    data class StatusResult(val active: Boolean, val isPaid: Boolean, val expiresAt: String, val daysLeft: Int)

    fun register(email: String, password: String): ApiResult<AuthResult> =
        postJson("/auth/register", JSONObject().put("email", email).put("password", password)) { json ->
            AuthResult(json.getString("access_token"))
        }

    fun login(email: String, password: String): ApiResult<AuthResult> =
        postJson("/auth/login", JSONObject().put("email", email).put("password", password)) { json ->
            AuthResult(json.getString("access_token"))
        }

    /** Подписанная лицензия (новый сервер, см. server/). */
    fun license(token: String): ApiResult<String> = getJson("/subscription/license", token) { json ->
        json.getString("license")
    }

    fun status(token: String): ApiResult<StatusResult> = getJson("/subscription/status", token) { json ->
        StatusResult(
            active = json.getBoolean("active"),
            isPaid = json.getBoolean("is_paid"),
            expiresAt = json.getString("expires_at"),
            daysLeft = json.getInt("days_left"),
        )
    }

    private fun <T> getJson(path: String, token: String, parse: (JSONObject) -> T): ApiResult<T> {
        return try {
            val conn = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val body = readBody(conn, code)
            conn.disconnect()
            if (code in 200..299) ApiResult(true, parse(JSONObject(body)), httpCode = code)
            else ApiResult(false, error = extractError(body, code), httpCode = code)
        } catch (e: Exception) {
            android.util.Log.w("SubscriptionApi", "GET $path failed: ${e.javaClass.simpleName}")
            ApiResult(false, error = "Нет соединения с сервером")
        }
    }

    private fun <T> postJson(path: String, body: JSONObject, parse: (JSONObject) -> T): ApiResult<T> {
        return try {
            val conn = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val respBody = readBody(conn, code)
            conn.disconnect()
            if (code in 200..299) {
                ApiResult(true, parse(JSONObject(respBody)), httpCode = code)
            } else {
                ApiResult(false, error = extractError(respBody, code), httpCode = code)
            }
        } catch (e: Exception) {
            ApiResult(false, error = "Нет соединения с сервером")
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String =
        (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    private fun extractError(body: String, code: Int): String =
        try {
            val json = JSONObject(body)
            // FastAPI отдаёт ошибки валидации массивом: [{"msg": ...}, ...]
            json.optJSONArray("detail")?.let { arr ->
                if (code == 422) "Проверьте email и пароль" else arr.optJSONObject(0)?.optString("msg")
            } ?: json.optString("detail").ifBlank { "Ошибка сервера ($code)" }
        } catch (_: Exception) {
            "Ошибка сервера ($code)"
        }
}
