package com.example.guzlikvoice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File

class SettingsSheet : BottomSheetDialogFragment() {

    companion object {
        private const val PRIVACY_URL = "https://voice-mind.ru/privacy.html"

        // «Быстрее» — whisper-small q5_1 (190 МБ): tiny для русского ошибалась слишком часто.
        private const val TINY_NAME     = WhisperCppEngine.SMALL_FILE
        private val TINY_URLS = ModelMirror.urls(WhisperCppEngine.SMALL_FILE, WhisperCppEngine.SMALL_URL)
        private const val TINY_MIN_SIZE = WhisperCppEngine.SMALL_MIN_BYTES

        // «Точная» — Whisper-large-v3-turbo, дообученный на русском podlodka-корпусе
        // (bond005), GGML q4_0 от JoaoZaokk. Legacy q5_0 из v3.5 тоже принимается.
        private const val ACCURATE_NAME     = WhisperCppEngine.PODLODKA_TURBO_FILE
        private val ACCURATE_URLS = ModelMirror.urls(WhisperCppEngine.PODLODKA_TURBO_FILE, WhisperCppEngine.PODLODKA_TURBO_URL)
        private const val ACCURATE_MIN_SIZE = WhisperCppEngine.PODLODKA_TURBO_MIN_BYTES
    }

    var onSettingsChanged: (() -> Unit)? = null

