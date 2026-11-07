package com.guzlik.voice

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordFragment : Fragment(R.layout.fragment_record) {

    companion object {
        private const val TAG          = "RecordFragment"
        private const val SAMPLE_RATE  = 16_000
        private const val CHUNK_FRAMES = 1_600  // 100ms per chunk
        private val processingScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }

    // ─── Views ────────────────────────────────────────────────────────────────

    private lateinit var btnVosk:        MaterialButton
    private lateinit var tvVoskStatus:   TextView
    private lateinit var tvModelBadge:   TextView
    private lateinit var tvTopicChip:    TextView
    private lateinit var tvMicStatus:    TextView
    private lateinit var dotMicStatus:   View
    private lateinit var tvRecordHint:   TextView
    private lateinit var pulseRing:      View
    private lateinit var cardResult:     MaterialCardView
    private lateinit var tvResult:       TextView
    private lateinit var tvResultMeta:   TextView
    private lateinit var cardAnalysis:   MaterialCardView
    private lateinit var tvKeyIdeas:     TextView
    private lateinit var tvAnalysisMeta: TextView
    private lateinit var tvAnalysisTags: TextView
    private lateinit var btnImproveText:   MaterialButton
    private lateinit var btnCopyAnalysis:  MaterialButton
    private lateinit var btnShareAnalysis: MaterialButton
    private lateinit var btnMicSource:     MaterialButton

    // Fix 1: Timer
    private lateinit var tvRecordTimer: TextView
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timerSeconds = 0
    private var timerRunnable: Runnable? = null

    // Fix 2: Cancel processing
    private lateinit var btnCancelProcessing: MaterialButton
    @Volatile private var processingCancelled = false
    // Момент нажатия "стоп" — от него считаем сколько реально заняла расшифровка,
    // чтобы было видно на слабом железе, во сколько раз модель медленнее реального времени.
    @Volatile private var processingStartMs = 0L

    // Fix 3: Amplitude
    private lateinit var progressAmplitude: ProgressBar
    private lateinit var waveform: WaveformView

    // Fix 4: Copy/Share transcript
    private lateinit var layoutTranscriptActions: android.widget.LinearLayout
    private lateinit var btnCopyTranscript: MaterialButton
    private lateinit var btnShareTranscript: MaterialButton

    // LLM loading indicator
    private lateinit var llmLoadingIndicator: android.widget.LinearLayout
    private lateinit var tvLlmLoadingText: TextView

    // ─── State ────────────────────────────────────────────────────────────────

    private var improvementVersion = 0L
    private var whisperHelper:   WhisperHelper?       = null
    private var session:         TranscriptionSession? = null
    private var recordingLease: UserDataJobs.Lease? = null
    // Черновик записи в Истории, пока идёт распознавание после «Стоп»:
    // каждый готовый кусок сразу дописывается туда (иначе в Истории минутами
    // висела запись без текста, хотя на экране записи он уже был).
    @Volatile private var draftTimestamp = 0L
    private var pendingAudioFile: java.io.File?       = null

    @Volatile private var isRecording  = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioSaver:  AudioRecordingSaver? = null
    // Сигнал «capture-поток вышел из цикла и отпустил AudioRecord/AudioSaver».
    // stopRecording обязан дождаться этого перед saver.stop(), иначе получим
    // либо NPE от MediaCodec (feed после stop), либо use-after-free.
    @Volatile private var captureFinished: CountDownLatch? = null

    // false = авто (петличка если подключена, иначе телефон)
    // true  = принудительно встроенный микрофон телефона
    private var usePhoneMic = false

    private val llmHelper: LlmHelper?
        get() = (activity as? MainActivity)?.llmHelper

    private lateinit var anim: RecordAnimations

    // Аудиофокус: предупреждаем, если микрофон перехватил звонок или другое приложение.
    private val audioFocus = RecordingAudioFocus(
        onLost = {
            if (isRecording) activity?.runOnUiThread {
                if (isAdded) startStatusPulse("⚠ микрофон занят — возможна потеря звука")
            }
        },
        onRegained = {
            if (isRecording) activity?.runOnUiThread {
                if (isAdded) stopStatusPulse("запись идёт")
            }
        },
    )

    // Modern permission API. Флаг micPermRequested — чтобы отличить первый
    // отказ (shouldShowRationale=false, но система ещё покажет диалог) от
    // "Don't ask again" (shouldShowRationale=false после хотя бы одного отказа).
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val prefs = requireContext().getSharedPreferences("guzlikvoice_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("mic_perm_requested", true).apply()
        if (granted) {
            startRecordingInternal()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Отказ + "больше не спрашивать" (или сразу denied на API 30+)
            showPermissionDeniedPermanentlyDialog()
        } else {
            tvVoskStatus.text = "нужен доступ к микрофону"
        }
    }
    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnVosk        = view.findViewById(R.id.btnVosk)
        tvVoskStatus   = view.findViewById(R.id.tvVoskStatus)
        tvModelBadge   = view.findViewById(R.id.tvModelBadge)
        tvTopicChip    = view.findViewById(R.id.tvTopicChip)
        tvMicStatus    = view.findViewById(R.id.tvBtStatus)
        dotMicStatus   = view.findViewById(R.id.dotBtStatus)
        tvRecordHint   = view.findViewById(R.id.tvRecordHint)
        pulseRing      = view.findViewById(R.id.pulseRing)
        anim           = RecordAnimations(pulseRing, btnVosk, tvVoskStatus)
        cardResult     = view.findViewById(R.id.cardResult)
        tvResult       = view.findViewById(R.id.tvResult)
        tvResultMeta   = view.findViewById(R.id.tvResultMeta)
        cardAnalysis   = view.findViewById(R.id.cardAnalysis)
        tvKeyIdeas     = view.findViewById(R.id.tvKeyIdeas)
        tvAnalysisMeta = view.findViewById(R.id.tvAnalysisMeta)
        tvAnalysisTags = view.findViewById(R.id.tvAnalysisTags)
        btnImproveText   = view.findViewById(R.id.btnImproveText)
        btnCopyAnalysis  = view.findViewById(R.id.btnCopyAnalysis)
        btnShareAnalysis = view.findViewById(R.id.btnShareAnalysis)
        btnMicSource     = view.findViewById(R.id.btnMicSource)

        // Fix 1: Timer
        tvRecordTimer = view.findViewById(R.id.tvRecordTimer)

        // Fix 2: Cancel processing
        btnCancelProcessing = view.findViewById(R.id.btnCancelProcessing)
        btnCancelProcessing.setOnClickListener {
            processingCancelled = true
            session?.cancel()
            btnCancelProcessing.isEnabled = false
            btnCancelProcessing.text = "отменяется..."
        }

        // Fix 3: Amplitude
        progressAmplitude = view.findViewById(R.id.progressAmplitude)
        waveform = view.findViewById(R.id.waveform)

        // Fix 4: Copy/Share transcript
        layoutTranscriptActions = view.findViewById(R.id.layoutTranscriptActions)
        btnCopyTranscript = view.findViewById(R.id.btnCopyTranscript)
        btnShareTranscript = view.findViewById(R.id.btnShareTranscript)
        btnCopyTranscript.setOnClickListener { TextShare.copy(requireContext(), "GuzlikVoice транскрипция", tvResult.text.toString()) }
        btnShareTranscript.setOnClickListener {
            TextShare.share(requireContext(), "GuzlikVoice — транскрипция", "Поделиться транскрипцией", tvResult.text.toString())
        }

        // LLM loading indicator
        llmLoadingIndicator = view.findViewById(R.id.llmLoadingIndicator)
        tvLlmLoadingText = view.findViewById(R.id.tvLlmLoadingText)

        btnVosk.isEnabled = false
        tvVoskStatus.text = "готовлю распознавание…"
        setMicStatusInactive()
        initWhisper()

        // Crash recovery: сохраняем текст прерванных сессий (у каждой свой WAL).
        val appCtxForRecovery = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            var restored = 0
            for (wal in TranscriptionSession.crashedWalFiles(appCtxForRecovery)) {
                val text = try { wal.readText().trim() } catch (_: Exception) { "" }
                try {
                    if (text.isNotEmpty()) {
                        RecordStorage.addToToday(appCtxForRecovery, TextFormatter.format(text), "voice",
                            null, "", linkPendingEvent = false)
                        restored++
                    }
                    wal.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "crash recovery failed: ${e.message}")
                }
            }
            if (restored > 0) withContext(Dispatchers.Main) {
                if (isAdded) android.widget.Toast.makeText(
                    requireContext(),
                    "Восстановлена незавершённая запись",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnVosk.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                confirmRecordingConsent { startRecording() }
            }
        }
        btnImproveText.setOnClickListener   { improveTextWithLlm() }
        btnCopyAnalysis.setOnClickListener  { TextShare.copy(requireContext(), "GuzlikVoice анализ", tvKeyIdeas.text.toString()) }
        btnShareAnalysis.setOnClickListener {
            TextShare.share(requireContext(), "GuzlikVoice — анализ дня", "Сохранить или поделиться", tvKeyIdeas.text.toString())
        }
        btnMicSource.setOnClickListener     { toggleMicSource() }
    }

    override fun onDestroyView() {
        if (isRecording) stopRecording()
        stopTimer()
        super.onDestroyView()
        stopPulse()
        stopStatusPulse()
        if (isRecording) RecordingService.stop(requireContext())
        isRecording = false
        releaseAudioFocus()
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        // Дать capture-потоку выйти до того как отпускаем saver/whisper/embedder.
        val latch = captureFinished
        captureFinished = null
        try { latch?.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        try { audioSaver?.stop() } catch (_: Exception) {}
        audioSaver = null
        // Если запись ещё обрабатывается (session != null), движки не трогаем:
        // иначе nativeFree прервал бы расшифровку посреди чанка. Обработка
        // доедет в фоне (RecordingService держит процесс), модель освободится
        // вместе с процессом или при следующем reloadWhisper.
        if (session == null) {
            whisperHelper?.release()
        }
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    private fun initWhisper(allowReuse: Boolean = true) {
        val appCtx = requireContext().applicationContext
        // Экран открыли заново, а прежний движок ещё жив (его держит фоновый импорт или
        // разделение по голосам) — берём его. Второй экземпляр перезагрузил бы общую
        // нативную модель посреди их работы.
        if (allowReuse) WhisperHelper.active?.takeIf { it.reuse() }?.let { existing ->
            whisperHelper = existing
            onWhisperReady(existing)
            return
        }
        val helper = WhisperHelper(appCtx)
        whisperHelper = helper
        helper.init(
            onReady = { onWhisperReady(helper) },
            onError = { msg ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    // Модель не загружена/повреждена — не даём записывать,
                    // предлагаем скачать вместо записи с ошибкой.
                    btnVosk.isEnabled = false
                    tvVoskStatus.text = "распознавание не запустилось"
                    tvRecordHint.text = "нужно скачать компонент для записи"
                    showDownloadRequiredPrompt()
                    Log.e(TAG, "Whisper init error: $msg")
                }
            }
        )
    }

    private fun onWhisperReady(helper: WhisperHelper) {
        (activity as? MainActivity)?.whisperHelper = helper
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            tvVoskStatus.text = "готово"
            tvModelBadge.text = "офлайн"
            tvModelBadge.visibility = View.VISIBLE
            btnVosk.isEnabled = true
        }
    }

    private fun showDownloadRequiredPrompt() {
        if (!isAdded) return
        if (BuildConfig.OFFLINE_PROTOTYPE) {
            tvRecordHint.text = "Установите модель из файла в настройках"
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Нужна загрузка")
            .setMessage("Компонент для распознавания речи не загружен. Без него запись работать не будет.")
            .setCancelable(false)
            .setPositiveButton("Скачать") { d, _ ->
                d.dismiss()
                startActivity(Intent(requireContext(), SetupActivity::class.java))
            }
            .show()
    }

    private fun showAnalysisDownloadPrompt() {
        if (!isAdded) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Нужна загрузка")
            .setMessage(LlmModels.selected(requireContext()).let { "ИИ-помощник ещё не скачан (${it.sizeLabel}). Скачать сейчас?" })
            .setNegativeButton("Позже", null)
            .setPositiveButton("Скачать") { d, _ ->
                d.dismiss()
                startActivity(
                    Intent(requireContext(), SetupActivity::class.java)
                        .putExtra(SetupActivity.EXTRA_AUTO_LLM, true)
                )
            }
            .show()
    }

    // ─── Timer (Fix 1) ────────────────────────────────────────────────────────

    private fun startTimer() {
        timerSeconds = 0
        tvRecordTimer.visibility = View.VISIBLE
        tvRecordTimer.text = "00:00"
        val r = object : Runnable {
            override fun run() {
                timerSeconds++
                val m = timerSeconds / 60
                val s = timerSeconds % 60
                tvRecordTimer.text = "%02d:%02d".format(m, s)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerRunnable = r
        timerHandler.postDelayed(r, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        if (::tvRecordTimer.isInitialized) tvRecordTimer.visibility = View.GONE
    }

    // ─── Recording consent ────────────────────────────────────────────────────

    // Перед первой записью показываем consent-диалог: в большинстве юрисдикций
    // запись разговоров без согласия участников — нарушение (GDPR, wiretapping
    // laws в США, ст. 137/138 УК РФ и т.д.). Одноразовое подтверждение
    // с чекбоксом "больше не спрашивать" — стандартный UX-паттерн.
    private fun confirmRecordingConsent(onConfirmed: () -> Unit) {
        val prefs = requireContext().getSharedPreferences("guzlikvoice_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("recording_consent_dont_ask", false)) {
            onConfirmed(); return
        }
        val view = layoutInflater.inflate(R.layout.dialog_recording_consent, null)
        val cb = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbDontAskAgain)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Согласие на запись")
            .setView(view)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подтверждаю") { _, _ ->
                if (cb.isChecked) prefs.edit().putBoolean("recording_consent_dont_ask", true).apply()
                onConfirmed()
            }
            .show()
    }

    // ─── Audio focus ──────────────────────────────────────────────────────────

    private fun requestAudioFocus(): Boolean = audioFocus.request(requireContext())

    private fun releaseAudioFocus() { context?.let { audioFocus.release(it) } }

    // ─── Recording ────────────────────────────────────────────────────────────

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecordingInternal()
            return
        }

        val prefs = requireContext().getSharedPreferences("guzlikvoice_prefs", Context.MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean("mic_perm_requested", false)

        when {
            // Уже отклоняли и rationale недоступна → "Don't ask again". Только Settings.
            alreadyAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showPermissionDeniedPermanentlyDialog()
            }
            // Android рекомендует показать объяснение перед повторным диалогом.
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showPermissionRationaleDialog()
            }
            // Первый запрос — сразу системный диалог.
            else -> {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun showPermissionRationaleDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Нужен доступ к микрофону")
            .setMessage("GuzlikVoice локально распознаёт речь на устройстве. Без разрешения на микрофон запись невозможна. Аудио никогда не покидает телефон.")
            .setNegativeButton("Не сейчас", null)
            .setPositiveButton("Разрешить") { _, _ ->
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .show()
    }

    private fun showPermissionDeniedPermanentlyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Разрешение отключено")
            .setMessage("Доступ к микрофону выключен в настройках приложения. Откройте настройки и включите его вручную, чтобы записывать голос.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть настройки") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun startRecordingInternal() {
        val helper = whisperHelper ?: return
        if (WearableService.snapshot.active) {
            Toast.makeText(requireContext(), "Сначала отключите внешний микрофон", Toast.LENGTH_SHORT).show()
            return
        }

        // Свободное место: m4a ~1 МБ/мин. При <50 МБ — warning с продолжением,
        // при <10 МБ — жёсткий блок (иначе AAC-encoder тихо навернётся).
        val freeBytes = requireContext().filesDir.freeSpace
        val freeMb = freeBytes / (1024L * 1024L)
        when {
            freeMb < 10 -> {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Недостаточно места")
                    .setMessage("Свободно всего $freeMb МБ. Освободите память в настройках устройства и попробуйте снова.")
                    .setPositiveButton("Понятно", null)
                    .show()
                return
            }
            freeMb < 50 -> {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Мало места")
                    .setMessage("Свободно $freeMb МБ — хватит на ~$freeMb минут записи. Продолжить?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Продолжить") { _, _ -> continueStartRecording(helper) }
                    .show()
                return
            }
        }
        continueStartRecording(helper)
    }

    private fun continueStartRecording(helper: WhisperHelper) {
        // Просим audio focus чтобы система знала: мы захватываем голос.
        // Если фокус занят чем-то критичным (активный звонок) — не начинаем.
        if (!requestAudioFocus()) {
            Toast.makeText(
                requireContext(),
                "Микрофон сейчас занят другим приложением",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val lease = UserDataJobs.begin() ?: run {
            releaseAudioFocus()
            Toast.makeText(requireContext(), "Дождитесь удаления данных", Toast.LENGTH_SHORT).show()
            return
        }
        if (!helper.retain()) {
            lease.close()
            releaseAudioFocus()
            return
        }
        recordingLease = lease
        lease.onCancel {
            timerHandler.post {
                processingCancelled = true
                session?.cancel()
                if (isRecording) stopRecording()
            }
        }
        isRecording = true

        draftTimestamp = 0L
        val appCtxForDraft = requireContext().applicationContext
        // «Мой словарь» из настроек — подсказка Whisper на всю запись.
        val glossaryPrompt = Glossary.buildPrompt(requireContext().applicationContext)

        val sess = TranscriptionSession(
            whisper  = helper,
            embedder = null,   // голоса делим только по кнопке «Разделить по голосам» в записи, не автоматически
            context  = requireContext().applicationContext,
            glossaryPrompt = glossaryPrompt,
            onChunkReady = { _, fullText ->
                val ts = draftTimestamp
                if (ts != 0L) {
                    try { RecordStorage.updateEntryText(appCtxForDraft, ts, TextFormatter.format(fullText)) }
                    catch (e: Exception) { Log.w(TAG, "draft text update failed: ${e.message}") }
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    showTranscript(fullText)
                }
            },
            onProcessingChanged = { active ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (isRecording) {
                        if (active) startStatusPulse("транскрибирую...")
                        else stopStatusPulse("запись идёт")
                    }
                }
            }
        )
        session = sess

        cardResult.visibility   = View.GONE
        cardAnalysis.visibility = View.GONE
        if (glossaryPrompt.isNotEmpty()) {
            tvTopicChip.text = "Словарь: " + glossaryPrompt.split(", ").size + " слов"
            tvTopicChip.visibility = View.VISIBLE
        } else {
            tvTopicChip.visibility = View.GONE
        }
        tvRecordHint.text       = "нажмите чтобы остановить"
        setRecordingUi(true)
        startPulse()
        startTimer()
        RecordingService.start(requireContext())

        val appCtx = requireContext().applicationContext
        val audioFile = java.io.File(
            appCtx.filesDir,
            "rec_${System.currentTimeMillis()}.m4a"
        )
        // Локальная ссылка — capture-поток не должен читать поле, которое может
        // занулиться из main. Field очищается в stopRecording только после того,
        // как capture-поток отсигналил captureFinished.
        val saverLocal = AudioRecordingSaver(audioFile).also { it.start() }
        audioSaver = saverLocal
        pendingAudioFile = audioFile

        val latch = CountDownLatch(1)
        captureFinished = latch

        thread(name = "mic-capture") {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, CHUNK_FRAMES * 2 * 4)

                @SuppressLint("MissingPermission")
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )

                // Принудительный встроенный микрофон если выбран режим "телефон"
                if (usePhoneMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        ?.let { ar.preferredDevice = it }
                }

                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    ar.release()
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        tvVoskStatus.text = "ошибка: не удалось открыть микрофон"
                        isRecording = false
                        releaseAudioFocus()
                        resetUi()
                    }
                    return@thread
                }

                audioRecord = ar
                ar.startRecording()

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    tvVoskStatus.text = "запись идёт"
                    setMicStatusActive()
                }

                val chunk = ShortArray(CHUNK_FRAMES)

                while (isRecording) {
                    val read = ar.read(chunk, 0, CHUNK_FRAMES)
                    if (read > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until read) {
                            val a = Math.abs(chunk[i].toInt())
                            if (a > maxAmplitude) maxAmplitude = a
                        }
                        val ampPct = (maxAmplitude * 100 / 32767).coerceIn(0, 100)
                        val ampFrac = maxAmplitude / 32767f
                        activity?.runOnUiThread {
                            if (isAdded) {
                                progressAmplitude.progress = ampPct
                                waveform.pushAmplitude(ampFrac)
                            }
                        }

                        val slice = chunk.copyOf(read)
                        // Всё аудио — в сессию: тишину отсекает Silero VAD внутри
                        // whisper.cpp с сохранением реальных таймкодов. Прежний
                        // энергетический гейт выкидывал паузы (таймкоды расходились
                        // с записью → диаризация и переход по тапу промахивались)
                        // и обрезал начало фраз после тишины.
                        sess.pushAudio(slice)
                        // saverLocal — снимок; поле audioSaver может уже быть занулено
                        // stopRecording'ом, но capture-поток по контракту продолжает
                        // работать со своей копией пока не выйдет из цикла.
                        try { saverLocal.feed(slice) } catch (_: Exception) {}
                    }
                }

                try { ar.stop() } catch (_: IllegalStateException) {}
                try { ar.release() } catch (_: Exception) {}
                audioRecord = null
            } finally {
                latch.countDown()
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        val lease = recordingLease
        recordingLease = null
        processingCancelled = lease?.cancelled == true
        isRecording = false
        setRecordingUi(false)
        stopPulse()
        stopTimer()
        releaseAudioFocus()
        val appCtx = requireContext().applicationContext
        val processingToken = RecordingService.processingStarted(appCtx)
        RecordingService.stop(appCtx)
        processingStartMs = System.currentTimeMillis()
        btnVosk.isEnabled = false
        tvVoskStatus.text = "финальная обработка..."
        setMicStatusInactive()
        btnCancelProcessing.visibility = View.VISIBLE
        btnCancelProcessing.isEnabled = true
        btnCancelProcessing.text = "Отменить обработку"

        val saver = audioSaver
        audioSaver = null
        val audioFile = pendingAudioFile
        pendingAudioFile = null
        val latch = captureFinished
        captureFinished = null
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        val sess = session
        val engine = whisperHelper
        // The application scope owns storage and cleanup; Fragment only displays the result.
        processingScope.launch {
            var saved = 0L
            var finalText = ""
            try {
                latch?.await(2, TimeUnit.SECONDS)
                val savedAudio = saver?.stop() ?: audioFile
                if (sess == null) return@launch
                if (processingCancelled || lease?.cancelled == true) {
                    sess.cancel()
                    return@launch
                }
                val path = savedAudio?.absolutePath.orEmpty()
                if (path.isNotEmpty()) {
                    saved = RecordStorage.addToToday(appCtx, TextFormatter.format(sess.currentText()), "voice", null, path)
                    draftTimestamp = saved
                }
                sess.flushAndWait(onRawReady = { raw ->
                    if (!processingCancelled && lease?.cancelled != true && saved != 0L && raw.isNotBlank())
                        RecordStorage.updateEntryText(appCtx, saved, TextFormatter.format(raw))
                }, onDone = { text, segments, diarization ->
                    if (!processingCancelled && lease?.cancelled != true) {
                        val warning = if (sess.failedChunks > 0)
                            "⚠ Расшифровка неполная: часть записи не распознана.\n\n" else ""
                        finalText = warning + TextFormatter.format(text)
                        if (saved == 0L && (finalText.isNotBlank() || path.isNotEmpty()))
                            saved = RecordStorage.addToToday(appCtx, finalText, "voice", null, path)
                        else if (saved != 0L) RecordStorage.updateEntryText(appCtx, saved, finalText)
                        if (saved != 0L) {
                            if (segments.isNotEmpty()) RecordStorage.updateEntrySegments(appCtx, saved, segments)
                            SpeakerCentroidStorage.putFromResult(appCtx, saved, diarization)
                            // Recovery is discarded only AFTER durable storage succeeded.
                            sess.clearWal()
                            ProcessingNotifier.notifyReady(appCtx, saved,
                                if (sess.failedChunks > 0) "Расшифровка неполная" else "Транскрипт готов")
                            if (sess.failedChunks == 0) AutoDiarization.start(appCtx, savedAudio, saved, segments)
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "record finalization failed; WAL retained", e)
            } finally {
                if (processingCancelled || lease?.cancelled == true) {
                    sess?.cancel()
                    if (saved != 0L) runCatching { RecordStorage.deleteByTimestamp(appCtx, saved) }
                        .onFailure { Log.e(TAG, "Could not remove cancelled draft", it) }
                    audioFile?.delete()
                }
                sess?.release()
                sess?.awaitStopped()
                try {
                    engine?.releaseUse()
                    if (processingCancelled || lease?.cancelled == true) sess?.clearWal()
                    if (session === sess) session = null
                    draftTimestamp = 0L
                } finally {
                    try { RecordingService.processingFinished(appCtx, processingToken) }
                    finally { lease?.close() }
                }
                timerHandler.post {
                    if (isAdded && view != null) {
                        stopStatusPulse()
                        btnCancelProcessing.visibility = View.GONE
                        if (finalText.isNotBlank()) showTranscript(finalText)
                        tvVoskStatus.text = if (processingCancelled) "отменено" else
                            if (saved != 0L) "сохранено · ${elapsedProcessingLabel()} с" else "не удалось сохранить"
                        resetUi()
                    }
                }
            }
        }
    }

    // ─── Save & Analyse ───────────────────────────────────────────────────────

    /** Сколько прошло с нажатия "стоп" до готового результата — видно, тянет ли железо модель. */
    private fun elapsedProcessingLabel(): String {
        val sec = (System.currentTimeMillis() - processingStartMs) / 1000.0
        return if (sec < 10) "%.1f".format(sec) else Math.round(sec).toString()
    }


    private fun toggleMicSource() {
        if (isRecording) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Источник звука")
            .setItems(arrayOf("Микрофон телефона", "Проводной / USB-микрофон (авто)", "Устройство GuzlikVoice по Bluetooth")) { _, which ->
                if (which == 2) startActivity(Intent(requireContext(), WearableActivity::class.java))
                else { usePhoneMic = which == 0; updateMicUi(active = false) }
            }.show()
    }

    private fun isWiredMicConnected(): Boolean {
        val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    /** Обновляет строку статуса микрофона и кнопку переключения. */
    private fun updateMicUi(active: Boolean) {
        val hasWired = isWiredMicConnected()

        // Если нет проводного микрофона — сбрасываем принудительный режим
        if (!hasWired) usePhoneMic = false

        val label = when {
            usePhoneMic         -> "микрофон телефона"
            hasWired            -> "петличка"
            else                -> "микрофон телефона"
        }
        tvMicStatus.text = if (active) "$label • активен" else label

        val dotColor = if (active)
            ContextCompat.getColor(requireContext(), R.color.accent)
        else
            ContextCompat.getColor(requireContext(), R.color.textMuted)
        dotMicStatus.background = ContextCompat.getDrawable(
            requireContext(), R.drawable.bg_calendar_dot
        )?.apply { setTint(dotColor) }

        // Кнопку показываем только когда есть проводной mic И не идёт запись
        if (!active) {
            btnMicSource.visibility = View.GONE
        } else {
            btnMicSource.visibility = View.GONE
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private fun showTranscript(text: String) {
        if (text.isBlank() || !isAdded) return
        cardResult.visibility = View.VISIBLE
        tvResult.text         = text
        tvResultMeta.text     = "${text.length} симв."
        // Fix 5: Always show the button — LLM will load on demand
        btnImproveText.visibility = View.VISIBLE
        btnImproveText.isEnabled  = true
        btnImproveText.text       = "✦ Выделить главное"
        // Fix 4: Show transcript actions
        layoutTranscriptActions.visibility = View.VISIBLE
    }

    // Fix 5: Lazy LLM loading
    private fun improveTextWithLlm() {
        val originalView=view ?: return
        val request=++improvementVersion
        val rawText = tvResult.text.toString()
        if (rawText.isBlank()) return

        val main = activity as? MainActivity
        val alreadyLoaded = main?.llmHelper?.isReady == true

        btnImproveText.isEnabled = false
        btnImproveText.text = if (alreadyLoaded) "подготовка..." else "загрузка ИИ..."

        // Показываем индикатор только когда модель ещё не в памяти — иначе
        // мельканий не будет.
        if (!alreadyLoaded) {
            llmLoadingIndicator.visibility = View.VISIBLE
            tvLlmLoadingText.text = "Подготавливаю ИИ-помощника…"
        }

        main?.ensureLlmLoaded {
            if (!isAdded || view !== originalView || improvementVersion!=request) return@ensureLlmLoaded
            val llm = (activity as? MainActivity)?.llmHelper
            if (isAdded) llmLoadingIndicator.visibility = View.GONE
            if (llm == null || !llm.isReady) {
                if (isAdded) {
                    btnImproveText.isEnabled = true
                    btnImproveText.text = "✦ Выделить главное"
                    tvVoskStatus.text = "компонент анализа не загружен"
                    showAnalysisDownloadPrompt()
                }
                return@ensureLlmLoaded
            }
            btnImproveText.text = "анализируется..."
            tvVoskStatus.text = "анализ..."

            val appCtx = requireContext().applicationContext
            if(!llm.retain()) {btnImproveText.isEnabled=true;btnImproveText.text="✦ Выделить главное";return@ensureLlmLoaded}
            val lease=UserDataJobs.begin() ?: run{llm.releaseUse();btnImproveText.isEnabled=true;btnImproveText.text="✦ Выделить главное";return@ensureLlmLoaded}
            val processingToken = try{RecordingService.processingStarted(appCtx)}catch(e:Exception){
                lease.close();llm.releaseUse();btnImproveText.isEnabled=true;btnImproveText.text="✦ Выделить главное"
                Toast.makeText(appCtx,ErrorMessages.humanize(e),Toast.LENGTH_LONG).show();return@ensureLlmLoaded
            }
            val job=lifecycleScope.launch(Dispatchers.IO) {
                try {
                    lease.check()
                    val result = llm.structure(rawText)
                    lease.check()
                    withContext(Dispatchers.Main) {
                        if (!isAdded || view!==originalView || improvementVersion!=request) return@withContext
                        btnImproveText.text      = "✓ готово"
                        btnImproveText.isEnabled = false
                        tvVoskStatus.text        = "готово"
                        showAnalysis(result)
                    }
                } catch(e:kotlinx.coroutines.CancellationException){
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!isAdded || view!==originalView || improvementVersion!=request) return@withContext
                        btnImproveText.isEnabled = true
                        btnImproveText.text      = "✦ Выделить главное"
                        tvVoskStatus.text        = "анализ не удался"
                        Toast.makeText(requireContext(), ErrorMessages.humanize(e), Toast.LENGTH_LONG).show()
                    }
                }
            }
            lease.onCancel{llm.cancel();job.cancel()}
            job.invokeOnCompletion {
                try{RecordingService.processingFinished(appCtx,processingToken)}finally{try{lease.close()}finally{llm.releaseUse()}}
                if(job.isCancelled)timerHandler.post {
                    if(isAdded && view===originalView && improvementVersion==request) {
                        btnImproveText.isEnabled=true;btnImproveText.text="✦ Выделить главное"
                    }
                }
            }
        }
    }

    private fun showAnalysis(text: String) {
        if (text.isBlank() || !isAdded) return
        cardAnalysis.visibility   = View.VISIBLE
        tvKeyIdeas.text           = text
        tvAnalysisMeta.text       = ""
        tvAnalysisTags.visibility = View.GONE
        btnCopyAnalysis.visibility  = View.VISIBLE
        btnShareAnalysis.visibility = View.VISIBLE
    }

    private fun setRecordingUi(recording: Boolean) {
        val ctx = requireContext()
        if (recording) {
            btnVosk.setIconResource(R.drawable.ic_stop)
            btnVosk.background = ContextCompat.getDrawable(ctx, R.drawable.bg_record_button_rec)
            btnVosk.app_iconTint(ctx, android.R.color.white)
        } else {
            btnVosk.setIconResource(R.drawable.ic_mic)
            btnVosk.background = ContextCompat.getDrawable(ctx, R.drawable.bg_record_button)
            btnVosk.app_iconTint(ctx, R.color.surface)
        }
        btnVosk.isEnabled = true
        // Fix 3: Amplitude visibility
        progressAmplitude.visibility = if (recording) View.VISIBLE else View.GONE
        waveform.visibility = if (recording) View.VISIBLE else View.GONE
        if (!recording) {
            progressAmplitude.progress = 0
            waveform.clear()
        }
    }

    private fun setMicStatusActive()   { updateMicUi(active = true)  }
    private fun setMicStatusInactive() { updateMicUi(active = false) }

    private fun MaterialButton.app_iconTint(ctx: android.content.Context, colorRes: Int) {
        iconTint = ContextCompat.getColorStateList(ctx, colorRes)
    }

    private fun resetUi() {
        isRecording = false
        btnVosk.isEnabled      = true
        btnMicSource.isEnabled = true
        tvRecordHint.text      = "нажмите чтобы начать"
        setRecordingUi(false)
        setMicStatusInactive()
        stopPulse()
        stopStatusPulse()
        stopTimer()
        if (::btnCancelProcessing.isInitialized) btnCancelProcessing.visibility = View.GONE
        if (::layoutTranscriptActions.isInitialized) {
            // keep transcript actions visible if transcript card is visible
        }
    }

    // ─── Pulse animation ──────────────────────────────────────────────────────

    private fun startPulse() = anim.startPulse()
    private fun stopPulse() = anim.stopPulse()
    private fun startStatusPulse(text: String) = anim.startStatusPulse(text)
    private fun stopStatusPulse(finalText: String = "") = anim.stopStatusPulse(finalText)

    // Вызывается из MainActivity когда пользователь изменил настройки
    fun reloadWhisper() {
        if (isRecording || !isAdded) return
        if (WearableService.snapshot.active) {
            Toast.makeText(requireContext(), "Новая модель применится после отключения устройства", Toast.LENGTH_LONG).show()
            return
        }
        whisperHelper?.release()
        whisperHelper = null
        // activity? — SettingsSheet.dismiss() может успеть детачить фрагмент
        // до колбэка, тогда requireActivity() кинет IllegalStateException.
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            btnVosk.isEnabled = false
            tvVoskStatus.text = "готовлю распознавание…"
        }
        initWhisper(allowReuse = false)   // сменили модель в настройках — грузим новую, а не берём живую старую
    }
}
