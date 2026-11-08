package com.guzlik.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.UUID

/**
 * Автосохранение JSON в «Загрузки» сразу после обработки записи, без нажатия кнопки и без перехода на другие экраны.
 * Используется строгий экспорт, а при его отказе запасные режимы ([JsonTranscriptExporter.exportBestEffort]):
 * пользователь не остаётся без файла из-за одной неудачной реплики.
 */
object AutoExport {
    private const val TAG = "AutoExport"

    /** Вызывать не из главного потока. Не бросает исключений; true, если файл сохранён в «Загрузки». */
    fun saveJson(ctx: Context, timestamp: Long): Boolean = try {
        val appCtx = ctx.applicationContext
        val entry = RecordStorage.findEntryByTimestamp(appCtx, timestamp)?.second
        if (entry == null || entry.isEncrypted || entry.text.isBlank()) false
        else {
            val labels = SpeakerCentroidStorage.get(appCtx, timestamp)?.labels.orEmpty()
            val outcome = JsonTranscriptExporter.exportBestEffort(entry.text, entry.segments, labels)
            if (outcome.mode != JsonTranscriptExporter.Mode.STRICT)
                Log.w(TAG, "strict export failed (${outcome.reason}); saved as ${outcome.mode}")
            val name = ExportFileNames.forRecord("guzlikvoice", timestamp, "json")
            val dir = File(EntryExporter.exportsDir(appCtx), UUID.randomUUID().toString()).apply { mkdirs() }
            val file = File(dir, name).apply { writeText(outcome.json, Charsets.UTF_8) }
            val saved = DownloadsExporter.saveToDownloads(appCtx, file, "application/json") != null
            if (saved) toast(appCtx, if (outcome.mode == JsonTranscriptExporter.Mode.STRICT) "JSON сохранён в Загрузки"
                else "JSON сохранён в Загрузки (упрощённо)")
            saved
        }
    } catch (e: Throwable) {
        Log.w(TAG, "auto export failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun toast(ctx: Context, msg: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}