    private val pickModelFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importModel(uri) }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val backupUi = BackupUi(this, { context }, { activity })

    /** Слушатель прогресса от глобального [ModelDownloader]. */
    private val downloadListener = object : ModelDownloader.Listener {
        override fun onProgress(modelName: String, pct: Int, downloadedMb: Long, totalMb: Long) {
            if (!isAdded || !::progressBar.isInitialized) return
            progressBar.progress = pct
            tvStatus.text = "$pct%  •  $downloadedMb / $totalMb МБ"
        }
        override fun onFinished(modelName: String, success: Boolean) {
            if (!isAdded) return
            renderDownloadIdle(AppPrefs.getWhisperModel(requireContext()))
            if (success) {
                // Новая q4_0 заменяет старую q5_0 — освобождаем 574 МБ.
                if (modelName == ACCURATE_NAME) {
                    File(requireContext().filesDir, WhisperCppEngine.PODLODKA_TURBO_Q5_0_FILE).delete()
                }
                Toast.makeText(requireContext(), "Готово", Toast.LENGTH_SHORT).show()
                onSettingsChanged?.invoke()
            } else {
                Toast.makeText(requireContext(),
                    "Ошибка загрузки. Проверьте интернет.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private lateinit var modelToggle: MaterialButtonToggleGroup
    private lateinit var btnTiny:      MaterialButton
    private lateinit var btnSmall:     MaterialButton
    private lateinit var tvHint:       TextView
    private lateinit var tvChangeHint: TextView
    private lateinit var btnDownload:  MaterialButton
    private lateinit var progressBar:  ProgressBar
    private lateinit var tvStatus:     TextView



    private lateinit var rowTools:      LinearLayout
    private lateinit var tvToolsStatus: TextView

    private lateinit var youGileUi: YouGileUi

    private lateinit var switchWhisperGpu:   MaterialSwitch
    private lateinit var tvGpuBackendStatus: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        modelToggle  = view.findViewById(R.id.toggleWhisperModel)
        btnTiny      = view.findViewById(R.id.btnModelTiny)
        btnSmall     = view.findViewById(R.id.btnModelSmall)
        btnTiny.isEnabled = !WearableService.snapshot.active
        btnSmall.isEnabled = !WearableService.snapshot.active
        tvHint       = view.findViewById(R.id.tvModelHint)
        tvChangeHint = view.findViewById(R.id.tvChangeHint)
        btnDownload  = view.findViewById(R.id.btnDownloadModel)
        progressBar  = view.findViewById(R.id.progressDownload)
        tvStatus     = view.findViewById(R.id.tvDownloadStatus)

        // Начальное состояние — то что стоит в настройках
        val currentModel = AppPrefs.getWhisperModel(ctx)
        modelToggle.check(when (currentModel) {
            AppPrefs.MODEL_TINY -> R.id.btnModelTiny
            else                -> R.id.btnModelSmall
        })
        refreshUi(currentModel)
        if (BuildConfig.CONTEST_BUILD) {
            modelToggle.visibility = View.GONE
            tvChangeHint.visibility = View.GONE
            view.findViewById<View>(R.id.btnImportModel).visibility = View.GONE
            view.findViewById<View>(R.id.tvModelImportHint).visibility = View.GONE
        }

        modelToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (ModelDownloader.isDownloading()) return@addOnButtonCheckedListener

            val model = when (checkedId) {
                R.id.btnModelTiny -> AppPrefs.MODEL_TINY
                else              -> AppPrefs.MODEL_ACCURATE
            }

            AppPrefs.setWhisperModel(ctx, model)
            refreshUi(model)
            // Reload whisper only when the selected variant is actually available
            if (isModelReady(model)) onSettingsChanged?.invoke()
        }

        btnDownload.setOnClickListener {
            if (BuildConfig.OFFLINE_PROTOTYPE) { pickModelFile.launch(arrayOf("*/*")); return@setOnClickListener }
            val selected = AppPrefs.getWhisperModel(ctx)
            startDownload(selected)
        }

        // Подписываемся один раз, отписываемся в onDestroyView. Загрузка живёт
        // в глобальном ModelDownloader и НЕ отменяется при закрытии sheet.
        ModelDownloader.addListener(downloadListener)
        if (ModelDownloader.isDownloading()) {
            renderDownloadInProgress()
            // Тут же перерисуем последний известный прогресс.
            downloadListener.onProgress(
                ModelDownloader.currentModel ?: "",
                ModelDownloader.lastPct,
                ModelDownloader.lastDlMb,
                ModelDownloader.lastTotalMb
            )
        }

        // ── Язык речи ──────────────────────────────────────────────────────────
        val toggleLang = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleAsrLanguage)
        val tvLangHint = view.findViewById<TextView>(R.id.tvAsrLanguageHint)
        val langHints = mapOf(
            R.id.btnLangAuto to "Русский или английский — определяется для каждой минуты записи, так что можно переходить с языка на язык. Добавляет несколько секунд к расшифровке.",
            R.id.btnLangRu   to "Все записи на русском. Английские слова внутри русской речи распознаются как обычно.",
            R.id.btnLangEn   to "Все записи на английском."
        )
        val langIds = mapOf(
            AppPrefs.LANG_AUTO to R.id.btnLangAuto,
            AppPrefs.LANG_RU   to R.id.btnLangRu,
            AppPrefs.LANG_EN   to R.id.btnLangEn
        )
        val langChecked = langIds[AppPrefs.getAsrLanguage(ctx)] ?: R.id.btnLangAuto
        toggleLang.check(langChecked)
        tvLangHint.text = langHints[langChecked]
        toggleLang.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            AppPrefs.setAsrLanguage(ctx, langIds.entries.first { it.value == id }.key)
            tvLangHint.text = langHints[id]
        }

        // ── Где записываете (порог Silero VAD) ─────────────────────────────────
        val toggleMic = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleMicMode)
        val tvMicHint = view.findViewById<TextView>(R.id.tvMicModeHint)
        val micHints = mapOf(
            R.id.btnMicAuto   to "Приложение само подстраивается под обстановку в каждом фрагменте записи: в тишине бережёт паузы, при ровном шуме (кондиционер, кухня) ловит тихие и дальние голоса, в кафе отсекает гул. Подходит почти всегда.",
            R.id.btnMicQuiet  to "Тихая речь, телефон далеко от говорящих, лекция из зала. Ловит всё, но в шуме может принять шум за речь.",
            R.id.btnMicNormal to "Разговор рядом с телефоном: встреча, приём, заметка себе.",
            R.id.btnMicNoisy  to "Кафе, улица, транспорт, работающая техника. Пропускает фоновый шум, но может пропустить тихие реплики."
        )
        val current = AppPrefs.getMicSensitivity(ctx)
        val checkedId = when {
            AppPrefs.isMicAuto(ctx) -> R.id.btnMicAuto
            current < 100f -> R.id.btnMicQuiet
            current > 250f -> R.id.btnMicNoisy
            else           -> R.id.btnMicNormal
        }
        toggleMic.check(checkedId)
        tvMicHint.text = micHints[checkedId]
        toggleMic.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            AppPrefs.setMicAuto(ctx, id == R.id.btnMicAuto)
            if (id != R.id.btnMicAuto) AppPrefs.setMicSensitivity(ctx, when (id) {
                R.id.btnMicQuiet -> AppPrefs.MIC_MODE_QUIET
                R.id.btnMicNoisy -> AppPrefs.MIC_MODE_NOISY
                else             -> AppPrefs.MIC_MODE_NORMAL
            })
            tvMicHint.text = micHints[id]
        }

        // ── Мой словарь ────────────────────────────────────────────────────────
        val etDictionary = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDictionary)
        etDictionary.setText(AppPrefs.getCustomDictionary(ctx))
        etDictionary.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                AppPrefs.setCustomDictionary(ctx, s?.toString().orEmpty())
            }
        })

        // ── Календарь и встречи → отдельный экран ToolsActivity ────────────────
        rowTools      = view.findViewById(R.id.rowTools)
        tvToolsStatus = view.findViewById(R.id.tvToolsStatusRow)
        refreshToolsStatus()
        rowTools.setOnClickListener {
            startActivity(Intent(ctx, ToolsActivity::class.java))
        }

        // ── YouGile ───────────────────────────────────────────────────────────
        youGileUi = YouGileUi(this) { renderYouGile() }
        renderYouGile()

        // ── GPU (Vulkan) для Whisper ──────────────────────────────────────────
        // Тумблер меняет только AppPrefs. Реальный переключатель backend'а —
        // при следующем init'е WhisperCppEngine (onSettingsChanged → reload).
        switchWhisperGpu   = view.findViewById(R.id.switchWhisperGpu)
        tvGpuBackendStatus = view.findViewById(R.id.tvGpuBackendStatus)
        switchWhisperGpu.isChecked = AppPrefs.isWhisperGpuEnabled(ctx)
        switchWhisperGpu.isEnabled = !WearableService.snapshot.active
        refreshGpuBackendStatus()
        switchWhisperGpu.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setWhisperGpuEnabled(ctx, checked)
            AppPrefs.setLlmGpuEnabled(ctx, checked)
            if (checked) AppPrefs.clearBenchResults(ctx)   // перемерить GPU vs CPU
            // Показываем «Переключится после перезагрузки движка» — фактический
            // статус GPU/CPU обновится когда WhisperCppEngine.init() отчитается.
            tvGpuBackendStatus.text = if (checked) "Ускорение: попробует видеочип при следующем запуске"
                                      else         "Ускорение: процессор при следующем запуске"
            onSettingsChanged?.invoke()
        }

        // ── Beam search (точность) ────────────────────────────────────────────
        // Читается при каждой расшифровке — перезагрузка движка не нужна.
        val switchBeam = view.findViewById<MaterialSwitch>(R.id.switchWhisperBeam)
        switchBeam.isChecked = AppPrefs.isWhisperBeamSearch(ctx)
        switchBeam.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setWhisperBeamSearch(ctx, checked)
        }

        // ── Export all as JSON ────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btnExportAll).setOnClickListener { backupUi.startExport() }
        view.findViewById<MaterialButton>(R.id.btnRestoreBackup).setOnClickListener { backupUi.startImport() }

        view.findViewById<MaterialButton>(R.id.btnWipeAll).setOnClickListener { confirmWipeAll() }

        view.findViewById<MaterialButton>(R.id.btnImportModel).setOnClickListener {
            if (WearableService.snapshot.active) {
                Toast.makeText(ctx, "Сначала отключите внешнее устройство", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            pickModelFile.launch(arrayOf("*/*"))
        }

        // ── Поддержка ─────────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btnReportProblem).setOnClickListener {
            CrashReporter.sendReport(requireActivity())
        }
        view.findViewById<MaterialButton>(R.id.btnPrivacy).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL)))
            }.onFailure { Toast.makeText(ctx, PRIVACY_URL, Toast.LENGTH_LONG).show() }
        }
        view.findViewById<TextView>(R.id.tvAppVersion).text =
            "GuzlikVoice ${BuildConfig.VERSION_NAME} · всё считается на телефоне"

        // «Тест скорости ИИ» идёт 15–20 минут и выгружает рабочую модель — тестерам пилота не нужен.
        view.findViewById<View>(R.id.groupSpeedLab).visibility = if (BuildConfig.DEBUG && !BuildConfig.CONTEST_BUILD) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.btnSpeedLab).setOnClickListener {
            activity?.let { SpeedLab.showDialog(it) }
        }
    }

    /**
     * Офлайн-установка модели: копируем выбранный файл, попутно считая SHA256,
     * и по сумме определяем, какая это модель (Whisper/Gemma/голоса/детектор речи). Неизвестный или
     * битый файл не сохраняем.
     */
    private fun importModel(uri: android.net.Uri) {
        val ctx = context?.applicationContext ?: return
        Toast.makeText(ctx, "Копирую и проверяю файл…", Toast.LENGTH_SHORT).show()
        Thread {
            val tmp = File(ctx.filesDir, "import.tmp")
            val message = try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "не удалось открыть файл" }
                    java.security.DigestInputStream(input, md).use { din ->
                        tmp.outputStream().use { out -> din.copyTo(out, 1 shl 20) }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                val name = ModelIntegrity.identify(sha)
                if (name == null) {
                    tmp.delete()
                    "Этот файл не подходит или повреждён"
                } else if (!tmp.renameTo(File(ctx.filesDir, name))) {
                    tmp.delete()
                    "Не удалось сохранить файл — проверьте свободное место"
                } else {
                    AppPrefs.clearBenchResults(ctx)
                    "Установлено"
                }
            } catch (e: Exception) {
                tmp.delete()
                "Импорт не удался: ${ErrorMessages.humanize(e)}"
            }
            mainHandler.post {
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                if (isAdded) {
                    refreshUi(AppPrefs.getWhisperModel(ctx))
                    onSettingsChanged?.invoke()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::tvToolsStatus.isInitialized) refreshToolsStatus()
        // WhisperCppEngine.init() может пройти асинхронно между открытиями sheet'а —
        // обновляем бейдж каждый раз при появлении, чтобы отражал текущий backend.
        if (::tvGpuBackendStatus.isInitialized) refreshGpuBackendStatus()
    }

    private fun renderYouGile() {
        val v = view ?: return
        val ctx = v.context
        val connected = YouGile.isConfigured(ctx)
        v.findViewById<TextView>(R.id.tvYouGileStatus).text =
            if (connected) "Подключён: ${YouGile.companyName(ctx)}" else "Не подключён"
        v.findViewById<TextView>(R.id.tvYouGileColumn).text =
            if (connected) YouGile.defaultColumn(ctx)?.title?.let { "Новые задачи: $it" } ?: "Колонка для новых задач не выбрана" else ""
        val btnConnect = v.findViewById<MaterialButton>(R.id.btnYouGileConnect)
        btnConnect.text = if (connected) "Отключить" else "Подключить"
        btnConnect.setOnClickListener { if (connected) youGileUi.disconnect() else youGileUi.connect() }
        v.findViewById<MaterialButton>(R.id.btnYouGileColumn).apply {
            isEnabled = connected
            setOnClickListener { youGileUi.chooseColumn() }
        }
    }

    /** «Удалить все данные» (ТЗ: П-9, Д-3). Модели и настройки остаются — см. [DataWipe]. */
    private fun confirmWipeAll() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Удалить все данные?")
            .setMessage("С телефона будут удалены все записи с аудио, клиенты, голосовые профили, " +
                "«Мой словарь» и подключения календаря и YouGile. Восстановить их можно только из " +
                "резервной копии — сохраните её заранее, если она нужна.\n\nСкачанные компоненты и настройки останутся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить всё") { _, _ ->
                val appCtx = ctx.applicationContext
                Toast.makeText(appCtx, "Удаляю…", Toast.LENGTH_SHORT).show()
                Thread {
                    val msg = try {
                        val r = DataWipe.wipeAll(appCtx)
                        "Данные удалены (записей: ${r.records})"
                    } catch (e: Exception) {
                        "Удалить не удалось: ${ErrorMessages.humanize(e)}"
                    }
                    mainHandler.post {
                        Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                        onSettingsChanged?.invoke()
                    }
                }.start()
            }
            .show()
    }

    private fun refreshGpuBackendStatus() {
        // После авто-замера CPU может оказаться быстрее GPU — это нормальный выбор.
        tvGpuBackendStatus.text = if (WhisperCppEngine.lastKnownGpuActive) "Ускорение: видеочип — быстрее на этом телефоне"
                                  else                                     "Ускорение: процессор"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ModelDownloader.removeListener(downloadListener)
        // Drain any queued UI updates so downloader callbacks scheduled onto
        // this handler don't fire after the view is gone (NPE on lateinit views).
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun refreshToolsStatus() {
        val ctx = context ?: return
        tvToolsStatus.text = CalendarProvider.statusLabel(ctx)
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    /** Стоит только старая q5_0: q4_0 в 2-3 раза быстрее на CPU (веса перепаковываются под dotprod). */
    private fun onlyLegacyAccurate(): Boolean {
        val ctx = context ?: return false
        val q4 = File(ctx.filesDir, ACCURATE_NAME)
        val q5 = File(ctx.filesDir, WhisperCppEngine.PODLODKA_TURBO_Q5_0_FILE)
        return !(q4.exists() && q4.length() > ACCURATE_MIN_SIZE) &&
            q5.exists() && q5.length() > WhisperCppEngine.PODLODKA_TURBO_Q5_0_MIN_BYTES
    }

    private fun refreshUi(model: String) {
        val ready = isModelReady(model)
        if (BuildConfig.CONTEST_BUILD) {
            tvHint.text = "Точная модель распознавания и Gemma встроены в приложение. Всё работает без интернета"
            btnDownload.visibility = View.GONE
            return
        }
        if (BuildConfig.OFFLINE_PROTOTYPE) {
            tvHint.text = if (ready) "Модель установлена. Распознавание работает без интернета"
                          else "Установите модель из файла — в этой сборке интернет отключён"
            btnDownload.visibility = if (ready) View.GONE else View.VISIBLE
            btnDownload.text = "Установить из файла"
            return
        }
        if (model != AppPrefs.MODEL_TINY && onlyLegacyAccurate() && !ModelDownloader.isDownloading()) {
            tvHint.text = "Есть улучшенная версия: распознаёт в 2–3 раза быстрее. Старую удалим"
            btnDownload.visibility = View.VISIBLE
            btnDownload.text = "Обновить"
            return
        }
        tvHint.text = when (model) {
            // Замер 8–9.10: ошибок примерно втрое больше, чем у «Точно» — говорим прямо.
            AppPrefs.MODEL_TINY -> if (ready) "Работает быстрее, но ошибок примерно втрое больше, чем в «Точно». Для совещаний не подходит"
                                   else       "Работает быстрее, но ошибок примерно втрое больше, чем в «Точно». Для совещаний не подходит. Нужно скачать"
            else -> if (ready) "Лучше распознаёт русскую речь, работает дольше"
                    else       "Лучше распознаёт русскую речь, работает дольше. Нужно скачать"
        }

        if (ready || ModelDownloader.isDownloading()) {
            btnDownload.visibility = View.GONE
        } else {
            btnDownload.visibility = View.VISIBLE
            btnDownload.text = when (model) {
                AppPrefs.MODEL_TINY -> "Скачать"
                else                -> "Скачать"
            }
        }
    }

    private fun isModelReady(model: String): Boolean {
        val ctx = context ?: return false
        if (BuildConfig.CONTEST_BUILD) return File(ctx.filesDir, ACCURATE_NAME)
            .let { it.isFile && it.length() > ACCURATE_MIN_SIZE }
        // Для точной модели принимаем И новый q4_0, И legacy q5_0 — пользователю
        // v3.5 не показываем «Скачать», если у него уже есть 574 МБ q5_0.
        val candidates = when (model) {
            AppPrefs.MODEL_TINY -> listOf(
                TINY_NAME to TINY_MIN_SIZE,
                WhisperCppEngine.TINY_FILE to WhisperCppEngine.TINY_MIN_BYTES,
            )
            else                -> listOf(
                ACCURATE_NAME to ACCURATE_MIN_SIZE,
                WhisperCppEngine.PODLODKA_TURBO_Q5_0_FILE to WhisperCppEngine.PODLODKA_TURBO_Q5_0_MIN_BYTES,
            )
        }
        return candidates.any { (name, minSize) ->
            File(ctx.filesDir, name).let { it.exists() && it.length() > minSize } ||
                ctx.getExternalFilesDir(null)?.let {
                    File(it, name).let { f -> f.exists() && f.length() > minSize }
                } == true
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun startDownload(model: String) {
        val ctx = context ?: return
        val (name, urls, minSize) = when (model) {
            AppPrefs.MODEL_TINY -> Triple(TINY_NAME,     TINY_URLS,     TINY_MIN_SIZE)
            else                -> Triple(ACCURATE_NAME, ACCURATE_URLS, ACCURATE_MIN_SIZE)
        }
        val started = ModelDownloader.start(ctx.applicationContext, name, urls, minSize)
        if (!started) {
            Toast.makeText(ctx, "Загрузка уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        renderDownloadInProgress()
    }

    private fun renderDownloadInProgress() {
        btnDownload.visibility  = View.GONE
        progressBar.visibility  = View.VISIBLE
        tvStatus.visibility     = View.VISIBLE
        tvChangeHint.visibility = View.GONE
        modelToggle.isEnabled   = false
        progressBar.progress    = 0
        tvStatus.text           = "Начало загрузки..."
    }

    private fun renderDownloadIdle(model: String) {
        progressBar.visibility  = View.GONE
        tvStatus.visibility     = View.GONE
        tvChangeHint.visibility = View.VISIBLE
        modelToggle.isEnabled   = true
        refreshUi(model)
    }
}
