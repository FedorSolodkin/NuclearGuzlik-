package com.example.voicemind

import android.content.Context
import android.util.Log

/**
 * Учётка Яндекс.Календаря (CalDAV): email + пароль приложения.
 * Хранится в [SecurePrefs].
 */
object YandexCaldavStore {
    private const val TAG = "YandexCaldavStore"
    private const val PREFS = "voicemind_yandex_caldav"

    private const val KEY_EMAIL         = "email"
    private const val KEY_APP_PASSWORD  = "app_password"
    private const val KEY_CALENDAR_URL  = "calendar_url"

    // Пароль приложения Яндекса — постоянный секрет с полным доступом
    // к CalDAV/IMAP. Только в SecurePrefs; без Keystore не сохраняем вовсе.
    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)

    data class Creds(val email: String, val appPassword: String, val calendarUrl: String)

    fun save(ctx: Context, creds: Creds) {
        val ok = prefs(ctx).edit(
            KEY_EMAIL to creds.email,
            KEY_APP_PASSWORD to creds.appPassword,
            KEY_CALENDAR_URL to creds.calendarUrl,
        )
        if (!ok) Log.e(TAG, "save: keystore unavailable — creds NOT persisted")
    }

    fun get(ctx: Context): Creds? {
        val p = prefs(ctx)
        val email = p.getString(KEY_EMAIL) ?: return null
        val pass  = p.getString(KEY_APP_PASSWORD) ?: return null
        val url   = p.getString(KEY_CALENDAR_URL) ?: return null
        return Creds(email, pass, url)
    }

    fun clear(ctx: Context) = prefs(ctx).clear()

    fun isConnected(ctx: Context): Boolean = get(ctx) != null
}
