package com.guzlik.voice

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Падения и отчёт «Сообщить о проблеме» — без облака и сторонних SDK.
 *
 * Падение пишется в filesDir/crash/ (последние [KEEP_CRASHES]). Отчёт — текстовый
 * файл в cacheDir/exports/ (его отдаёт FileProvider): сведения о телефоне и
 * сборке, последние падения и журнал этого процесса. Записей и текста
 * расшифровок в журнале нет: приложение пишет туда только служебное (размеры,
 * язык, время). Пользователь сам выбирает, куда отправить файл.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val DIR = "crash"
    private const val KEEP_CRASHES = 5
    private const val LOG_LINES = 3000
    private const val PREFS = "crash_reporter"
    private const val KEY_SEEN = "last_seen_crash"

    /** Вызывать первым делом в Application.onCreate. */
    fun install(app: Context) {
        val appCtx = app.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { writeCrash(appCtx, thread, e) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    private fun crashDir(ctx: Context) = File(ctx.filesDir, DIR)

    private fun crashFiles(ctx: Context): List<File> =
        crashDir(ctx).listFiles { f -> f.name.startsWith("crash_") }?.sortedByDescending { it.name }.orEmpty()

    private fun writeCrash(ctx: Context, thread: Thread, e: Throwable) {
        val dir = crashDir(ctx).apply { mkdirs() }
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        File(dir, "crash_${System.currentTimeMillis()}.txt")
            .writeText("${stamp(System.currentTimeMillis())}  поток «${thread.name}»\n$sw")
        crashFiles(ctx).drop(KEEP_CRASHES).forEach { it.delete() }
    }

    /** Падение, о котором пользователя ещё не спрашивали (после перезапуска), или null. */
    fun unseenCrash(ctx: Context): File? {
        val newest = crashFiles(ctx).firstOrNull() ?: return null
        val seen = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, null)
        return newest.takeIf { it.name != seen }
    }

    fun markSeen(ctx: Context) {
        val newest = crashFiles(ctx).firstOrNull() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, newest.name).apply()
    }

    /** Собирает отчёт (блокирующе — звать не из UI-потока). */
    fun buildReport(ctx: Context): File {
        val sb = StringBuilder()
        sb.appendLine("GuzlikVoice — отчёт о проблеме, ${stamp(System.currentTimeMillis())}")
        sb.appendLine()
        sb.appendLine("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE}, ${ctx.packageName}")
        sb.appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Процессор: ${Build.SUPPORTED_ABIS.joinToString()}, ${Build.HARDWARE}")
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            sb.appendLine("Память: всего ${mi.totalMem / 1_000_000} МБ, свободно ${mi.availMem / 1_000_000} МБ")
        }
        runCatching {
            val st = StatFs(ctx.filesDir.absolutePath)
            sb.appendLine("Место: свободно ${st.availableBytes / 1_000_000} МБ")
        }
        runCatching {
            sb.appendLine("Распознавание: ${AppPrefs.getWhisperModel(ctx)}, ИИ-анализ: ${LlmModels.selected(ctx).id}" +
                " (скачана: ${LlmModels.isDownloaded(ctx, LlmModels.selected(ctx))})")
        }

        val crashes = crashFiles(ctx)
        sb.appendLine()
        sb.appendLine("── Падения (${crashes.size}) ──")
        crashes.forEach { sb.appendLine(it.readText().take(20_000)); sb.appendLine() }

        sb.appendLine("── Журнал процесса ──")
        sb.appendLine(readOwnLog())

        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("guzlikvoice_report_") }?.forEach { it.delete() }
        val stampName = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        return File(dir, "guzlikvoice_report_$stampName.txt").apply { writeText(sb.toString()) }
    }

    /** Собирает отчёт в фоне и открывает «Поделиться». */
    fun sendReport(activity: FragmentActivity) {
        val appCtx = activity.applicationContext
        Toast.makeText(activity, "Собираю отчёт…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching { buildReport(appCtx) }.getOrNull() }
            if (report == null) {
                Toast.makeText(activity, "Не удалось собрать отчёт", Toast.LENGTH_LONG).show()
                return@launch
            }
            markSeen(appCtx)
            share(activity, report)
        }
    }

    /** После перезапуска: если в прошлый раз приложение упало — предложить отправить отчёт. */
    fun offerAfterCrash(activity: FragmentActivity) {
        unseenCrash(activity) ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle("GuzlikVoice закрылся с ошибкой")
            .setMessage("В прошлый раз приложение неожиданно закрылось. Отправите нам отчёт? " +
                "В нём только сведения о телефоне и технический журнал — без записей и текста.")
            .setPositiveButton("Отправить") { _, _ -> sendReport(activity) }
            .setNegativeButton("Не сейчас") { _, _ -> markSeen(activity) }
            .setOnCancelListener { markSeen(activity) }
            .show()
    }

    /** Открывает «Поделиться» с файлом отчёта. */
    fun share(activity: Activity, report: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", report)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "GuzlikVoice: отчёт о проблеме")
            .putExtra(Intent.EXTRA_TEXT, "Отчёт о проблеме в GuzlikVoice. Опишите, что делали перед ошибкой:")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, "Отправить отчёт"))
    }

    /** Последние строки журнала своего процесса (своё приложение читает без разрешений). */
    private fun readOwnLog(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString(),
            "--pid", Process.myPid().toString())
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
        text.ifBlank { "(журнал пуст)" }
    } catch (e: Exception) {
        Log.w(TAG, "logcat failed: ${e.message}")
        "(журнал недоступен: ${e.javaClass.simpleName})"
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
