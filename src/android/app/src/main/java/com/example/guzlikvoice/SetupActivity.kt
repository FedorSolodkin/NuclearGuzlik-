package com.example.guzlikvoice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class SetupActivity : AppCompatActivity() {

    companion object {
        // Ставится при переходе сюда с экрана записи, когда человек пропустил
        // ИИ-анализ на онбординге, а потом нажал "Выделить главное" — сразу
        // начинаем скачивание, без повторного экрана-предложения.
        const val EXTRA_AUTO_LLM = "auto_llm"

        // Ставится при переходе из настроек, когда выбрана "Быстрее" —
        // соответствующая модель ещё не скачана, сразу начинаем скачивание.
        const val EXTRA_AUTO_TINY  = "auto_tiny"

        // "Точная" модель: Whisper-large-v3-turbo, дообученный Иваном Юрьевичем
        // (bond005) на подкаст-корпусе podlodka_speech, GGML q4_0 от JoaoZaokk.
        // Раньше здесь качался q5_0, а движок и настройки ждали q4_0 — после
        // докачки из настроек на диске оказывались обе версии (~1 ГБ).
        private const val WHISPER_NAME     = WhisperCppEngine.PODLODKA_TURBO_FILE
        private val WHISPER_URLS = ModelMirror.urls(WHISPER_NAME, WhisperCppEngine.PODLODKA_TURBO_URL)
        private const val WHISPER_MIN_SIZE = WhisperCppEngine.PODLODKA_TURBO_MIN_BYTES  // ~474 МБ

        // Облегчённая модель («Быстрее») — whisper-small q5_1. Скачивается только
        // по выбору в настройках / на слабых устройствах.
        private const val TINY_NAME     = WhisperCppEngine.SMALL_FILE
        private val TINY_URLS = ModelMirror.urls(TINY_NAME, WhisperCppEngine.SMALL_URL)
        private const val TINY_MIN_SIZE = WhisperCppEngine.SMALL_MIN_BYTES

        private const val SPEAKER_NAME     = "speaker_model.onnx"
        private val SPEAKER_URLS = ModelMirror.urls(ModelMirror.SPEAKER_ASSET,
            "https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM/resolve/main/onnx/model.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recog-models/wespeaker-voxceleb-resnet34-LM.onnx"
        )
        private const val SPEAKER_MIN_SIZE = 20_000_000L  // ~26 MB

        private const val VAD_NAME     = "silero_vad.onnx"
        private val VAD_URLS = ModelMirror.urls(ModelMirror.VAD_ASSET,
            "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx")
        private const val VAD_MIN_SIZE = 1_000_000L  // ~1.8 MB

        // pyannote/segmentation-3.0 — «Level 4» диаризация. onnx-community — не gated.
        private const val PYANNOTE_NAME     = "pyannote_segmentation.onnx"
        private val PYANNOTE_URLS = ModelMirror.urls(ModelMirror.PYANNOTE_ASSET,
            "https://huggingface.co/onnx-community/pyannote-segmentation-3.0/resolve/main/onnx/model.onnx",
            "https://github.com/pengzhendong/pyannote-onnx/releases/download/v0.1.0/segmentation-3.0.onnx"
        )
        private const val PYANNOTE_MIN_SIZE = 4_000_000L  // ~5.7 MB
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus:    TextView
    private lateinit var tvProgress:  TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry:    Button
    private lateinit var btnSkip:     Button

    @Volatile private var downloadCancelled = false
    private var lastProgressTime = 0L
    private var lastDownloadedBytes = 0L
    private var autoDownloadLlm   = false
    private var autoDownloadTiny  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.OFFLINE_PROTOTYPE) { proceed(); return }
        autoDownloadLlm   = intent.getBooleanExtra(EXTRA_AUTO_LLM, false)
        autoDownloadTiny  = intent.getBooleanExtra(EXTRA_AUTO_TINY, false)
        setContentView(R.layout.activity_setup)
        tvStatus    = findViewById(R.id.tvSetupStatus)
        tvProgress  = findViewById(R.id.tvSetupProgress)
        progressBar = findViewById(R.id.setupProgressBar)
        btnRetry    = findViewById(R.id.btnRetry)
        btnSkip     = findViewById(R.id.btnSkip)

        btnSkip.visibility = View.GONE
        btnRetry.visibility = View.GONE

        if (BuildConfig.CONTEST_BUILD) {
            autoDownloadTiny = false
            autoDownloadLlm = false
            installBundled()
            return
        }

        // Модели лежат внутри APK: копируем их в папку приложения, без интернета.
        if (BundledModels.pending(this).isNotEmpty()) { installBundled(); return }

        when (SetupChoices.route(autoDownloadTiny, autoDownloadLlm, whisperExists(), SetupChoices.completed(this), allModelsReady())) {
            SetupChoices.Route.TINY -> checkTinyDirect()
            SetupChoices.Route.LLM -> checkLlm()
            SetupChoices.Route.APP -> proceed()
            SetupChoices.Route.INTRO -> showIntro()
        }
    }

    /** Сколько всего скачает приложение при первом запуске (для вступительного текста): всё нужное плюс ИИ-помощник этой сборки. */
    private fun totalSizeText(): String {
        val llm = LlmModels.selected(this).sizeBytes
        val gb = (514_000_000L + llm) / 1e9
        return "около " + String.format(java.util.Locale.US, "%.1f", gb).replace('.', ',') + " ГБ"
    }

    private fun allModelsReady(): Boolean =
        whisperExists() && vadExists() && speakerExists() && pyannoteExists() &&
            (llmExists() || SetupChoices.llmSkipped(this))

    private fun showIntro() {
        progressBar.visibility = View.GONE
        tvStatus.text = "Всё работает на вашем телефоне"
        tvProgress.text =
            "GuzlikVoice не отправляет ваши записи в интернет: речь распознаётся и анализируется " +
            "прямо на телефоне.\n\n" +
            "Для этого один раз нужно скачать всё необходимое — ${totalSizeText()}. " +
            "Лучше по Wi-Fi, это займёт несколько минут. Дальше приложение работает без интернета."
        btnRetry.text = "Начать"
        btnRetry.visibility = View.VISIBLE
        btnRetry.setOnClickListener {
            btnRetry.setOnClickListener(null)
            btnRetry.visibility = View.GONE
            checkWhisper()
        }
        btnSkip.visibility = View.GONE
    }

    // ─── Прямая докачка одной модели (из настроек, "Быстрее"/"Точнее+") ────────

    private fun checkTinyDirect() {
        if (tinyExists()) { finish(); return }
        startAutoDownload(
            name      = "быстрого распознавания",
            fileName  = TINY_NAME,
            urls      = TINY_URLS,
            minSize   = TINY_MIN_SIZE,
            stepLabel = "",
            onSuccess = { finish() },
            onFailure = { showRetry("Ошибка загрузки. Проверьте интернет.") { checkTinyDirect() } }
        )
    }

    private fun tinyExists(): Boolean {
        val f = File(filesDir, TINY_NAME)
        return f.exists() && f.length() > TINY_MIN_SIZE
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadCancelled = true
    }

    // ─── Phase 1: речевая модель (обязательно, без пропуска) ───────────────────

    private fun checkWhisper() {
        if (whisperExists()) { checkVad(); return }
        startAutoDownload(
            name      = "распознавания речи",
            fileName  = WHISPER_NAME,
            urls      = WHISPER_URLS,
            minSize   = WHISPER_MIN_SIZE,
            stepLabel = "1 / 5",
            skippable = false,
            onSuccess = { checkVad() },
            onFailure = { showRetry("Ошибка загрузки. Проверьте интернет.") { checkWhisper() } }
        )
    }

    // ─── Phase 2: Silero VAD ──────────────────────────────────────────────────

    private fun checkVad() {
        if (vadExists()) { checkSpeaker(); return }
        // Download silently — no skip option, it's tiny (~1.8 MB)
        startAutoDownload(
            name      = "детектора речи",
            fileName  = VAD_NAME,
            urls      = VAD_URLS,
            minSize   = VAD_MIN_SIZE,
            stepLabel = "2 / 5",
            skippable = false,
            onSuccess = { checkSpeaker() },
            onFailure = {
                // Non-fatal: VAD is optional (energy fallback exists)
                Log.w("SetupActivity", "VAD download failed, continuing without it")
                checkSpeaker()
            }
        )
    }

    // ─── Phase 3: Speaker model ───────────────────────────────────────────────

    private fun checkSpeaker() {
        if (speakerExists()) { checkPyannote(); return }
        progressBar.visibility = View.GONE
        tvStatus.text = "Различать голоса собеседников?"
        tvProgress.text = "Приложение будет помечать, кто что сказал. Около 40 МБ. Можно скачать позже."
        btnRetry.text = "Скачать"
        btnRetry.visibility = View.VISIBLE
        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            btnSkip.visibility = View.GONE
            downloadSpeaker()
        }
        btnSkip.visibility = View.VISIBLE
        btnSkip.text = "Пропустить"
        btnSkip.setOnClickListener { checkLlm() }
    }

    private fun downloadSpeaker() {
        startAutoDownload(
            name      = "разделения голосов",
            fileName  = SPEAKER_NAME,
            urls      = SPEAKER_URLS,
            minSize   = SPEAKER_MIN_SIZE,
            stepLabel = "3 / 5",
            onSuccess = { checkPyannote() },
            onFailure = { showRetry("Ошибка загрузки. Проверьте интернет.") { checkSpeaker() } }
        )
    }

    // ─── Phase 3.5: pyannote segmentation-3.0 (тихая догрузка) ───────────────

    /** Pyannote нужен для «Level 4» диаризации (overlap, точнее K).
     *  Если качать не удалось — не блокируем, диаризация упадёт на Silero+sliding. */
    private fun checkPyannote() {
        if (pyannoteExists() || !speakerExists()) { checkLlm(); return }
        downloadPyannote()
    }

    private fun downloadPyannote() {
        startAutoDownload(
            name      = "продвинутой сегментации",
            fileName  = PYANNOTE_NAME,
            urls      = PYANNOTE_URLS,
            minSize   = PYANNOTE_MIN_SIZE,
            stepLabel = "4 / 5",
            skippable = false,
            onSuccess = { checkLlm() },
            onFailure = { checkLlm() }   // не обязательна: без неё диаризация на Silero+sliding
        )
    }

    // ─── Phase 4: ИИ-анализ (необязательно) ────────────────────────────────────

    private fun checkLlm() {
        if (llmExists()) { proceed(); return }
        if (autoDownloadLlm) { startLlmDownload(); return }
        val spec = LlmModels.selected(this)
        progressBar.visibility = View.GONE
        tvStatus.text = "Включить ИИ-помощника?"
        tvProgress.text = "Он выделяет главное из записей и отвечает на вопросы по ним. ${spec.sizeLabel}. " +
            "Можно скачать позже — предложим при первом «Выделить главное»."
        btnRetry.text = "Скачать"
        btnRetry.visibility = View.VISIBLE
        btnRetry.setOnClickListener { startLlmDownload() }
        btnSkip.visibility = View.VISIBLE
        btnSkip.text = "Пропустить"
        btnSkip.setOnClickListener { SetupChoices.skipLlm(this); proceed() }
    }

    private fun startLlmDownload() {
        btnRetry.visibility = View.GONE
        btnSkip.visibility = View.GONE
        val spec = LlmModels.selected(this)
        startAutoDownload(
            name      = "ИИ-помощника",
            fileName  = spec.fileName,
            urls      = spec.urls,
            minSize   = spec.minSize,
            stepLabel = "5 / 5",
            skippable = !autoDownloadLlm,
            onSuccess = { proceed() },
            onFailure = { showRetry("Ошибка загрузки. Проверьте интернет.") { checkLlm() } }
        )
    }

    // ─── Download engine ──────────────────────────────────────────────────────

    /**
     * Результат попытки скачивания. Разделяем сетевые ошибки (retry имеет смысл),
     * ошибки диска (нет смысла — надо освободить место), серверные (модель ушла)
     * и явную отмену пользователем (не показываем ошибку).
     */
    private sealed class DownloadResult {
        object Success : DownloadResult()
        object Cancelled : DownloadResult()
        data class NetworkError(val cause: Throwable?) : DownloadResult()  // timeout, DNS, разрыв
        data class ServerError(val code: Int) : DownloadResult()           // 4xx/5xx
        data class DiskError(val cause: Throwable?) : DownloadResult()      // IOException при записи
        data class BadFile(val actualSize: Long, val minSize: Long) : DownloadResult() // размер меньше ожидаемого
        object Corrupted : DownloadResult()                                // не сошлась контрольная сумма
    }

    private fun startAutoDownload(
        name:      String,
        fileName:  String,
        urls:      List<String>,
        minSize:   Long,
        stepLabel: String,
        skippable: Boolean = stepLabel.isNotEmpty(),
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        downloadCancelled = false
        lastProgressTime = System.currentTimeMillis()
        lastDownloadedBytes = 0L
        progressBar.visibility = View.VISIBLE
        progressBar.progress   = 0
        tvStatus.text   = "Загрузка $name..."
        tvProgress.text = if (stepLabel.isNotEmpty()) "Шаг $stepLabel — только при первом запуске"
                          else "Это займёт немного времени — только один раз"
        btnRetry.visibility = View.GONE

        if (skippable) {
            btnSkip.visibility = View.VISIBLE
            btnSkip.text = "Пропустить загрузку"
            btnSkip.setOnClickListener {
                downloadCancelled = true
                // .tmp специально не удаляем — на случай если юзер вернётся и продолжит.
                proceed()
            }
        } else {
            btnSkip.visibility = View.GONE
        }

        Thread {
            val dest = File(filesDir, fileName)
            val tmp  = File(filesDir, "$fileName.tmp")

            // Auto-retry с exponential backoff. Между попытками .tmp сохраняется,
            // Range-запрос продолжит с последнего байта. Пользователь не видит retry —
            // для него это просто "загрузка чуть тормозит".
            val backoffs = listOf(0L, 2_000L, 5_000L, 15_000L)
            var lastResult: DownloadResult = DownloadResult.NetworkError(null)
            var url = urls.first()

            // Источники по очереди: наше зеркало, затем оригинал. Следующий
            // пробуем, если с текущего не скачалось или файл не сошёлся.
            sources@ for ((srcIndex, srcUrl) in urls.withIndex()) {
                if (srcIndex > 0) {
                    // Докачивать .tmp можно, только если источники отдают один и тот же файл.
                    if (ModelIntegrity.expectedFor(fileName, url) == null ||
                        ModelIntegrity.expectedFor(fileName, url) != ModelIntegrity.expectedFor(fileName, srcUrl)) tmp.delete()
                    Log.w("SetupActivity", "$fileName: switching to source ${srcIndex + 1}/${urls.size}")
                }
                url = srcUrl
                for ((attempt, delay) in backoffs.withIndex()) {
                    if (downloadCancelled) { lastResult = DownloadResult.Cancelled; break }
                    if (delay > 0) {
                        mainHandler.post {
                            tvProgress.text = "Обрыв связи. Продолжаю через ${delay / 1000} сек... (попытка ${attempt + 1})"
                        }
                        try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
                        if (downloadCancelled) { lastResult = DownloadResult.Cancelled; break }
                    }
                    lastResult = downloadOnce(url, tmp, minSize) { pct, dlMb, totalMb ->
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastProgressTime
                        val speedMbs = if (elapsed > 500) {
                            val dlBytes = dlMb * 1_000_000L
                            val bytesPerSec = (dlBytes - lastDownloadedBytes) * 1000L / elapsed
                            lastProgressTime = now
                            lastDownloadedBytes = dlBytes
                            "%.1f МБ/с".format(bytesPerSec / 1_000_000.0)
                        } else ""
                        mainHandler.post {
                            progressBar.progress = pct
                            val stepPrefix = if (stepLabel.isNotEmpty()) "Шаг $stepLabel   " else ""
                            tvProgress.text = "$stepPrefix$pct%  •  $dlMb / $totalMb МБ${if (speedMbs.isNotEmpty()) "  •  $speedMbs" else ""}"
                        }
                    }
                    // Успех, отмена или неустранимая ошибка — прекращаем retry
                    when (lastResult) {
                        is DownloadResult.Success,
                        is DownloadResult.Cancelled,
                        is DownloadResult.DiskError,
                        is DownloadResult.ServerError,
                        is DownloadResult.BadFile,
                        is DownloadResult.Corrupted -> break
                        is DownloadResult.NetworkError -> continue   // сетевая — retry
                    }
                }
                when (lastResult) {
                    is DownloadResult.Cancelled, is DownloadResult.DiskError -> break@sources
                    is DownloadResult.Success -> {
                        // Размер ≠ целостность: обрыв/подмена на CDN даёт файл, на
                        // котором whisper/llama.cpp падают или выдают мусор.
                        mainHandler.post { tvProgress.text = "Проверяю целостность файла..." }
                        if (ModelIntegrity.verify(tmp, fileName, url)) break@sources
                        tmp.delete()
                        lastResult = DownloadResult.Corrupted
                    }
                    else -> {}
                }
            }

            when (val r = lastResult) {
                is DownloadResult.Success -> {
                    if (tmp.renameTo(dest)) mainHandler.post(onSuccess)
                    else mainHandler.post {
                        showRetry("Не удалось сохранить файл. Проверьте свободное место.") { onFailure() }
                    }
                }
                is DownloadResult.Corrupted -> mainHandler.post {
                    showRetry("Файл повреждён при загрузке. Скачаем заново.") { onFailure() }
                }
                is DownloadResult.Cancelled -> {
                    // Юзер сам отменил — proceed() уже вызван из onClickListener
                }
                is DownloadResult.NetworkError -> mainHandler.post {
                    val partial = if (tmp.exists()) " (уже скачано ${tmp.length() / 1_000_000} МБ)" else ""
                    showRetry("Проблемы со связью$partial. Проверьте интернет.") { onFailure() }
                }
                is DownloadResult.ServerError -> mainHandler.post {
                    showRetry("Сервер недоступен (код ${r.code}). Попробуйте позже.") { onFailure() }
                }
                is DownloadResult.DiskError -> mainHandler.post {
                    tmp.delete()   // повреждённый файл, начнём с нуля при retry
                    showRetry("Не удалось записать файл. Проверьте свободное место на устройстве.") { onFailure() }
                }
                is DownloadResult.BadFile -> mainHandler.post {
                    tmp.delete()
                    showRetry("Файл скачался не полностью. Проверьте интернет и попробуйте ещё раз.") { onFailure() }
                }
            }
        }.start()
    }

    private fun showRetry(msg: String, onRetry: () -> Unit) {
        progressBar.visibility = View.GONE
        tvStatus.text   = msg
        tvProgress.text = ""
        btnRetry.text   = "Попробовать снова"
        btnRetry.setOnClickListener { onRetry() }
        btnRetry.visibility = View.VISIBLE
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Первый запуск со встроенными моделями: копирование из APK с индикатором прогресса. */
    private fun installBundled() {
        btnRetry.visibility = View.GONE
        btnSkip.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = 1000
        progressBar.progress = 0
        tvStatus.text = "Подготовка к работе"
        tvProgress.text = "Первый запуск: устанавливаем встроенные модели. Интернет не нужен."
        Thread {
            val result = BundledModels.install(this) { done, total, _ ->
                mainHandler.post {
                    if (total > 0) progressBar.progress = (1000.0 * done / total).toInt()
                    tvProgress.text = "Установлено ${done / 1_000_000} из ${total / 1_000_000} МБ"
                }
            }
            mainHandler.post {
                when (result) {
                    is BundledModels.Result.Ok -> { SetupChoices.finish(this); proceed() }
                    is BundledModels.Result.NoSpace -> showBundledError(
                        "Не хватает места: освободите ещё ${result.missingBytes / 1_000_000 + 1} МБ и повторите.")
                    is BundledModels.Result.Failed -> showBundledError("Не удалось установить модели: ${result.message}")
                }
            }
        }.start()
    }

    private fun showBundledError(text: String) {
        tvStatus.text = "Установка не завершена"
        tvProgress.text = text
        btnRetry.text = "Повторить"
        btnRetry.visibility = View.VISIBLE
        btnRetry.setOnClickListener { btnRetry.setOnClickListener(null); installBundled() }
    }

    private fun proceed() {
        if (whisperExists()) SetupChoices.finish(this)
        // Запущено из экрана записи/настроек только ради докачки одной модели —
        // пользователь уже залогинен, возвращаемся туда же, а не через AuthActivity заново.
        if (autoDownloadLlm) { finish(); return }
        startActivity(Intent(this, AuthActivity::class.java)); finish()
    }

    private fun whisperExists(): Boolean {
        data class Candidate(val name: String, val minBytes: Long)
        // Первым — новая (v3.5) podlodka-turbo; следом — legacy-модели ggerganov,
        // чтобы юзеры с уже скачанным ggml-small.bin из v3.4 не качали заново.
        val candidates = listOf(
            Candidate(WhisperCppEngine.PODLODKA_TURBO_FILE, WhisperCppEngine.PODLODKA_TURBO_MIN_BYTES),
            Candidate(WhisperCppEngine.PODLODKA_TURBO_Q5_0_FILE, WhisperCppEngine.PODLODKA_TURBO_Q5_0_MIN_BYTES),
            Candidate(WhisperCppEngine.SMALL_FILE,          WhisperCppEngine.SMALL_MIN_BYTES),
            Candidate("ggml-medium.bin",                    400_000_000L),
            Candidate("ggml-small.bin",                     400_000_000L),
            Candidate("ggml-base.bin",                       50_000_000L),
            Candidate(WhisperCppEngine.TINY_FILE,           WhisperCppEngine.TINY_MIN_BYTES),
        )
        return candidates.any { (name, minBytes) ->
            File(filesDir, name).let { it.exists() && it.length() > minBytes } ||
            getExternalFilesDir(null)?.let { File(it, name).let { f -> f.exists() && f.length() > minBytes } } == true
        }
    }

    private fun llmExists(): Boolean = LlmModels.isDownloaded(this, LlmModels.selected(this))

    private fun speakerExists(): Boolean {
        val f = File(filesDir, SPEAKER_NAME)
        if (f.exists() && f.length() > SPEAKER_MIN_SIZE) return true
        val ext = getExternalFilesDir(null)
        if (ext != null) {
            val fe = File(ext, SPEAKER_NAME)
            if (fe.exists() && fe.length() > SPEAKER_MIN_SIZE) return true
        }
        return false
    }

    private fun vadExists(): Boolean {
        val f = File(filesDir, VAD_NAME)
        if (f.exists() && f.length() > VAD_MIN_SIZE) return true
        val ext = getExternalFilesDir(null)
        if (ext != null) {
            val fe = File(ext, VAD_NAME)
            if (fe.exists() && fe.length() > VAD_MIN_SIZE) return true
        }
        return false
    }

    private fun pyannoteExists(): Boolean {
        val f = File(filesDir, PYANNOTE_NAME)
        if (f.exists() && f.length() > PYANNOTE_MIN_SIZE) return true
        val ext = getExternalFilesDir(null)
        if (ext != null) {
            val fe = File(ext, PYANNOTE_NAME)
            if (fe.exists() && fe.length() > PYANNOTE_MIN_SIZE) return true
        }
        return false
    }

    /**
     * Одна попытка скачивания. При наличии .tmp продолжает с последнего байта
     * через HTTP Range. Возвращает типизированный результат — вызывающий сам
     * решит: retry, показать пользователю или продолжить с успехом.
     *
     * Что важно:
     *  - На NetworkError .tmp сохраняется как есть (продолжим при retry).
     *  - На DiskError .tmp тоже сохраняется, но retry вероятно тоже упадёт.
     *  - Если сервер вернул 200 вместо 206 — просто перезапишем с нуля
     *    (например HuggingFace mirror, не поддерживающий Range).
     */
    private fun downloadOnce(
        startUrl:   String,
        dest:       File,
        minSize:    Long,
        onProgress: (Int, Long, Long) -> Unit,
    ): DownloadResult {
        val existingSize = if (dest.exists()) dest.length() else 0L
        var urlStr = startUrl

        for (redirect in 0 until 10) {
            if (downloadCancelled) return DownloadResult.Cancelled

            val conn = try {
                (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout    = 60_000
                    if (existingSize > 0) setRequestProperty("Range", "bytes=$existingSize-")
                }
            } catch (e: IOException) {
                return DownloadResult.NetworkError(e)
            }

            try {
                try { conn.connect() }
                catch (e: SocketTimeoutException) { return DownloadResult.NetworkError(e) }
                catch (e: UnknownHostException)   { return DownloadResult.NetworkError(e) }
                catch (e: IOException)            { return DownloadResult.NetworkError(e) }

                val code = try { conn.responseCode }
                catch (e: IOException) { return DownloadResult.NetworkError(e) }

                // Redirect (301/302/303/307/308) — идём по Location
                if (code in 300..399) {
                    urlStr = conn.getHeaderField("Location")
                        ?: return DownloadResult.ServerError(code)
                    continue
                }

                // 200 = полный ответ, 206 = partial content (resume принят)
                val resuming = code == 206
                val totalSize: Long = when (code) {
                    200 -> conn.contentLengthLong
                    206 -> {
                        // Content-Range: "bytes 100-999/1000"
                        val cr = conn.getHeaderField("Content-Range")
                        cr?.substringAfter("/")?.toLongOrNull()
                            ?: (existingSize + conn.contentLengthLong)
                    }
                    416 -> {
                        // Requested Range Not Satisfiable — файл уже полный или сервер не понял.
                        // Проверим размер локально. Если больше minSize — считаем что уже готово.
                        return if (dest.length() > minSize) DownloadResult.Success
                               else { dest.delete(); DownloadResult.ServerError(416) }
                    }
                    in 400..599 -> return DownloadResult.ServerError(code)
                    else -> return DownloadResult.ServerError(code)
                }

                if (!resuming && existingSize > 0) {
                    Log.i("SetupActivity", "Server returned 200 (no Range support), restarting download")
                }

                var downloaded = if (resuming) existingSize else 0L
                try {
                    conn.inputStream.use { inp ->
                        FileOutputStream(dest, resuming).use { out ->
                            val buf = ByteArray(32_768); var n: Int
                            while (inp.read(buf).also { n = it } != -1) {
                                if (downloadCancelled) return DownloadResult.Cancelled
                                out.write(buf, 0, n); downloaded += n
                                if (totalSize > 0) onProgress(
                                    (downloaded * 100 / totalSize).toInt(),
                                    downloaded / 1_000_000,
                                    totalSize / 1_000_000
                                )
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) { return DownloadResult.NetworkError(e) }
                  catch (e: IOException) {
                      // Не отличить сетевую от disk-full наверняка — смотрим место
                      val free = filesDir.freeSpace
                      return if (free < 10_000_000L) DownloadResult.DiskError(e)
                             else DownloadResult.NetworkError(e)
                  }

                val finalSize = dest.length()
                return if (finalSize > minSize) DownloadResult.Success
                       else DownloadResult.BadFile(finalSize, minSize)

            } finally {
                conn.disconnect()
            }
        }
        return DownloadResult.ServerError(-1)  // слишком много редиректов
    }
}
