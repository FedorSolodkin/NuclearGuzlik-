package com.example.voicemind

import android.content.Context

/**
 * Сессионный unlock защищённых записей: после биометрии/PIN — [TTL_SEC] секунд
 * без повтора (столько же Keystore считает аутентификацию действительной, см.
 * [VaultManager]). Сбрасывается при уходе приложения в фон, вместе с
 * расшифрованными на время копиями аудио.
 */
object VaultSession {
    const val TTL_SEC = 5 * 60
    // Чуть меньше, чем у Keystore: UI не должен считать сессию живой, когда
    // ключ уже требует повторной аутентификации.
    private const val TTL_MS = TTL_SEC * 1000L - 10_000L

    @Volatile private var unlockedUntil: Long = 0L

    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    fun unlock() { unlockedUntil = System.currentTimeMillis() + TTL_MS }

    fun lock(ctx: Context? = null) {
        unlockedUntil = 0L
        ctx?.let { VaultManager.wipePlaybackCache(it.applicationContext) }
    }
}
