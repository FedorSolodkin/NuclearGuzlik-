package com.guzlik.voice

import android.content.Context
import android.util.Log

/**
 * Безопасное хранилище OAuth-токенов календаря (access + refresh).
 * Хранится в [SecurePrefs] (AES-256-GCM с ключом в Android Keystore).
 *
 * Никогда не логируется в открытом виде.
 */
object CalendarTokenStore {
    private const val TAG = "CalendarTokenStore"
    private const val PREFS = "guzlikvoice_calendar_tokens"

    private const val KEY_ACCESS_TOKEN  = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT    = "expires_at"
    private const val KEY_ACCOUNT_EMAIL = "account_email"
    private const val KEY_PROVIDER      = "provider"

    // Открытым текстом токены не храним: если Keystore недоступен, SecurePrefs
    // ничего не запишет, и isConnected()==false — пользователь переподключится.
    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)

    data class Tokens(
        val accessToken:  String,
        val refreshToken: String?,
        val expiresAt:    Long,
        val email:        String?,
        val provider:     String
    )

    fun save(ctx: Context, tokens: Tokens) {
        val ok = prefs(ctx).edit(
            KEY_ACCESS_TOKEN  to tokens.accessToken,
            KEY_REFRESH_TOKEN to tokens.refreshToken,
            KEY_EXPIRES_AT    to tokens.expiresAt,
            KEY_ACCOUNT_EMAIL to tokens.email,
            KEY_PROVIDER      to tokens.provider,
        )
        if (!ok) Log.e(TAG, "save: keystore unavailable — token NOT persisted")
    }

    fun get(ctx: Context): Tokens? {
        val p = prefs(ctx)
        val access = p.getString(KEY_ACCESS_TOKEN) ?: return null
        return Tokens(
            accessToken  = access,
            refreshToken = p.getString(KEY_REFRESH_TOKEN),
            expiresAt    = p.getLong(KEY_EXPIRES_AT, 0L),
            email        = p.getString(KEY_ACCOUNT_EMAIL),
            provider     = p.getString(KEY_PROVIDER) ?: "google"
        )
    }

    fun clear(ctx: Context) = prefs(ctx).clear()

    fun isConnected(ctx: Context): Boolean = get(ctx) != null
}
