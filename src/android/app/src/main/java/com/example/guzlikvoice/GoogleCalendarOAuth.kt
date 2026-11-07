package com.example.guzlikvoice

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 PKCE-флоу для Google Calendar (public client, без секрета).
 *
 * Endpoints:
 *   auth  = https://accounts.google.com/o/oauth2/v2/auth
 *   token = https://oauth2.googleapis.com/token
 *
 * Redirect URI: {applicationId}:/oauthredirect  — обрабатывается
 * [OAuthRedirectActivity] по интент-фильтру в манифесте.
 *
 * OAuth Client ID берётся из BuildConfig.GOOGLE_OAUTH_CLIENT_ID. Если пусто —
 * функция [isConfigured] возвращает false и UI показывает подсказку.
 */
object GoogleCalendarOAuth {
    private const val TAG = "GoogleCalOAuth"

    private const val AUTH_URL  = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    const val SCOPE = "https://www.googleapis.com/auth/calendar.readonly openid email"

    // Хранится в памяти между запуском Custom Tab и обработкой redirect'а.
    // Cleared после успешного/неуспешного обмена.
    @Volatile private var pendingCodeVerifier: String? = null
    @Volatile private var pendingState: String? = null

    fun isConfigured(): Boolean = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank()

    // iOS OAuth-клиент диктует redirect по reversed-DNS схеме client ID.
    // Формат: com.googleusercontent.apps.{prefix}:/oauthredirect
    private fun redirectUri(@Suppress("UNUSED_PARAMETER") ctx: Context): String {
        val prefix = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.removeSuffix(".apps.googleusercontent.com")
        return "com.googleusercontent.apps.$prefix:/oauthredirect"
    }

    /** Запускает Custom Tab с ссылкой на Google OAuth. */
    fun startAuthFlow(ctx: Context) {
        if (!isConfigured()) {
            Log.w(TAG, "GOOGLE_OAUTH_CLIENT_ID is empty — configure in gradle.properties")
            return
        }
        val verifier = generateCodeVerifier()
        val challenge = codeChallengeS256(verifier)
        val state = generateState()
        pendingCodeVerifier = verifier
        pendingState = state

        val params = mapOf(
            "response_type"          to "code",
            "client_id"              to BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            "redirect_uri"           to redirectUri(ctx),
            "scope"                  to SCOPE,
            "state"                  to state,
            "code_challenge"         to challenge,
            "code_challenge_method"  to "S256",
            "access_type"            to "offline",  // получить refresh_token
            "prompt"                 to "consent"   // всегда показывать consent-экран, чтобы получить refresh
        )
        val url = AUTH_URL + "?" + params.map { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }.joinToString("&")

        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(ctx, Uri.parse(url))
    }

    /**
     * Вызывается из [OAuthRedirectActivity] с параметрами callback'а.
     * Обменивает code на токены и сохраняет их. Возвращает true при успехе.
     * Блокирующий сетевой вызов — выполнять с IO-диспетчера.
     */
    fun handleRedirect(ctx: Context, uri: Uri): Boolean {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            Log.w(TAG, "OAuth error: $error")
            pendingCodeVerifier = null; pendingState = null
            return false
        }
        if (code == null) {
            Log.w(TAG, "OAuth callback без code")
            return false
        }
        val expectedState = pendingState
        if (expectedState != null && expectedState != state) {
            Log.w(TAG, "OAuth state mismatch — возможная атака")
            pendingCodeVerifier = null; pendingState = null
            return false
        }
        val verifier = pendingCodeVerifier ?: run {
            Log.w(TAG, "Нет сохранённого code_verifier — сессия потеряна")
            return false
        }
        pendingCodeVerifier = null
        pendingState = null

        return exchangeCodeForTokens(ctx, code, verifier)
    }

    private fun exchangeCodeForTokens(ctx: Context, code: String, verifier: String): Boolean {
        val body = mapOf(
            "code"          to code,
            "client_id"     to BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            "redirect_uri"  to redirectUri(ctx),
            "grant_type"    to "authorization_code",
            "code_verifier" to verifier
        ).map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.joinToString("&")

        return try {
            val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15_000
                readTimeout    = 15_000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code2 = conn.responseCode
            val text = (if (code2 in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            conn.disconnect()

            if (code2 !in 200..299) {
                Log.w(TAG, "Token exchange failed: HTTP $code2")
                return false
            }

            val json = JSONObject(text)
            val access  = json.optString("access_token")
            val refresh = json.optString("refresh_token", "").ifEmpty { null }
            val expIn   = json.optLong("expires_in", 3600L)
            val idToken = json.optString("id_token", "")
            val email   = idToken.takeIf { it.isNotBlank() }?.let { parseEmailFromIdToken(it) }

            if (access.isBlank()) {
                Log.w(TAG, "Пустой access_token")
                return false
            }

            CalendarTokenStore.save(ctx, CalendarTokenStore.Tokens(
                accessToken  = access,
                refreshToken = refresh,
                expiresAt    = System.currentTimeMillis() + expIn * 1000L,
                email        = email,
                provider     = "google"
            ))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange threw", e)
            false
        }
    }

    /**
     * Обновляет access_token через refresh_token. Возвращает актуальный access,
     * или null если refresh не удался (пользователь должен переподключиться).
     */
    fun refreshAccessToken(ctx: Context): String? {
        val tokens = CalendarTokenStore.get(ctx) ?: return null
        val refresh = tokens.refreshToken ?: return null

        val body = mapOf(
            "client_id"     to BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            "refresh_token" to refresh,
            "grant_type"    to "refresh_token"
        ).map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.joinToString("&")

        return try {
            val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15_000
                readTimeout    = 15_000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "Refresh failed: HTTP $code")
                return null
            }
            val json = JSONObject(text)
            val access = json.optString("access_token")
            val expIn  = json.optLong("expires_in", 3600L)
            if (access.isBlank()) return null

            CalendarTokenStore.save(ctx, tokens.copy(
                accessToken = access,
                expiresAt   = System.currentTimeMillis() + expIn * 1000L
            ))
            access
        } catch (e: Exception) {
            Log.e(TAG, "refresh threw", e)
            null
        }
    }

    /** Возвращает свежий access_token: из storage, если не истёк; иначе рефрешит. */
    fun getValidAccessToken(ctx: Context): String? {
        val tokens = CalendarTokenStore.get(ctx) ?: return null
        val now = System.currentTimeMillis()
        // Обновляем за 60 сек до истечения — с запасом
        return if (tokens.expiresAt - 60_000L > now) tokens.accessToken
        else refreshAccessToken(ctx)
    }

    // ── PKCE helpers ──────────────────────────────────────────────────────────

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallengeS256(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Извлекает email из payload id_token без проверки подписи (только для UI-подписи). */
    private fun parseEmailFromIdToken(idToken: String): String? {
        return try {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(decoded).optString("email").ifBlank { null }
        } catch (_: Exception) { null }
    }
}
