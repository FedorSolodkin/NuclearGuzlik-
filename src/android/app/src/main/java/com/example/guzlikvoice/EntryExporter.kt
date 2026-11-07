package com.example.guzlikvoice

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт записи в PDF / Word / SRT из карточки записи (раньше — 200 строк
 * внутри TranscriptionDetailSheet, PDF и Word почти дословно повторяли друг друга).
 */
class EntryExporter(private val fragment: Fragment) {
    companion object {
        private const val TAG = "EntryExporter"
        private const val EXPORTS_DIR = "exports"
        private const val MAX_AGE_MS = 24L * 3600 * 1000

        private const val MIME_PDF = "application/pdf"
        private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MIME_SRT = "application/x-subrip"

        fun exportsDir(ctx: Context) = File(ctx.cacheDir, EXPORTS_DIR).apply { mkdirs() }

        private fun newExportFile(ctx:Context,name:String):File =
            File(File(exportsDir(ctx),java.util.UUID.randomUUID().toString()).apply { check(mkdirs()) },name)

        /**
         * Файлы экспорта — открытый текст записей. Через FileProvider они живут,
         * пока их не заберёт приложение, которому поделились; дальше не нужны.
         */
        fun pruneOldExports(ctx: Context) {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            File(ctx.cacheDir, EXPORTS_DIR).listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        }
    }

    enum class Doc { PDF, DOCX }

    private val appCtx: Context get() = fragment.requireContext().applicationContext

    fun exportDocument(kind: Doc, date: String, timestamp: Long, transcript: String) {
        val ctx = appCtx
        if (transcript.isBlank()) { toast("Пустая запись"); return }
        val label = if (kind == Doc.PDF) "PDF" else "Word"
        toast("Готовлю $label...")
        run("$label export") {
            val entry = RecordStorage.getEntry(ctx, date, timestamp)
            val tpl = LlmHelper.Template.fromId(entry?.templateId)
            val subtitle = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(Date(timestamp))
            val fileName = if (kind == Doc.PDF) PdfExporter.defaultFilename("guzlikvoice", timestamp)
                           else DocxExporter.defaultFilename("guzlikvoice", timestamp)
            val outFile = newExportFile(ctx, fileName)
            if (kind == Doc.PDF) {
                PdfExporter.export(outFile = outFile, title = "GuzlikVoice — запись", subtitle = subtitle,
                    transcript = transcript, templateTitle = tpl?.displayName, templateBody = entry?.templateResult)
            } else {
                DocxExporter.export(outFile = outFile, title = "GuzlikVoice — запись", subtitle = subtitle,
                    transcript = transcript, templateTitle = tpl?.displayName, templateBody = entry?.templateResult)
            }
            Ready(outFile, if (kind == Doc.PDF) MIME_PDF else MIME_DOCX, fileName, label)
        }
    }

    /**
     * Субтитры. Берём фразы с таймкодами, сохранённые при записи; распознаём
     * заново только старые записи без них.
     */
    fun exportSrt(audioFile: File, timestamp: Long, storedSegments: List<WhisperHelper.Segment>?) {
        val ctx = appCtx
        val whisper = (fragment.activity as? MainActivity)?.whisperHelper
        if (storedSegments.isNullOrEmpty() && (whisper == null || !whisper.isReady)) {
            toast("Сначала откройте вкладку «Запись»: распознавание ещё запускается")
            return
        }
        toast("Готовлю SRT...")
        run("SRT export") {
            val segments = storedSegments?.takeIf { it.isNotEmpty() } ?: run {
                val jobContext=kotlinx.coroutines.currentCoroutineContext()
                StoredAudioTranscriber.transcribe(ctx,audioFile,whisper!!) { !jobContext.isActive }.also { generated ->
                    if(generated.isNotEmpty() && RecordStorage.findEntryByTimestamp(ctx,timestamp)?.second?.audioPath==audioFile.absolutePath)
                        RecordStorage.updateEntrySegments(ctx,timestamp,generated)
                }
            }
            if (segments.isNullOrEmpty()) return@run null
            val fileName = "guzlikvoice_${timestamp}.srt"
            val file = newExportFile(ctx, fileName).apply { writeText(SrtExporter.toSrt(segments)) }
            Ready(file, MIME_SRT, fileName, "SRT")
        }
    }

    fun exportJson(timestamp: Long, transcript: String, segments: List<WhisperHelper.Segment>?) {
        val ctx = appCtx
        run("JSON export") {
            val labels = SpeakerCentroidStorage.get(ctx, timestamp)?.labels.orEmpty()
            val json = JsonTranscriptExporter.export(transcript, segments.orEmpty(), labels)
            val name = ExportFileNames.forRecord("guzlikvoice", timestamp, "json")
            val file = newExportFile(ctx, name).apply { writeText(json, Charsets.UTF_8) }
            Ready(file, "application/json", name, "JSON")
        }
    }

    /** Исходный звук записи: копия во временную папку экспорта, дальше как у остальных форматов (Загрузки и «Поделиться»). */
    fun exportAudio(audioFile: File, timestamp: Long) {
        val ctx = appCtx
        toast("Готовлю аудио...")
        run("audio export") {
            val ext = audioFile.extension.ifBlank { "m4a" }.lowercase(Locale.US)
            val mime = when (ext) { "m4a", "mp4", "aac" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/*" }
            val fileName = ExportFileNames.forRecord("guzlikvoice",timestamp,ext)
            val out = newExportFile(ctx, fileName)
            audioFile.copyTo(out, overwrite = true)
            Ready(out, mime, fileName, "Аудио")
        }
    }

    private data class Ready(val file: File, val mime: String, val subject: String, val kind: String)

    /** Фоновая работа на уровне активности (закрытие карточки экспорт не прерывает). */
    private fun run(what: String, block: suspend () -> Ready?) {
        val ctx = appCtx
        val activity = fragment.requireActivity()
        val lease=UserDataJobs.begin() ?: return
        val token=try{RecordingService.processingStarted(ctx)}catch(e:Exception){lease.close();toast(ErrorMessages.humanize(e));return}
        val job=activity.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    lease.check()
                    block()?.let { lease.check();it to (DownloadsExporter.saveToDownloads(ctx, it.file, it.mime) != null) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$what failed: ${e.javaClass.simpleName}", e)
                Toast.makeText(ctx, ErrorMessages.humanize(e), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (result == null) { Toast.makeText(ctx, "Речь не распознана", Toast.LENGTH_SHORT).show(); return@launch }
            onReady(result.first, result.second, ctx, activity)
        }
        lease.onCancel { job.cancel() }
        job.invokeOnCompletion { try{RecordingService.processingFinished(ctx,token)}finally{lease.close()} }
    }

    // Snackbar после экспорта: файл уже в Загрузках, «Поделиться» открывает chooser.
    private fun onReady(r: Ready, saved: Boolean, ctx: Context, activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val cacheUri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", r.file)
        val msg = if (saved) "${r.kind} сохранён в Загрузки" else "${r.kind} готов к отправке"
        val root: View = fragment.view ?: activity.findViewById(android.R.id.content)
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
            .setAction("Поделиться") {
                val intent = DownloadsExporter.buildShareIntent(cacheUri, r.mime, r.subject)
                if (!activity.isFinishing && !activity.isDestroyed)
                    activity.startActivity(Intent.createChooser(intent, "Поделиться ${r.kind}"))
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
}
