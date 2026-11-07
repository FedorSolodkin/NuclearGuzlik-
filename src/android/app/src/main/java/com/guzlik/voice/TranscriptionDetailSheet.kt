package com.guzlik.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class TranscriptionDetailSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG            = "TranscriptionDetail"
        private const val ARG_TIMESTAMP  = "timestamp"
        private const val ARG_AUDIO_PATH = "audioPath"
        private const val ARG_DATE       = "date"
        private const val ARG_ENCRYPTED  = "encrypted"
        /** Порог уверенности слова для подсветки (см. highlightDoubtfulWords). */
        private const val DOUBT_P = 0.4f

        /**
         * В аргументах — только ключ записи. Текст и фразы со словами читаются из базы:
         * аргументы фрагмента Android сохраняет при уходе в фон, и у часовой записи они
         * весили ~0,9 МБ — приложение падало с TransactionTooLargeException (лимит ~1 МБ).
         */
        fun newInstance(log: LogEntry, date: String) = TranscriptionDetailSheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_TIMESTAMP, log.timestamp)
                putString(ARG_AUDIO_PATH, log.audioPath)
                putString(ARG_DATE, date)
                putBoolean(ARG_ENCRYPTED, log.isEncrypted)
            }
        }
    }

    private val exporter = EntryExporter(this)

    private val audioPlayer = DetailAudioPlayer()
    private var playableAudioFile: File? = null
    private val protection = EntryProtectionUi(this,
        onUnlocked = { reopen() },
        beforeChange = { audioPlayer.release() },
        onChanged = { if (isAdded) dismiss() })

    // Подсветка последней фразы, на которую тыкнули в режиме тайм-кодов.
    private var lastTapSpan: BackgroundColorSpan? = null
    private var entrySnapshot: DailyRecord.Entry? = null
    private var entrySnapshotLoaded = false
    private fun invalidateEntrySnapshot() { entrySnapshot = null; entrySnapshotLoaded = false }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_transcription_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        invalidateEntrySnapshot()
        playableAudioFile = null

        val timestamp  = arguments?.getLong(ARG_TIMESTAMP) ?: System.currentTimeMillis()
        // Одна запись по ключу — доли миллисекунды (Room разрешает это в главном потоке).
        val storedText = storedEntry()?.text ?: ""
        val audioPath  = arguments?.getString(ARG_AUDIO_PATH) ?: ""
        val date       = arguments?.getString(ARG_DATE)
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        val isEncrypted = arguments?.getBoolean(ARG_ENCRYPTED, false) ?: false

        val tvText    = view.findViewById<TextView>(R.id.tvDetailText)
        val btnDiarize = view.findViewById<MaterialButton>(R.id.btnDiarize)

        val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val fmtDate = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

        view.findViewById<TextView>(R.id.tvDetailTime).text  = fmtTime.format(Date(timestamp))
        view.findViewById<TextView>(R.id.tvDetailDate).text  = fmtDate.format(Date(timestamp))

        // Определяем эффективный текст для отображения:
        // - обычная запись: text из storage
        // - защищённая + vault разблокирован: расшифровываем на лету
        // - защищённая + vault заперт: placeholder и кнопка разблокировки
        val vaultPayload = if (isEncrypted && VaultSession.isUnlocked())
            RecordStorage.readDecryptedPayload(requireContext(), timestamp) else null
        val effectiveText = if (isEncrypted) {
            vaultPayload?.get("text").orEmpty().ifEmpty { RecordStorage.ENCRYPTED_PLACEHOLDER }
        } else {
            storedText
        }

        view.findViewById<TextView>(R.id.tvDetailChars).text = "${effectiveText.length} симв."
        applyTranscriptStyle(tvText, effectiveText, timestamp)

        if (!isEncrypted) {
            setupAudioFeatures(view, audioPath.takeIf { it.isNotBlank() }?.let(::File),
                effectiveText, timestamp, date, tvText, btnDiarize)
        } else if (vaultPayload != null) {
            // Аудио защищённой записи расшифровывается во временный файл (cacheDir),
            // который стирается при блокировке vault.
            val ext = vaultPayload["audioPath"].orEmpty().substringAfterLast('.', "m4a")
            val appCtx = requireContext().applicationContext
            setupPlayer(view, "")
            setupAudioSync(view, null, tvText, null)
            viewLifecycleOwner.lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) {
                    try { VaultManager.playableAudio(appCtx, timestamp, ext) }
                    catch (e: Exception) { VaultManager.logFailure("playableAudio", e); null }
                }
                if (isAdded && getView() === view) {
                    setupAudioFeatures(view, file, effectiveText, timestamp, date, tvText, btnDiarize)
                }
            }
        } else {
            setupPlayer(view, "")
            setupAudioSync(view, null, tvText, null)
        }

        setupActionsToggle(view)
        setupCalendarLink(view, date, timestamp)
        setupClientLink(view, date, timestamp)
        setupProtocolButton(view, date, timestamp, tvText)
        setupQaButton(view)
        setupNameSpeakersButton(view, timestamp, tvText)
        setupExportButton(view, timestamp)
        protection.bindUnlock(view, isEncrypted, timestamp)
        protection.bindProtect(view, isEncrypted, date, timestamp)
        setupDeleteButton(view, date, timestamp)

        val btnEdit = view.findViewById<MaterialButton>(R.id.btnDetailEdit)
        // Зашифрованную запись не редактируем: updateEntryText записал бы открытый
        // текст в records.json в обход vault.
        btnEdit.visibility = if (isEncrypted) View.GONE else View.VISIBLE
        btnEdit.setOnClickListener {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
            val current = RecordStorage.getEntry(requireContext(), date, timestamp)?.text ?: tvText.text.toString()
            showEditDialog(current) { edited ->
                RecordStorage.updateEntryText(requireContext().applicationContext, timestamp, edited)
                invalidateEntrySnapshot()
                view.findViewById<TextView>(R.id.tvDetailChars).text = "${edited.length} симв."
                applyTranscriptStyle(tvText, edited, timestamp)
                Toast.makeText(requireContext(), "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<MaterialButton>(R.id.btnDetailCopy).setOnClickListener {
            val currentText = tvText.text.toString()
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@setOnClickListener
            cb.setPrimaryClip(ClipData.newPlainText("transcription", currentText))
            Toast.makeText(requireContext(), "Скопировано", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDetailSave).setOnClickListener {
            val currentText = tvText.text.toString()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, currentText)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "GuzlikVoice")
            }
            startActivity(android.content.Intent.createChooser(intent, "Поделиться записью"))
        }

        dialog?.setOnShowListener { shown ->
            val bottomSheet = (shown as? android.app.Dialog)?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    // ── Сворачиваемый блок «Функции записи» ───────────────────────────────────

    /** Функции записи свёрнуты по умолчанию, чтобы текст занимал всю карточку. Состояние запоминается. */
    private fun setupActionsToggle(view: View) {
        val prefs = requireContext().getSharedPreferences("detail_sheet_ui", Context.MODE_PRIVATE)
        val block = view.findViewById<View>(R.id.layoutRecordActions)
        val chevron = view.findViewById<TextView>(R.id.tvActionsChevron)
        fun apply(expanded: Boolean) {
            block.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.text = if (expanded) "▾" else "▸"
        }
        apply(prefs.getBoolean("actions_expanded", false))
        view.findViewById<View>(R.id.rowActionsToggle).setOnClickListener {
            val expanded = block.visibility != View.VISIBLE
            prefs.edit().putBoolean("actions_expanded", expanded).apply()
            apply(expanded)
        }
    }

    // ── Редактирование текста ────────────────────────────────────────────────

    private fun showEditDialog(text: String, onSave: (String) -> Unit) {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(ctx).apply {
            setText(text)
            setSelection(text.length)
            minLines = 6
            maxLines = 18
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 15f
        }
        val container = android.widget.FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Изменить текст")
            .setMessage("Метки вида [Говорящий А] можно оставить или исправить вручную.")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val edited = input.text.toString().trim()
                if (edited.isNotEmpty() && edited != text) onSave(edited)
            }
            .show()
    }

    // ── Calendar link (auto by time) ──────────────────────────────────────────

    private fun setupCalendarLink(view: View, date: String, timestamp: Long) {
        // Хакатон: календарь не используется.
        view.findViewById<MaterialCardView>(R.id.cardCalendarLink).visibility = View.GONE
    }

    // ── Client link (Pro) ─────────────────────────────────────────────────────

    private fun setupClientLink(view: View, date: String, timestamp: Long) {
        // Хакатон: клиенты не используются.
        view.findViewById<MaterialCardView>(R.id.cardClientLink).visibility = View.GONE
    }

    private fun renderClientLink(tvName: TextView, date: String, timestamp: Long) {
        val appCtx = requireContext().applicationContext
        val entry  = RecordStorage.getEntry(appCtx, date, timestamp)
        val clientId = entry?.clientId
        if (clientId == null) {
            tvName.text = "Не привязан"
        } else {
            val client = ClientStorage.getById(appCtx, clientId)
            tvName.text = client?.name ?: "(удалённый клиент)"
        }
    }

    // ── Voice Profiles: именование говорящих (Pro) ────────────────────────────

    private fun setupNameSpeakersButton(view: View, timestamp: Long, tvText: TextView) {
        val btn = view.findViewById<MaterialButton>(R.id.btnNameSpeakers)
        val appCtx = requireContext().applicationContext
        val snap = SpeakerCentroidStorage.get(appCtx, timestamp)
        btn.visibility = if (snap != null && snap.centroids.isNotEmpty()) View.VISIBLE else View.GONE
        parentFragmentManager.setFragmentResultListener(NameSpeakersSheet.resultKey(timestamp),viewLifecycleOwner) { _,_ ->
            invalidateEntrySnapshot()
            storedEntry(refresh=true)?.takeIf{!it.isEncrypted}?.let{applyTranscriptStyle(tvText,it.text,timestamp)}
        }
        btn.setOnClickListener {
            if (!ProFeatures.requirePro(requireContext(), ProFeatures.Feature.VOICE_PROFILES)) return@setOnClickListener
            NameSpeakersSheet.newInstance(timestamp).show(parentFragmentManager, "name_speakers")
        }
    }

    /**
     * Заменяет `[Говорящий X]` в тексте на цветные округлые чипы через SpannableString.
     * Цвет чипа связан с индексом спикера в снапшоте — те же цвета видны в NameSpeakersSheet
     * (аватар + чип одного цвета). Если снапшота нет — рендерим текст без чипов.
     */
    private fun applyTranscriptStyle(tvText: TextView, text: String, timestamp: Long) {
        val snap = SpeakerCentroidStorage.get(requireContext().applicationContext, timestamp)
        if (snap == null || snap.labels.isEmpty()) {
            tvText.text = text
        } else {
            val labelToIndex = snap.labels.mapIndexed { i, name -> name to i }.toMap()
            tvText.text = SpeakerColors.renderWithChips(text, labelToIndex, tvText)
        }
        highlightDoubtfulWords(tvText)
        setupSpeakerCorrection(tvText, timestamp)
        view?.let { currentView ->
            currentView.findViewById<TextView>(R.id.tvDetailChars)?.text = "${text.length} симв."
            setupAudioSync(currentView,playableAudioFile,tvText,parseStoredSegments())
        }
    }

    /** Long-press a passage → «Говорящий»: edit just that passage without inference. */
    private fun setupSpeakerCorrection(tv: TextView, timestamp: Long) {
        if (storedEntry()?.isEncrypted != false) return
        tv.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, 0x564d, 0, "Говорящий").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if (item.itemId != 0x564d) return false
                val from = minOf(tv.selectionStart, tv.selectionEnd)
                val to = maxOf(tv.selectionStart, tv.selectionEnd)
                val original = tv.text.toString()
                mode.finish()
                if (from < 0 || to <= from) return true
                if (DiarizationJobRunner.stateOf(timestamp) is DiarizationJobRunner.State.Running) {
                    Toast.makeText(requireContext(), "Дождитесь окончания разделения", Toast.LENGTH_SHORT).show(); return true
                }
                val labels = SpeakerCentroidStorage.get(requireContext(), timestamp)?.labels.orEmpty()
                val headers = labels.map { "[Говорящий $it]" } + listOf("[Говорящий не определён]", "[Говорят одновременно]")
                val titles = labels.map { "Говорящий $it" } + listOf("Говорящий не определён", "Говорят одновременно")
                androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Кто говорит в выделенной фразе?")
                    .setItems(titles.toTypedArray()) { _, which ->
                        if (storedEntry(refresh = true)?.text != original || DiarizationJobRunner.stateOf(timestamp) is DiarizationJobRunner.State.Running) {
                            Toast.makeText(requireContext(), "Запись изменилась. Выделите фразу заново", Toast.LENGTH_SHORT).show()
                        } else try {
                            val corrected = SpeakerTextEdits.relabel(original, from, to, headers[which])
                            RecordStorage.updateEntryText(requireContext(), timestamp, corrected)
                            invalidateEntrySnapshot()
                            applyTranscriptStyle(tv, corrected, timestamp)
                            view?.findViewById<TextView>(R.id.tvDetailChars)?.text = "${corrected.length} симв."
                        } catch (_: IllegalArgumentException) {
                            Toast.makeText(requireContext(), "Выделите текст фразы", Toast.LENGTH_SHORT).show()
                        }
                    }.setNegativeButton("Отмена", null).show()
                return true
            }
        }
    }

    /**
     * Подсвечивает слова, в которых модель не уверена (уверенность < [DOUBT_P]).
     * Замер на 100 фразах Golos (turbo q4_0): при пороге 0.4 подсвечивается ~5%
     * слов, из них ~60% действительно ошибочны, и ловится ~половина всех ошибок.
     * Нажатие на подсвеченное слово проигрывает это место записи.
     *
     * Слова ищутся в показанном тексте по порядку: после ручной правки
     * исправленное слово просто не найдётся и подсветка с него снимется.
     */
    private fun highlightDoubtfulWords(tvText: TextView) {
        // Выравниваем по тексту ВСЕ слова записи, а не только сомнительные: иначе короткое слово находилось
        // внутри чужого и подсвечивалось не то место (см. DoubtfulWords).
        val allWords = parseStoredSegments()?.flatMap { it.words.orEmpty() } ?: return
        if (allWords.none { it.p < DOUBT_P }) return
        val sb = SpannableStringBuilder(tvText.text)
        val marks = DoubtfulWords.align(sb.toString(), allWords, DOUBT_P)
        val hint = view?.findViewById<TextView>(R.id.tvDoubtHint)
        if (marks.isEmpty()) { hint?.visibility = View.GONE; return }
        val color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            ContextCompat.getColor(requireContext(), R.color.accentRed), 70)
        for (m in marks) {
            sb.setSpan(BackgroundColorSpan(color), m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val startMs = m.startMs
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { seekAndPlay(startMs) }
                override fun updateDrawState(ds: android.text.TextPaint) { /* вид обычного текста */ }
            }, m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val marked = marks.size
        tvText.text = sb
        enableSpanClicks(tvText)
        hint?.text = "Красным — $marked ${plural(marked, "слово", "слова", "слов")}, в которых распознавание " +
            "не уверено. Нажмите, чтобы послушать это место."
        hint?.visibility = View.VISIBLE
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    /**
     * Нажатия по ClickableSpan без LinkMovementMethod: он внутри ScrollView
     * перехватывает вертикальный жест и ломает прокрутку длинного текста.
     */
    private fun enableSpanClicks(tvText: TextView) {
        tvText.setOnTouchListener { v, event ->
            val tv = v as TextView
            if (tv.hasSelection()) return@setOnTouchListener false
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val text = tv.text
                val layout = tv.layout
                if (text is Spannable && layout != null) {
                    val x = event.x.toInt() - tv.totalPaddingLeft + tv.scrollX
                    val y = event.y.toInt() - tv.totalPaddingTop + tv.scrollY
                    val off = layout.getOffsetForHorizontal(layout.getLineForVertical(y), x.toFloat())
                    val spans = text.getSpans(off, off, ClickableSpan::class.java)
                    if (spans.isNotEmpty()) { spans[0].onClick(tv); return@setOnTouchListener true }
                }
            }
            false
        }
    }

    // ── Тайм-коды: тап по фразе → перемотка аудио на её начало ─────────────────
    //
    // Быстрый путь: сегменты (startMs/endMs/text) уже посчитаны Whisper во время
    // самой записи (TranscriptionSession.allSegments) и сохранены в Entry.segments —
    // ничего заново распознавать не надо, текст строится мгновенно.
    // Медленный путь (только для старых записей, сделанных до этой фичи, у которых
    // segments нет): декодируем сохранённый файл и прогоняем через
    // whisper.transcribeSegments(), как раньше.
    //
    // Ссылки добавляются к совпавшим словам текущего текста. Ручные правки,
    // имена и метки говорящих остаются на экране и в последующих действиях.
    private fun setupAudioSync(
        view: View, audioFile: File?, tvText: TextView, storedSegments: List<WhisperHelper.Segment>?
    ) {
        val btn = view.findViewById<MaterialButton>(R.id.btnSyncAudio)
        if (audioFile == null) { btn.visibility = View.GONE; return }
        btn.visibility = View.VISIBLE

        btn.setOnClickListener {
            if (!storedSegments.isNullOrEmpty()) {
                if(showTappableTranscript(tvText, storedSegments))btn.visibility = View.GONE
                return@setOnClickListener
            }

            val appCtx = requireContext().applicationContext
            val whisper = (activity as? MainActivity)?.whisperHelper
            if (whisper == null || !whisper.isReady) {
                Toast.makeText(appCtx, "Сначала откройте вкладку «Запись»: распознавание ещё запускается", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btn.isEnabled = false
            btn.text = "Готовлю тайм-коды..."

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jobContext=kotlinx.coroutines.currentCoroutineContext()
                    val segments = StoredAudioTranscriber.transcribe(appCtx,audioFile,whisper) { !jobContext.isActive }
                    if(segments.isNotEmpty())RecordStorage.updateEntrySegments(appCtx,requireArguments().getLong(ARG_TIMESTAMP),segments)
                    if (segments.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (!isAdded || getView() !== view) return@withContext
                            Toast.makeText(appCtx, "Речь не распознана", Toast.LENGTH_SHORT).show()
                            btn.isEnabled = true
                            btn.text = "Тайм-коды: тыкать и слушать"
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        if (!isAdded || getView() !== view) return@withContext
                        invalidateEntrySnapshot()
                        btn.isEnabled = true
                        btn.text = "Тайм-коды: тыкать и слушать"
                        setupAudioSync(view,audioFile,tvText,segments)
                        if(showTappableTranscript(tvText, segments))btn.visibility = View.GONE
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Audio sync failed: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        if (!isAdded || getView() !== view) return@withContext
                        Toast.makeText(appCtx, ErrorMessages.humanize(e), Toast.LENGTH_LONG).show()
                        btn.isEnabled = true
                        btn.text = "Тайм-коды: тыкать и слушать"
                    }
                }
            }
        }
    }

    private fun showTappableTranscript(tvText: TextView, segments: List<WhisperHelper.Segment>):Boolean {
        val timed=buildTappableTranscript(tvText.text, segments)
        if(timed.getSpans(0,timed.length,ClickableSpan::class.java).isEmpty()) {
            Toast.makeText(tvText.context,"Тайм-коды не совпали с изменённым текстом. Ваши правки сохранены",Toast.LENGTH_LONG).show()
            return false
        }
        tvText.text = timed
        // ВАЖНО: не ставим LinkMovementMethod — на TextView внутри ScrollView он
        // перехватывает вертикальный drag целиком (даже мимо ссылок) и ломает скролл
        // длинного текста. Вместо этого сами разбираем только ACTION_UP: если тап
        // попал на спан — кликаем по нему, иначе отдаём событие дальше как обычно,
        // и ScrollView скроллится как ни в чём не бывало.
        tvText.setOnTouchListener { v, event ->
            val tv = v as TextView
            if (tv.hasSelection()) return@setOnTouchListener false
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val text = tv.text
                if (text is Spannable) {
                    val x = event.x.toInt() - tv.totalPaddingLeft + tv.scrollX
                    val y = event.y.toInt() - tv.totalPaddingTop + tv.scrollY
                    val layout = tv.layout
                    if (layout != null) {
                        val line = layout.getLineForVertical(y)
                        val off = layout.getOffsetForHorizontal(line, x.toFloat())
                        val spans = text.getSpans(off, off, ClickableSpan::class.java)
                        if (spans.isNotEmpty()) {
                            spans[0].onClick(tv)
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }
        Toast.makeText(tvText.context, "Нажмите на любую фразу, чтобы услышать её в записи", Toast.LENGTH_LONG).show()
        return true
    }

    private fun buildTappableTranscript(current: CharSequence, segments: List<WhisperHelper.Segment>): SpannableStringBuilder {
        val sb = SpannableStringBuilder(current)
        sb.getSpans(0,sb.length,ClickableSpan::class.java).forEach { sb.removeSpan(it) }
        for (mark in TranscriptTiming.align(current.toString(),segments)) {
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val tv = widget as? TextView ?: return
                    highlightRange(tv,mark.start,mark.end)
                    seekAndPlay(mark.startMs)
                }
                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
            },mark.start,mark.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun highlightRange(tv: TextView, start: Int, end: Int) {
        val spannable = tv.text as? Spannable ?: return
        lastTapSpan?.let { spannable.removeSpan(it) }
        val accent = ContextCompat.getColor(tv.context, R.color.accent)
        val span = BackgroundColorSpan(ColorUtils.setAlphaComponent(accent, 60))
        spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        lastTapSpan = span
        tv.invalidate()
    }

    private fun seekAndPlay(startMs: Long) = audioPlayer.seekAndPlay(startMs)

    // ── Q&A по записи (Pro, локальная Gemma) ──────────────────────────────────

    /** Протокол совещания (Gemma, шаблон «Встреча»). Текст берём с экрана — уже с именами говорящих. */
    private fun setupProtocolButton(view: View, date: String, timestamp: Long, tvText: TextView) {
        val btn = view.findViewById<MaterialButton>(R.id.btnProtocol)
        val saved = RecordStorage.getEntry(requireContext(), date, timestamp)
            ?.takeIf { it.templateId == LlmHelper.Template.MEETING.id }?.templateResult
        if (!saved.isNullOrBlank()) btn.text = "Открыть протокол совещания"
        btn.setOnClickListener {
            val transcript = currentTranscript()
            if (transcript.isBlank()) {
                Toast.makeText(requireContext(), "Пустая запись", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val existing = RecordStorage.getEntry(requireContext(), date, timestamp)
                ?.takeIf { it.templateId == LlmHelper.Template.MEETING.id }?.templateResult
            ProtocolSheet.newInstance(timestamp, transcript, existing).show(parentFragmentManager, "protocol_sheet")
        }
    }

    private fun setupQaButton(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.btnAskQuestion)
        val timestamp = arguments?.getLong(ARG_TIMESTAMP) ?: 0L
        btn.setOnClickListener {
            if (!ProFeatures.requirePro(requireContext(), ProFeatures.Feature.QA_CHAT)) return@setOnClickListener
            // Дообученная Qwen умеет только «Выделить главное».
            if (!LlmModels.selected(requireContext()).supportsQa) {
                Toast.makeText(requireContext(), "Вопросы к записи недоступны в этой версии", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val transcript = currentTranscript()
            if (transcript.isBlank()) {
                Toast.makeText(requireContext(), "Пустая запись", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            QaSheet.newInstance(timestamp.toString(), transcript)
                .show(parentFragmentManager, "qa_sheet")
        }
    }

    /** Плеер, разделение по голосам и тайм-коды — всё, что требует аудиофайла. */
    private fun setupAudioFeatures(
        view: View, audioFile: File?, effectiveText: String, timestamp: Long, date: String,
        tvText: TextView, btnDiarize: MaterialButton,
    ) {
        val file = audioFile?.takeIf { it.exists() }
        playableAudioFile = file
        setupPlayer(view, file?.absolutePath.orEmpty())
        if (file == null) { setupAudioSync(view, null, tvText, null); return }

        // Кнопка разделения по голосам: если метки уже есть, это «Разделить заново»
        // (например, с другим числом людей).
        btnDiarize.visibility = View.VISIBLE
        val labeled = (effectiveText.contains("[Говорящий") || effectiveText.contains("[Говорят одновременно]"))
        hasSpeakerLabels = labeled
        btnDiarize.text = if (labeled) "Разделить заново" else "Разделить по голосам"
        btnDiarize.setOnClickListener {
            askSpeakerCount(alreadyLabeled = hasSpeakerLabels) { count ->
                runDiarization(file, timestamp, count, parseStoredSegments())
            }
        }
        // Задача могла начаться раньше и идти (или закончиться) без этого листа: показываем её состояние.
        bindDiarization(view, timestamp, tvText, btnDiarize, labeled)
        setupAudioSync(view, file, tvText, parseStoredSegments())
    }

    // ── Защита записи (vault) ─────────────────────────────────────────────────

    private fun reopen() {
        val args = arguments ?: return
        val fm = parentFragmentManager
        dismiss()
        TranscriptionDetailSheet().apply { arguments = Bundle(args) }.show(fm, tag)
    }

    private fun setupDeleteButton(view: View, date: String, timestamp: Long) {
        val btn = view.findViewById<android.widget.ImageButton>(R.id.btnDetailDelete)
        btn.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Удалить запись?")
                .setMessage("Аудио и текст будут удалены безвозвратно.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ ->
                    val appCtx = requireContext().applicationContext
                    lifecycleScope.launch(Dispatchers.IO) {
                        RecordStorage.deleteEntry(appCtx, date, timestamp)
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            Toast.makeText(appCtx, "Запись удалена", Toast.LENGTH_SHORT).show()
                            dismiss()
                        }
                    }
                }
                .show()
        }
    }

    // ── Exports (SRT / PDF / Word) ────────────────────────────────────────────

    private fun currentTranscript(): String {
        val entry = storedEntry(refresh = true) ?: return ""
        return if (entry.isEncrypted) {
            if (VaultSession.isUnlocked()) RecordStorage.readDecryptedPayload(requireContext(),entry.timestamp)?.get("text").orEmpty()
            else ""
        } else entry.text
    }

    private fun setupExportButton(view: View, timestamp: Long) {
        val requestKey = "entry_export:$timestamp"
        parentFragmentManager.setFragmentResultListener(requestKey,viewLifecycleOwner) { _, result ->
            val format = ExportPickerSheet.Format.values().firstOrNull { it.name == result.getString(ExportPickerSheet.RESULT_FORMAT) }
                ?: return@setFragmentResultListener
            val transcript = currentTranscript()
            if (transcript.isBlank() && format in listOf(ExportPickerSheet.Format.PDF,ExportPickerSheet.Format.DOCX))
                return@setFragmentResultListener
            val audioFile = playableAudioFile?.takeIf { it.isFile }
            val date = arguments?.getString(ARG_DATE) ?: SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date(timestamp))
            when (format) {
                ExportPickerSheet.Format.JSON -> exporter.exportJson(timestamp,transcript,parseStoredSegments())
                ExportPickerSheet.Format.SRT -> if (audioFile != null) exporter.exportSrt(audioFile,timestamp,parseStoredSegments())
                ExportPickerSheet.Format.AUDIO -> if (audioFile != null) exporter.exportAudio(audioFile,timestamp)
                ExportPickerSheet.Format.PDF -> exporter.exportDocument(EntryExporter.Doc.PDF,date,timestamp,transcript)
                ExportPickerSheet.Format.DOCX -> exporter.exportDocument(EntryExporter.Doc.DOCX,date,timestamp,transcript)
            }
        }
        view.findViewById<MaterialButton>(R.id.btnExportFormats).setOnClickListener {
            if (!ProFeatures.requirePro(requireContext(),ProFeatures.Feature.ADVANCED_EXPORTS)) return@setOnClickListener
            if (parentFragmentManager.findFragmentByTag("export_picker") == null)
                ExportPickerSheet.newInstance(playableAudioFile?.isFile == true,requestKey).show(parentFragmentManager,"export_picker")
        }
    }

    // ── Дiarization ───────────────────────────────────────────────────────────

    /** Decode a large word timeline once per view. Writes and job completion invalidate it;
     * user-confirmed edits always refresh before comparing with the displayed text. */
    private fun storedEntry(refresh: Boolean = false): DailyRecord.Entry? {
        if (!refresh && entrySnapshotLoaded) return entrySnapshot
        val ts = arguments?.getLong(ARG_TIMESTAMP) ?: return null
        val ctx = context ?: return null
        entrySnapshot = try { RecordStorage.findEntryByTimestamp(ctx, ts)?.second }
        catch (e: Exception) { Log.w(TAG, "entry load failed: ${e.message}"); null }
        entrySnapshotLoaded = true
        return entrySnapshot
    }

    private fun parseStoredSegments(): List<WhisperHelper.Segment>? = storedEntry()?.segments

    /** «Сколько было человек?» — точное число сильно помогает кластеризации голосов. */
    private fun askSpeakerCount(alreadyLabeled: Boolean, onChosen: (Int?) -> Unit) {
        val options = arrayOf("Не знаю — определить автоматически", "2 человека", "3 человека", "4 человека", "5 человек")
        val values = arrayOf<Int?>(null, 2, 3, 4, 5)
        var chosen = 0
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Сколько человек говорит в записи?")
            .setSingleChoiceItems(options, 0) { _, which -> chosen = which }
            .setNegativeButton("Отмена", null)
            .setPositiveButton(if (alreadyLabeled) "Разделить заново" else "Разделить") { _, _ ->
                onChosen(values[chosen])
            }
            .show()
        if (alreadyLabeled) {
            Toast.makeText(requireContext(),
                "Текст пересоберётся из распознавания — ручные правки и имена говорящих сбросятся",
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Разделение идёт в DiarizationJobRunner на уровне приложения: закрыли лист, свернули или смахнули приложение,
     * а обработка продолжается (пока жив процесс; на это время работает foreground-сервис с уведомлением).
     */
    private fun runDiarization(
        audioFile: File,
        timestamp: Long,
        numSpeakers: Int?,
        storedSegments: List<WhisperHelper.Segment>?,
    ) {
        val appCtx = requireContext().applicationContext
        // Whisper нужен только для записей без сохранённых слов; берём ссылку заранее, пока лист жив.
        val whisper = (activity as? MainActivity)?.whisperHelper ?: WhisperHelper.active
        DiarizationJobRunner.start(appCtx, audioFile, timestamp, numSpeakers, storedSegments, whisper, hasSpeakerLabels)
    }

    /** Модель голосов не скачана (в начале её можно было пропустить): предлагаем скачать через экран загрузки. */
    private fun offerVoiceModelDownload(message: String) {
        val ctx = context ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Разделение голосов")
            .setMessage(message)
            .setNegativeButton("Позже", null)
            .setPositiveButton("Скачать") { _, _ ->
                startActivity(android.content.Intent(ctx, SetupActivity::class.java))
            }
            .show()
    }

    /** Есть ли в тексте записи метки говорящих (от этого зависит, затирать ли текст, если найден один голос). */
    private var hasSpeakerLabels = false
    private var diarizeListener: ((DiarizationJobRunner.State) -> Boolean)? = null

    /** Показывает состояние фонового разделения на кнопке и подхватывает результат, если лист открыт к его концу. */
    private fun bindDiarization(
        view: View, timestamp: Long, tvText: TextView, btnDiarize: MaterialButton, labeledInitially: Boolean,
    ) {
        var idleText = if (labeledInitially) "Разделить заново" else "Разделить по голосам"
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelDiarize)
        btnCancel.setOnClickListener { DiarizationJobRunner.cancel(timestamp) }
        val listener: (DiarizationJobRunner.State) -> Boolean = render@{ s ->
            if (s.timestamp != timestamp || !isAdded || getView() !== view) return@render false
            // Лист открыт, но приложение свёрнуто: результат применим, а уведомление всё равно нужно.
            val visible = viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            when (s) {
                is DiarizationJobRunner.State.Running -> {
                    btnDiarize.isEnabled = false
                    btnDiarize.text = s.stage
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.isEnabled = !s.stage.startsWith("Отменяю")
                    false
                }
                is DiarizationJobRunner.State.Cancelled -> {
                    btnCancel.visibility = View.GONE
                    btnDiarize.isEnabled = true
                    btnDiarize.text = idleText
                    DiarizationJobRunner.consume(timestamp)
                    false
                }
                is DiarizationJobRunner.State.Done -> {
                    invalidateEntrySnapshot()
                    btnCancel.visibility = View.GONE
                    if (s.text != null) {
                        applyTranscriptStyle(tvText, s.text, timestamp)
                        hasSpeakerLabels = (s.text.contains("[Говорящий") || s.text.contains("[Говорят одновременно]"))
                    }
                    idleText = if (hasSpeakerLabels) "Разделить заново" else "Разделить по голосам"
                    btnDiarize.isEnabled = true
                    btnDiarize.text = idleText
                    // Кнопку именования показываем, только если есть с чем сравнивать голоса (не «по паузам»).
                    if (s.hasCentroids) view.findViewById<MaterialButton>(R.id.btnNameSpeakers)?.visibility = View.VISIBLE
                    DiarizationJobRunner.consume(timestamp)
                    visible
                }
                is DiarizationJobRunner.State.Failed -> {
                    btnCancel.visibility = View.GONE
                    btnDiarize.isEnabled = true
                    btnDiarize.text = idleText
                    DiarizationJobRunner.consume(timestamp)
                    if (s.needsModels && visible) offerVoiceModelDownload(s.message)
                    visible
                }
            }
        }
        diarizeListener?.let { DiarizationJobRunner.removeListener(it) }
        diarizeListener = listener
        DiarizationJobRunner.addListener(listener)
        DiarizationJobRunner.stateOf(timestamp)?.let { listener(it) }
    }

    // ── Player ────────────────────────────────────────────────────────────────

    private fun setupPlayer(view: View, audioPath: String) = audioPlayer.bind(view, audioPath)

    override fun onDestroyView() {
        invalidateEntrySnapshot()
        super.onDestroyView()
        diarizeListener?.let { DiarizationJobRunner.removeListener(it) }
        diarizeListener = null
        audioPlayer.release()
    }
}
