package com.guzlik.voice

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Импорт готовых аудиофайлов: голосовые из Telegram/WhatsApp, записи диктофона,
 * mp3/m4a/ogg/wav. Файл копируется в хранилище приложения (чтобы его можно было
 * слушать из истории), декодируется в 16 кГц моно и проходит тот же путь, что
 * запись с микрофона: [TranscriptionSession] (VAD, глоссарий, разбиение по голосам).
 *
 * Файлы обрабатываются по одному, в фоне; RecordingService держит процесс живым,
 * по готовности — уведомление с переходом к записи.
 */
object AudioImporter {
    private const val TAG = "AudioImporter"
    private const val ENGINE_WAIT_MS = 120_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Копирует файл по [uri] к себе (синхронно — вызывать не из UI-потока) и ставит в очередь. */
    fun importUri(ctx: Context, uri: Uri): Boolean {
        val appCtx = ctx.applicationContext
        if (!uri.scheme.equals("content", ignoreCase = true)) {
            Log.w(TAG, "rejected non-content uri scheme: ${uri.scheme}")
            toast(appCtx, "Не удалось прочитать файл")
            return false
        }
        val lease = UserDataJobs.begin() ?: return false
        lease.use {
            val local = copyToLocal(appCtx, uri, lease)
            if (local == null) { toast(appCtx, "Не удалось прочитать файл"); return false }
            if (lease.cancelled) { local.delete(); return false }
            enqueueFile(appCtx, local)
            return true
        }
    }

    /** Ставит уже скопированный к себе файл в очередь на расшифровку. */
    fun enqueueFile(ctx: Context, local: File) {
        val appCtx = ctx.applicationContext
        val lease = UserDataJobs.begin() ?: run { local.delete(); return }
        toast(appCtx, "Файл принят — расшифровка в фоне, пришлём уведомление")
        val processingToken = try { RecordingService.processingStarted(appCtx) }
        catch (e: Exception) {
            lease.close()
            local.delete()
            Log.w(TAG,"Could not start import service",e)
            toast(appCtx,"Не удалось начать импорт: ${ErrorMessages.humanize(e)}")
            return
        }
        WhisperHelper.holdForImport()
        executor.execute {
            try {
                process(appCtx, local, lease)
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                toast(appCtx, "Импорт не удался: ${ErrorMessages.humanize(e)}")
            } finally {
                try { WhisperHelper.unholdForImport() }
                finally {
                    try { RecordingService.processingFinished(appCtx, processingToken) }
                    finally { lease.close() }
                }
            }
        }
    }

    private fun copyToLocal(ctx: Context, uri: Uri, lease: UserDataJobs.Lease): File? {
        val name = displayName(ctx, uri)
        val ext = ImportFileNames.safeExtension(name)
        val dest = File(ctx.filesDir, "import_${System.currentTimeMillis()}.$ext")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        lease.check()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            if (dest.length() == 0L) { dest.delete(); null } else dest
        } catch (e: Exception) {
            Log.e(TAG, "copy failed: ${e.message}")
            dest.delete()
            null
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null } ?: uri.lastPathSegment

    private fun process(ctx: Context, file: File, lease: UserDataJobs.Lease) {
        lease.check()
        val whisper = waitForEngine(lease)?.takeIf { it.retain() }
        if (whisper == null) {
            toast(ctx, "Распознавание ещё не готово — откройте приложение и попробуйте ещё раз")
            file.delete()
            return
        }

        // Голоса при импорте не делим: это отдельная кнопка «Разделить по голосам» в записи.
        // try/finally: движок отпускаем на любом выходе (см. WhisperHelper.retain).
        try {
            val glossary = Glossary.buildPrompt(ctx)
            val session = TranscriptionSession(
                whisper = whisper,
                embedder = null,
                context = ctx,
                glossaryPrompt = glossary,
                onChunkReady = { _, _ -> },
                onProcessingChanged = { },
                persistWal = false,
            )
            lease.onCancel { session.cancel() }
            try {
            lease.check()
            // Декодируем потоково и подаём кадрами как с микрофона — сессия сама нарежет куски по паузам.
            // Весь файл в памяти не собираем: раньше часовой mp3 падал с OutOfMemoryError (~245 МБ одним массивом).
            val samples = AudioDecoder.decodeStreaming(file) { frame ->
                // Не больше двух кусков (~2 минуты звука) в очереди — память не зависит от длины файла.
                while (session.pendingChunks() >= 2 && !session.isCancelled) {
                    lease.check()
                    Thread.sleep(50)
                }
                lease.check()
                session.pushAudio(frame)
            }
            if (samples < 16_000 / 2) {
                session.cancel()
                session.release()
                toast(ctx, "Не удалось распознать формат файла")
                file.delete()
                return
            }
            Log.i(TAG, "Importing ${file.name}: ${samples / 16_000} s")
            var saved = 0L
            session.flushAndWait(onDone = { text, segments, diarization ->
                lease.check()
                if (text.isBlank() && session.failedChunks == 0) return@flushAndWait
                // Часть кусков не распознана (движок пропал, процесс прерывали) — говорим об этом в самой записи,
                // а не сохраняем обрывок под видом полной расшифровки.
                val warn = if (session.failedChunks > 0)
                    "⚠ Расшифровка неполная: часть файла не распознана. Расшифруйте файл ещё раз.\n\n" else ""
                val cleaned = warn + TextFormatter.format(text)
                saved = RecordStorage.addToToday(ctx, cleaned, "import", null, file.absolutePath,
                    linkPendingEvent = false)
                if (segments.isNotEmpty()) RecordStorage.updateEntrySegments(ctx, saved, segments)
                SpeakerCentroidStorage.putFromResult(ctx, saved, diarization)
            })
            session.release()
            if (saved != 0L && session.failedChunks > 0) {
                ProcessingNotifier.notifyReady(ctx, saved, "Расшифровка неполная — часть файла не распознана")
            } else if (saved != 0L) {
                ProcessingNotifier.notifyReady(ctx, saved, "Файл расшифрован")
            } else {
                toast(ctx, "В файле не найдено речи")
                file.delete()
            }
            } finally {
                session.cancel()
                if (lease.cancelled) file.delete()
                session.release()
                session.awaitStopped()
            }
        } finally {
            whisper.releaseUse()
        }
    }

    /** Ждёт, пока экран записи поднимет движок (например, приложение только что запущено шарингом). */
    private fun waitForEngine(lease: UserDataJobs.Lease): WhisperHelper? {
        val deadline = System.currentTimeMillis() + ENGINE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            lease.check()
            val w = WhisperHelper.active
            if (w != null && w.isReady) return w
            Thread.sleep(500)
        }
        return null
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }
}
