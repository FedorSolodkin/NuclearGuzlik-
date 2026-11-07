package com.example.voicemind

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Защищённые записи: текст и аудио шифруются AES-256-GCM ключом из Android Keystore.
 *
 * На Android 11+ ключ привязан к аутентификации пользователя: расшифровать можно
 * только в течение [VaultSession.TTL_SEC] после биометрии (класс STRONG) или
 * PIN/пароля. Это уже не просто «экран блокировки» в UI — без аутентификации
 * сам Keystore откажется расшифровывать, даже если кто-то достал файлы.
 * На Android 8–10 привязка не включается (там BiometricPrompt не умеет
 * STRONG|DEVICE_CREDENTIAL), ключ просто хранится в Keystore.
 *
 * Если пользователь снимет блокировку экрана, Android уничтожит ключ, и
 * защищённые записи станут нечитаемыми — об этом предупреждаем при включении.
 *
 * Формат файла: magic "VMV2", затем поток [ChunkedAead].
 */
object VaultManager {
    private const val TAG = "VaultManager"
    private const val VAULT_DIR = "vault"
    private const val KEY_ALIAS = "voicemind_vault_v2"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())

    /** Ключ требует аутентификации пользователя (Android 11+). */
    val isAuthBound: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Расшифровать не удалось из-за аутентификации — надо заново пройти биометрию/PIN. */
    class NeedAuthException(cause: Throwable) : Exception(cause)

    /** Ключ уничтожен системой (сняли блокировку экрана) — данные не восстановить. */
    class KeyLostException(cause: Throwable) : Exception(cause)

    // ── key ───────────────────────────────────────────────────────────────────

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (isAuthBound) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(VaultSession.TTL_SEC,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    // Новый отпечаток не должен уничтожать все защищённые записи.
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private inline fun <T> keystoreCall(block: () -> T): T = try {
        block()
    } catch (e: UserNotAuthenticatedException) {
        VaultSession.lock()
        throw NeedAuthException(e)
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw KeyLostException(e)
    }

    // ── stream format ─────────────────────────────────────────────────────────

    /** Шифрует поток целиком. Бросает [NeedAuthException], если нужна аутентификация. */
    fun encryptStream(input: InputStream, output: OutputStream) = keystoreCall {
        output.write(MAGIC)
        ChunkedAead.encrypt(key(), input, output)
    }

    fun decryptStream(input: InputStream, output: OutputStream) = keystoreCall {
        val magic = ByteArray(4)
        DataInputStream(input).readFully(magic)
        require(magic.contentEquals(MAGIC)) { "not a vault file" }
        ChunkedAead.decrypt(key(), input, output)
    }

    // ── files ─────────────────────────────────────────────────────────────────

    private fun vaultDir(ctx: Context): File =
        File(ctx.filesDir, VAULT_DIR).apply { if (!exists()) mkdirs() }

    private fun textFile(ctx: Context, timestamp: Long) = File(vaultDir(ctx), "text_$timestamp.vm")
    fun audioFileFor(ctx: Context, timestamp: Long) = File(vaultDir(ctx), "audio_$timestamp.vm")

    /** Каталог расшифрованного на время сессии аудио; чистится при блокировке. */
    private fun playbackDir(ctx: Context) = File(ctx.cacheDir, "vault_play")

    private fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.outputStream().use(write)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("rename failed")
        } finally {
            tmp.delete()
        }
    }

    fun encryptText(ctx: Context, timestamp: Long, plaintext: String) {
        writeAtomically(textFile(ctx, timestamp)) { out ->
            encryptStream(plaintext.toByteArray(Charsets.UTF_8).inputStream(), out)
        }
    }

    /** null — файла нет. Бросает [NeedAuthException]/[KeyLostException]. */
    fun decryptText(ctx: Context, timestamp: Long): String? {
        val f = textFile(ctx, timestamp)
        if (!f.exists()) return null
        val out = java.io.ByteArrayOutputStream()
        f.inputStream().buffered().use { decryptStream(it, out) }
        return out.toString("UTF-8")
    }

    /** Шифрует аудио записи в vault; исходник удаляет только после успешной записи. */
    fun encryptAudio(ctx: Context, timestamp: Long, source: File): File {
        val target = audioFileFor(ctx, timestamp)
        writeAtomically(target) { out -> source.inputStream().buffered().use { encryptStream(it, out) } }
        return target
    }

    /** Расшифровывает аудио в [dest] (например, обратно в filesDir при снятии защиты). */
    fun decryptAudioTo(ctx: Context, timestamp: Long, dest: File) {
        writeAtomically(dest) { out ->
            audioFileFor(ctx, timestamp).inputStream().buffered().use { decryptStream(it, out) }
        }
    }

    /**
     * Временная расшифрованная копия аудио для плеера. Живёт в cacheDir до
     * [wipePlaybackCache] (вызывается при блокировке vault / уходе в фон).
     */
    fun playableAudio(ctx: Context, timestamp: Long, ext: String): File? {
        if (!audioFileFor(ctx, timestamp).exists()) return null
        val dir = playbackDir(ctx).apply { mkdirs() }
        val f = File(dir, "$timestamp.$ext")
        if (!f.exists()) decryptAudioTo(ctx, timestamp, f)
        return f
    }

    fun wipePlaybackCache(ctx: Context) {
        playbackDir(ctx).listFiles()?.forEach { it.delete() }
    }

    fun deleteEncrypted(ctx: Context, timestamp: Long): Boolean {
        playbackDir(ctx).listFiles { f -> f.name.startsWith("$timestamp.") }?.forEach { it.delete() }
        val a = audioFileFor(ctx, timestamp).let { !it.exists() || it.delete() }
        val t = textFile(ctx, timestamp).let { !it.exists() || it.delete() }
        return a && t
    }

    /** Таймстемпы записей, у которых есть файлы в vault (для чистки сирот). */
    fun storedTimestamps(ctx: Context): Map<Long, List<File>> {
        val dir = File(ctx.filesDir, VAULT_DIR)
        val files = dir.listFiles() ?: return emptyMap()
        val re = Regex("""^(text|audio)_(\d+)\.vm$|^enc_(\d+)\.dat$""")
        return files.mapNotNull { f ->
            val m = re.find(f.name) ?: return@mapNotNull null
            val ts = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toLongOrNull() ?: return@mapNotNull null
            ts to f
        }.groupBy({ it.first }, { it.second })
    }

    fun logFailure(what: String, e: Exception) {
        Log.e(TAG, "$what failed: ${e.javaClass.simpleName}")
    }
}
