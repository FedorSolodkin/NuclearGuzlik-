package com.example.guzlikvoice

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Зашифрованные настройки (токены, пароли приложений): значения шифруются
 * AES-256-GCM ключом из Android Keystore, лежат в обычном SharedPreferences.
 *
 * Заменяет androidx.security:security-crypto (EncryptedSharedPreferences объявлен
 * устаревшим). Старые данные переносятся при первом обращении: читаем старый
 * файл через [LegacyEncryptedPrefs], пишем сюда, старый удаляем.
 *
 * Имя ключа входит в AAD — значение одного ключа нельзя подложить под другой.
 * Если Keystore недоступен, get() отдаёт null, а put() ничего не пишет:
 * открытым текстом секреты не сохраняем никогда.
 */
class SecurePrefs private constructor(private val ctx: Context, private val name: String) {

    companion object {
        private const val TAG = "SecurePrefs"
        private const val KEY_ALIAS = "guzlikvoice_prefs_v2"
        private const val IV_LEN = 12
        private val instances = HashMap<String, SecurePrefs>()

        fun get(ctx: Context, name: String): SecurePrefs = synchronized(instances) {
            instances.getOrPut(name) { SecurePrefs(ctx.applicationContext, name).also { it.migrateLegacy() } }
        }

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec) }.generateKey()
        }
    }

    private val sp: SharedPreferences = ctx.getSharedPreferences("${name}_v2", Context.MODE_PRIVATE)

    private fun encrypt(key: String, value: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(key.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e(TAG, "encrypt failed: ${e.javaClass.simpleName}")
        null
    }

    private fun decrypt(key: String, stored: String): String? = try {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        c.updateAAD(key.toByteArray(Charsets.UTF_8))
        String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e(TAG, "decrypt failed: ${e.javaClass.simpleName}")
        null
    }

    fun getString(key: String): String? = sp.getString(key, null)?.let { decrypt(key, it) }
    fun getLong(key: String, def: Long = 0L): Long = getString(key)?.toLongOrNull() ?: def
    fun getBoolean(key: String, def: Boolean = false): Boolean = getString(key)?.toBooleanStrictOrNull() ?: def
    fun contains(key: String): Boolean = sp.contains(key)

    /** Пакетная запись; null удаляет ключ. Возвращает false, если что-то не зашифровалось. */
    fun edit(vararg pairs: Pair<String, Any?>): Boolean {
        val e = sp.edit()
        var ok = true
        for ((k, v) in pairs) {
            if (v == null) { e.remove(k); continue }
            val enc = encrypt(k, v.toString())
            if (enc == null) ok = false else e.putString(k, enc)
        }
        if (ok) e.apply()
        return ok
    }

    fun putString(key: String, value: String?) = edit(key to value)
    fun putLong(key: String, value: Long) = edit(key to value)
    fun putBoolean(key: String, value: Boolean) = edit(key to value)
    fun remove(key: String) { sp.edit().remove(key).apply() }
    fun clear() { sp.edit().clear().apply() }

    private fun migrateLegacy() {
        val legacy = LegacyEncryptedPrefs.readAll(ctx, name) ?: return
        val pairs = legacy.map { (k, v) -> k to v }.toTypedArray()
        if (pairs.isEmpty() || edit(*pairs)) {
            LegacyEncryptedPrefs.delete(ctx, name)
            Log.i(TAG, "migrated ${pairs.size} keys from legacy $name")
        }
    }
}
