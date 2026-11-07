package com.guzlik.voice

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Только чтение старых EncryptedSharedPreferences (до v3.10) для переноса в
 * [SecurePrefs]. Новые данные сюда не пишутся. Когда у всех пользователей
 * пройдёт миграция, файл и зависимость security-crypto можно удалить.
 */
@Suppress("DEPRECATION")
internal object LegacyEncryptedPrefs {
    private const val TAG = "LegacyEncryptedPrefs"

    private fun file(ctx: Context, name: String) =
        File(ctx.applicationInfo.dataDir, "shared_prefs/$name.xml")

    /** null — старого файла нет или его не удалось прочитать. */
    fun readAll(ctx: Context, name: String): Map<String, Any>? {
        if (!file(ctx, name).exists()) return null
        return try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val p = EncryptedSharedPreferences.create(ctx, name, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            p.all.filterValues { it != null }.mapValues { it.value!! }
        } catch (e: Exception) {
            Log.e(TAG, "legacy read failed for $name: ${e.javaClass.simpleName}")
            null
        }
    }

    fun delete(ctx: Context, name: String) {
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        if (android.os.Build.VERSION.SDK_INT >= 24) ctx.deleteSharedPreferences(name)
        else file(ctx, name).delete()
    }
}
