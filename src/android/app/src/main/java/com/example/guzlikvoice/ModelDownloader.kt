package com.example.guzlikvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Синглтон-загрузчик Whisper-моделей.
 *
 * Живёт в процессе приложения, не привязан к жизненному циклу фрагментов /
 * BottomSheet'ов — закрытие настроек или свайп таба не отменяет загрузку.
 *
 * NB: если пользователь свернёт приложение, а ОС убьёт процесс (Xiaomi любит),
 * загрузка всё равно оборвётся. Для полной защиты нужен foreground service.
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private val main = Handler(Looper.getMainLooper())

    /** Слушатель прогресса; отписаться через [removeListener]. */
    interface Listener {
        fun onProgress(modelName: String, pct: Int, downloadedMb: Long, totalMb: Long)
        fun onFinished(modelName: String, success: Boolean)
    }

    /** Что сейчас качается (для восстановления UI при переоткрытии settings). */
    @Volatile var currentModel: String? = null
        private set

    /** Кэш последнего прогресса — чтобы sheet, открытая позже, могла сразу показать %. */
    @Volatile var lastPct: Int = 0
        private set
    @Volatile var lastDlMb: Long = 0
        private set
    @Volatile var lastTotalMb: Long = 0
        private set

    @Volatile private var cancelled = false

    private val listeners = mutableListOf<Listener>()
    private val listenersLock = Any()

    fun isDownloading(): Boolean = currentModel != null

    fun addListener(l: Listener) = synchronized(listenersLock) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: Listener) = synchronized(listenersLock) {
        listeners.remove(l)
    }

    fun cancel() { cancelled = true }

    /**
     * Стартует загрузку. Возвращает false, если уже идёт другая закачка.
     * Прогресс/финиш прилетают в [Listener] на UI-потоке.
     */
    fun start(appCtx: Context, modelName: String, urls: List<String>, minSize: Long): Boolean {
        synchronized(this) {
            if (currentModel != null) return false
            currentModel = modelName
            cancelled = false
            lastPct = 0; lastDlMb = 0; lastTotalMb = 0
        }

        Thread {
            val dest = File(appCtx.filesDir, modelName)
            val tmp  = File(appCtx.filesDir, "$modelName.tmp")
            var ok = false
            try {
                // Источники по очереди: наше зеркало, затем оригинал ([ModelMirror]).
                // .tmp не удаляем при обрыве — следующая попытка продолжит с того же байта;
                // файлы в источниках одинаковые (сверяются по одной сумме).
                for (url in urls) {
                    if (cancelled) break
                    ok = try {
                        download(url, tmp, minSize) { pct, dlMb, totalMb ->
                            lastPct = pct; lastDlMb = dlMb; lastTotalMb = totalMb
                            emitProgress(modelName, pct, dlMb, totalMb)
                        }
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "download from $url failed: ${e.message}"); false
                    }
                    if (ok && !ModelIntegrity.verify(tmp, modelName, url)) {
                        tmp.delete()   // битый файл — докачивать нечего, только заново
                        ok = false
                    }
                    if (ok) break
                    Log.w(TAG, "$modelName: source failed, trying next")
                }
                if (ok) ok = tmp.renameTo(dest)
            } catch (e: Exception) {
                Log.e(TAG, "download threw", e)
                ok = false
            }
            synchronized(this) { currentModel = null }
            emitFinished(modelName, ok)
        }.start()
        return true
    }

    private fun snapshotListeners(): List<Listener> = synchronized(listenersLock) { listeners.toList() }

    private fun emitProgress(model: String, pct: Int, dl: Long, tot: Long) {
        val snap = snapshotListeners()
        main.post { snap.forEach { it.onProgress(model, pct, dl, tot) } }
    }

    private fun emitFinished(model: String, ok: Boolean) {
        val snap = snapshotListeners()
        main.post { snap.forEach { it.onFinished(model, ok) } }
    }

    // Разрешённые хосты для скачивания моделей. HuggingFace редиректит на CDN
    // (cdn-lfs, cas-*.hf.co); все они должны быть в этом списке. Не пускаем
    // редирект на произвольный домен — иначе злонамеренный ответ мог бы увести
    // клиента на http-хост под контролем атакующего (даже если network-security-config
    // это заблокирует, лучше отказать раньше).
    private val ALLOWED_HOSTS = listOf(
        "huggingface.co", "hf.co",
        "cdn-lfs.huggingface.co", "cdn-lfs-us-1.huggingface.co",
        "cdn-lfs-eu-1.huggingface.co", "cdn-lfs.hf.co",
        "cas-bridge.xethub.hf.co",
        "alphacephei.com",
        // Наше зеркало — GitHub Releases, отдаёт редиректом на release-assets/objects.githubusercontent.com.
        "github.com", "githubusercontent.com",
    )

    private fun isHostAllowed(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val host = url.host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun download(
        startUrl: String,
        dest: File,
        minSize: Long,
        onProgress: (Int, Long, Long) -> Unit,
    ): Boolean {
        var urlStr = startUrl
        for (i in 0 until 10) {
            if (cancelled) return false
            val url = try { URL(urlStr) } catch (_: Exception) { return false }
            if (!isHostAllowed(url)) {
                Log.e(TAG, "redirect to non-allowlisted host blocked: ${url.host}")
                return false
            }
            val existing = if (dest.exists()) dest.length() else 0L
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    urlStr = conn.getHeaderField("Location") ?: return false
                    continue
                }
                if (code == 416) return dest.length() > minSize   // уже докачан целиком
                if (code != 200 && code != 206) return false
                val resuming = code == 206
                val total = if (resuming)
                    conn.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull()
                        ?: (existing + conn.contentLengthLong)
                else conn.contentLengthLong
                var downloaded = if (resuming) existing else 0L
                conn.inputStream.use { inp ->
                    java.io.FileOutputStream(dest, resuming).use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            if (cancelled) return false
                            out.write(buf, 0, n); downloaded += n
                            if (total > 0) onProgress(
                                (downloaded * 100 / total).toInt(),
                                downloaded / 1_000_000,
                                total / 1_000_000
                            )
                        }
                    }
                }
                return dest.length() > minSize
            } finally {
                conn.disconnect()
            }
        }
        return false
    }
}
