package com.guzlik.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Тест скорости ИИ» и выгрузка журнала. Нужны, чтобы без компьютера (adb) понять,
 * насколько быстро модель работает на конкретном телефоне, и прислать результат.
 * Всё считается локально, наружу ничего не уходит: файл можно отправить только вручную через «Поделиться».
 */
object SpeedLab {
    private const val TAG = "SpeedLab"

    /** Тестовый текст для полного прогона «Выделить главное» (assets/speedlab_sample.txt). */
    private fun sample(ctx: Context): String =
        try { ctx.assets.open("speedlab_sample.txt").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }

    fun deviceHeader(ctx: Context): String {
        val sb = StringBuilder()
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        sb.appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Процессор: $soc, ядер: ${Runtime.getRuntime().availableProcessors()}, ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (am != null) {
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            sb.appendLine("Память: всего ${mi.totalMem / 1_000_000} МБ, свободно ${mi.availMem / 1_000_000} МБ")
        }
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val charging = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
            }
            sb.appendLine("Батарея: $level%, температура $temp °C, ${if (charging) "на зарядке" else "не на зарядке"}")
        }
        // Максимальные частоты ядер: показывают, какие из них «большие».
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
            try { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 } catch (_: Exception) { -1L }
        }
        sb.appendLine("Макс. частоты ядер, МГц: ${freqs.joinToString(" ")}")
        sb.appendLine("Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        return sb.toString()
    }

    /** Журнал приложения (logcat своего процесса: сюда попадают и сообщения нативных библиотек). */
    fun saveAppLog(ctx: Context): File? = try {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "guzlikvoice_log_${stamp()}.txt")
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "8000").redirectErrorStream(true).start()
        val body = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        f.writeText(deviceHeader(ctx) + "\n==== logcat ====\n" + body)
        f
    } catch (e: Exception) {
        Log.e(TAG, "saveAppLog failed: ${e.message}")
        null
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Отправить файл").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Кнопка «Сохранить журнал приложения»: собрать logcat и сразу предложить отправить. */
    fun shareAppLog(activity: Activity) {
        val ctx = activity.applicationContext
        Toast.makeText(ctx, "Собираю журнал…", Toast.LENGTH_SHORT).show()
        Thread {
            val f = saveAppLog(ctx)
            activity.runOnUiThread {
                if (f == null) Toast.makeText(ctx, "Не удалось собрать журнал", Toast.LENGTH_LONG).show()
                else share(activity, f)
            }
        }.start()
    }

    /**
     * Окно «Тест скорости»: берёт облегчённую Gemma (если скачана, иначе первую доступную), замеряет скорость и четыре раза
     * гонит полный «Выделить главное» на тестовом тексте: прежний способ выбора слова, новый, прежний, новый.
     * Рабочая модель на время теста выгружается.
     */
    fun showDialog(activity: Activity) {
        val ctx = activity.applicationContext
        // Сравниваем способы выбора слова на одной модели: на облегчённой, она будет в приложении.
        val available = LlmModels.SELECTABLE.filter { !it.reminderSpecialist && LlmModels.isDownloaded(ctx, it) }
        val models = listOfNotNull(available.firstOrNull { it.id == "gemma2-2b-v90k" } ?: available.firstOrNull())
        if (models.isEmpty()) {
            Toast.makeText(ctx, "Сначала скачайте модель ИИ (в списке выше)", Toast.LENGTH_LONG).show()
            return
        }
        val tv = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        val report = StringBuilder()
        fun emit(line: String) {
            Log.i(TAG, line)
            report.appendLine(line)
            activity.runOnUiThread { tv.text = report.toString(); scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) } }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Тест скорости ИИ")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("", null)
            .setNeutralButton("Поделиться", null)
            .setNegativeButton("Закрыть", null)
            .create()
        dialog.show()
        val btnClose = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val btnShare = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = android.view.View.GONE   // потоки больше не подбираем
        btnClose.isEnabled = false
        btnShare.isEnabled = false

        Thread {
            var out: File? = null
            try {
                (activity as? MainActivity)?.releaseLlmForLab()
                emit("Не закрывайте окно и не блокируйте экран: тест идёт около 10–12 минут.")
                emit("Телефон поставьте на зарядку (заряд лучше не ниже 50%) и дайте остыть.\n")
                emit(deviceHeader(ctx))
                val text = sample(ctx)
                val spec = models[0]
                // Порядок «прежний, новый, прежний, новый»: если телефон греется или садится батарея, это видно по разнице между кругами.
                val order = listOf(false, true, false, true)
                val rounds = mutableListOf<Pair<Boolean, LlmHelper.LabResult>>()
                var step = 0
                for (lazy in order) {
                    step++
                    emit("---- Круг $step из ${order.size}: выбор слова ${if (lazy) "НОВЫЙ" else "прежний"} ----")
                    val res = LlmHelper(ctx, spec).speedLab(text, micro = step == 1, lazyGrammar = lazy, emit = ::emit)
                    if (res != null) rounds += lazy to res
                    emit("")
                }
                emit("==== СВОДКА: ${spec.title} (полный «Выделить главное», потоки по умолчанию) ====")
                for ((i, r) in rounds.withIndex()) {
                    val (lazy, res) = r
                    emit("круг ${i + 1}: %-7s %.0f с, пунктов: %d".format(if (lazy) "НОВЫЙ" else "прежний", res.pipelineSec, res.pipelineItems))
                }
                val olds = rounds.filter { !it.first }.map { it.second }
                val news = rounds.filter { it.first }.map { it.second }
                if (olds.isNotEmpty() && news.isNotEmpty()) {
                    val o = olds.map { it.pipelineSec }.average(); val n = news.map { it.pipelineSec }.average()
                    emit("Среднее: прежний %.0f с, новый %.0f с (%+.0f%%)".format(o, n, (n - o) / o * 100))
                    val same = olds.all { it.text == news[0].text } && news.all { it.text == olds[0].text }
                    emit(if (same) "Результат во всех кругах ОДИНАКОВЫЙ: качество не меняется." else "Результат в кругах РАЗНЫЙ: сравните строки «результат:» выше.")
                }
                emit("Скорость чтения запроса и ответа (ток/с) от длины ответа не зависит, поэтому сравнивайте и её: строки «[...] запрос ... ответ ...» выше.")
                emit("\nГотово. Нажмите «Поделиться» и пришлите файл.")
                out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "speedlab_${stamp()}.txt")
                out.writeText(report.toString())
            } catch (e: Throwable) {
                emit("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}")
                out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "speedlab_${stamp()}_error.txt")
                try { out.writeText(report.toString()) } catch (_: Exception) {}
            }
            activity.runOnUiThread {
                btnClose.isEnabled = true
                val f = out
                if (f != null) {
                    btnShare.isEnabled = true
                    btnShare.setOnClickListener {
                        // Один файл: результаты теста + журнал приложения в конце.
                        Thread {
                            val log = saveAppLog(ctx)
                            val merged = File(f.parentFile, f.nameWithoutExtension + "_with_log.txt")
                            merged.writeText(f.readText() + "\n\n" + (log?.readText() ?: "(журнал недоступен)"))
                            activity.runOnUiThread { share(activity, merged) }
                        }.start()
                    }
                }
            }
        }.start()
    }
}
