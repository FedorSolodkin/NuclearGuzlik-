package com.guzlik.voice

import android.content.Context
import java.io.File

/**
 * Модели, положенные в APK при сборке (-PguzlikvoiceBundle=<папка>, внутри подпапка models/).
 * whisper.cpp и llama.cpp читают обычные файлы, поэтому при первом запуске файлы копируются
 * из APK в папку приложения под теми же именами, под которыми их ждёт остальной код.
 * Интернет не нужен. Если в APK ничего не положено, объект ничего не делает.
 */
object BundledModels {
    private const val DIR = "models"
    private const val BUFFER = 1 shl 20

    sealed class Result {
        object Ok : Result()
        data class NoSpace(val missingBytes: Long) : Result()
        data class Failed(val message: String) : Result()
    }

    fun assetNames(ctx: Context): List<String> =
        try { ctx.assets.list(DIR)?.toList().orEmpty() } catch (_: Exception) { emptyList() }

    /** Размер файла в APK; -1, если он сжат и размер без чтения неизвестен. */
    private fun assetSize(ctx: Context, name: String): Long =
        try { ctx.assets.openFd("$DIR/$name").use { it.length } } catch (_: Exception) { -1L }

    private fun isInstalled(ctx: Context, name: String): Boolean {
        val f = File(ctx.filesDir, name)
        val size = assetSize(ctx, name)
        return f.exists() && f.length() > 0 && (size < 0 || f.length() == size)
    }

    /** Имена встроенных файлов, которых ещё нет в папке приложения (или они неполные). */
    fun pending(ctx: Context): List<String> = assetNames(ctx).filterNot { isInstalled(ctx, it) }

    /**
     * Копирует недостающие файлы. [onProgress] получает общий прогресс (байты) и имя текущего файла.
     * Вызовы из нескольких потоков выстраиваются в очередь: второй увидит, что всё уже скопировано.
     */
    @Synchronized
    fun install(ctx: Context, onProgress: (done: Long, total: Long, name: String) -> Unit): Result {
        if (BuildConfig.CONTEST_BUILD) {
            val expected = setOf(GigaAmEngine.BUNDLED_MODEL_FILE, GigaAmEngine.BUNDLED_TOKENS_FILE, LlmModels.GEMMA_TRIM.fileName,
                SpeakerEmbedder.MODEL_NAME, PyannoteSegmenter.MODEL_NAME, SileroVAD.MODEL_NAME)
            if (assetNames(ctx).toSet() != expected)
                return Result.Failed("В APK нет полного набора моделей. Установите полную конкурсную APK")
        }
        val names = pending(ctx)
        if (names.isEmpty()) return Result.Ok
        val sizes = names.associateWith { assetSize(ctx, it) }
        val total = sizes.values.sumOf { if (it > 0) it else 0L }
        val free = ctx.filesDir.usableSpace
        // Запас 100 МБ на временные файлы системы.
        if (total > 0 && free < total + 100L * 1024 * 1024) return Result.NoSpace(total + 100L * 1024 * 1024 - free)
        var done = 0L
        var lastReport = 0L
        for (name in names) {
            val target = File(ctx.filesDir, name)
            val tmp = File(ctx.filesDir, "$name.part")
            try {
                tmp.delete()
                ctx.assets.open("$DIR/$name").use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 200) { lastReport = now; onProgress(done, total, name) }
                        }
                    }
                }
                val want = sizes.getValue(name)
                if (want > 0 && tmp.length() != want) {
                    tmp.delete()
                    return Result.Failed("файл $name скопирован не полностью")
                }
                target.delete()
                if (!tmp.renameTo(target)) return Result.Failed("не удалось сохранить $name")
            } catch (e: Exception) {
                tmp.delete()
                return Result.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        onProgress(total, total, "")
        return Result.Ok
    }
}
