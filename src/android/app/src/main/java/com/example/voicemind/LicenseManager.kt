package com.example.voicemind

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Доступ к приложению по подписке. Единственный источник правды для [AccessGate].
 *
 * Два режима:
 *  • Подписанная лицензия (задан BuildConfig.LICENSE_PUBLIC_KEY): сервер выдаёт
 *    лицензию, подписанную ECDSA P-256, с полями exp (конец подписки) и
 *    offline_until (≤ 7 дней от выдачи). Подделать или продлить её на телефоне
 *    нельзя — подпись не сойдётся.
 *  • Старый сервер (ключ не задан): /subscription/status; офлайн пускаем не
 *    дольше [LEGACY_OFFLINE_GRACE_MS] с последней успешной проверки.
 *
 * В обоих режимах перевод часов назад (больше чем на час относительно уже
 * виденного времени) требует выйти в сеть.
 */
object LicenseManager {
    private const val TAG = "LicenseManager"
    private const val PREFS = "voicemind_auth"            // то же имя, что у старого AuthPrefs → миграция

    private const val KEY_TOKEN       = "access_token"
    private const val KEY_EXPIRED     = "subscription_expired"   // legacy
    private const val KEY_LICENSE     = "license"
    private const val KEY_LAST_OK     = "last_ok_at"
    private const val KEY_EXPIRES_AT  = "expires_at"
    private const val KEY_MAX_SEEN    = "max_seen_time"

    private const val LEGACY_OFFLINE_GRACE_MS = 7L * 24 * 3600 * 1000
    private const val CLOCK_SKEW_MS = 3600L * 1000
    private const val REFRESH_INTERVAL_MS = 6L * 3600 * 1000

    enum class Access { GRANTED, EXPIRED, NEED_LOGIN, NEED_ONLINE }

    /** Для тестов и диагностики: содержимое проверенной лицензии. */
    data class License(val exp: Long, val offlineUntil: Long, val paid: Boolean)

    private fun prefs(ctx: Context) = SecurePrefs.get(ctx, PREFS)
    private val signedMode get() = BuildConfig.LICENSE_PUBLIC_KEY.isNotBlank()
    @Volatile private var lastRefreshAt = 0L

    fun isLoggedIn(ctx: Context) = prefs(ctx).getString(KEY_TOKEN) != null

    fun access(ctx: Context, now: Long = System.currentTimeMillis()): Access {
        // Сборка без входа (debug или пилот с -PvoicemindAuthRequired=false).
        if (!BuildConfig.AUTH_REQUIRED) return Access.GRANTED
        val p = prefs(ctx)
        if (p.getString(KEY_TOKEN) == null) return Access.NEED_LOGIN

        val maxSeen = p.getLong(KEY_MAX_SEEN, 0L)
        if (now + CLOCK_SKEW_MS < maxSeen) {
            Log.w(TAG, "clock moved back — online check required")
            return Access.NEED_ONLINE
        }
        if (now > maxSeen) p.putLong(KEY_MAX_SEEN, now)

        return if (signedMode) signedAccess(p, now) else legacyAccess(p, now)
    }

    private fun signedAccess(p: SecurePrefs, now: Long): Access {
        val lic = p.getString(KEY_LICENSE)?.let { verify(it, BuildConfig.LICENSE_PUBLIC_KEY) }
            ?: return Access.NEED_ONLINE
        val nowSec = now / 1000
        return when {
            nowSec >= lic.exp -> Access.EXPIRED
            nowSec >= lic.offlineUntil -> Access.NEED_ONLINE
            else -> Access.GRANTED
        }
    }

    private fun legacyAccess(p: SecurePrefs, now: Long): Access {
        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
        if (p.getBoolean(KEY_EXPIRED) || (expiresAt > 0 && now >= expiresAt)) return Access.EXPIRED
        var lastOk = p.getLong(KEY_LAST_OK, 0L)
        if (lastOk == 0L) {
            // Обновились со старой версии, где проверку не запоминали: даём то же
            // окно с момента обновления, а не выкидываем сразу.
            lastOk = now
            p.putLong(KEY_LAST_OK, now)
        }
        return if (now - lastOk > LEGACY_OFFLINE_GRACE_MS) Access.NEED_ONLINE else Access.GRANTED
    }

    /**
     * Проверяет подпись и разбирает лицензию. null — подпись неверна или формат битый.
     * [publicKeyB64] — SubjectPublicKeyInfo (DER) в base64.
     */
    fun verify(license: String, publicKeyB64: String): License? = try {
        val parts = license.split('.')
        require(parts.size == 2)
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val body = Base64.decode(parts[0], flags)
        val sig = Base64.decode(parts[1], flags)
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyB64, Base64.DEFAULT)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(body); verify(sig)
        }
        if (!ok) null else {
            val j = JSONObject(String(body, Charsets.UTF_8))
            require(j.getInt("v") == 1)
            License(j.getLong("exp"), j.getLong("offline_until"), j.optBoolean("paid"))
        }
    } catch (e: Exception) {
        Log.w(TAG, "license rejected: ${e.javaClass.simpleName}")
        null
    }

    /** Сетевая перепроверка (блокирующая). Возвращает новый статус доступа. */
    fun refreshBlocking(ctx: Context): Access {
        val p = prefs(ctx)
        val token = p.getString(KEY_TOKEN) ?: return Access.NEED_LOGIN
        lastRefreshAt = System.currentTimeMillis()
        if (signedMode) {
            val r = SubscriptionApi.license(token)
            when {
                r.httpCode == 401 -> logout(ctx)
                r.success && r.data != null -> {
                    if (verify(r.data, BuildConfig.LICENSE_PUBLIC_KEY) != null) {
                        p.edit(KEY_LICENSE to r.data, KEY_LAST_OK to System.currentTimeMillis())
                    } else Log.e(TAG, "server returned license with bad signature")
                }
            }
        } else {
            val r = SubscriptionApi.status(token)
            when {
                r.httpCode == 401 -> logout(ctx)
                r.success && r.data != null -> p.edit(
                    KEY_EXPIRED to !r.data.active,
                    KEY_EXPIRES_AT to parseIsoMillis(r.data.expiresAt),
                    KEY_LAST_OK to System.currentTimeMillis(),
                )
            }
        }
        return access(ctx)
    }

    /** Фоновая перепроверка не чаще раза в [REFRESH_INTERVAL_MS]. */
    fun refreshInBackgroundIfDue(ctx: Context) {
        if (!BuildConfig.AUTH_REQUIRED) return
        if (System.currentTimeMillis() - lastRefreshAt < REFRESH_INTERVAL_MS) return
        lastRefreshAt = System.currentTimeMillis()
        val appCtx = ctx.applicationContext
        Thread({ runCatching { refreshBlocking(appCtx) } }, "license-refresh").start()
    }

    fun onLoggedIn(ctx: Context, token: String) {
        prefs(ctx).edit(KEY_TOKEN to token, KEY_LICENSE to null, KEY_EXPIRED to null, KEY_EXPIRES_AT to null)
    }

    fun logout(ctx: Context) {
        prefs(ctx).edit(KEY_TOKEN to null, KEY_LICENSE to null, KEY_EXPIRED to null,
            KEY_EXPIRES_AT to null, KEY_LAST_OK to null)
    }

    private fun parseIsoMillis(iso: String): Long? = try {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: Exception) {
        // Старый сервер мог отдавать время без смещения — считаем его UTC.
        try { java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        catch (_: Exception) { null }
    }
}
