package com.example.guzlikvoice

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private lateinit var rvHistory:        RecyclerView
    private lateinit var emptyState:       android.widget.LinearLayout
    private lateinit var cardDateSelector: com.google.android.material.card.MaterialCardView
    private lateinit var tvSelectedDate:   TextView
    private lateinit var btnAnalyzeDay:    MaterialButton
    private lateinit var btnExportDay:     MaterialButton
    // LLM текущего анализа — чтобы «Остановить» реально прерывал генерацию.
    @Volatile private var analysisLlm: LlmHelper? = null
    private lateinit var etSearch: com.google.android.material.textfield.TextInputEditText

    private val fmtKey  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val fmtDisp = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
    private var selectedDateStr = fmtKey.format(Date())

    private var currentEntries: List<DailyRecord.Entry> = emptyList()
    private var currentItems: List<HistoryItem> = emptyList()
    private var savedSummary: String = ""
    private var entriesLoadVersion = 0L
    private var swipeHelperAttached = false

    // Отмена анализа: каждый запуск получает уникальный ID;
    // если при возврате результата ID не совпадает — результат выбрасывается
    @Volatile private var activeJobId: Long = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvHistory        = view.findViewById(R.id.rvHistory)
        emptyState       = view.findViewById(R.id.historyEmptyState)
        cardDateSelector = view.findViewById(R.id.cardDateSelector)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnImportAudio)
            .setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        tvSelectedDate   = view.findViewById(R.id.tvSelectedDate)
        btnAnalyzeDay    = view.findViewById(R.id.btnAnalyzeDay)
        btnExportDay     = view.findViewById(R.id.btnExportDay)

        // Deep link из empty state → вкладка "Запись" (position 0 в ViewPager2,
        // после того как убрали вкладку "Сегодня")
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEmptyStartRecording)
            ?.setOnClickListener {
                requireActivity().findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
                    ?.setCurrentItem(0, true)
            }

        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        updateDateLabel()
        loadEntriesForDay(selectedDateStr)

        cardDateSelector.setOnClickListener { openDatePicker() }
        btnAnalyzeDay.setOnClickListener { showEntrySelectionDialog() }
        btnAnalyzeDay.isEnabled = false
        btnExportDay.setOnClickListener { exportDayAsMarkdown() }

        // Fix 10: Search
        etSearch = view.findViewById(R.id.etSearch)
        val searchLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.searchLayout)
        if (ProFeatures.canUse(requireContext(), ProFeatures.Feature.SEMANTIC_SEARCH)) {
            searchLayout.hint = "✦ Умный поиск"
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rebuildList() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    // Запись поменялась где-то ещё (правка текста в карточке, диаризация) —
    // перечитываем текущий день, если экран показан.
    private val storageListener: () -> Unit = {
        if (isAdded && view != null) loadEntriesForDay(selectedDateStr)
    }

    private val pickAudio = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val appCtx = requireContext().applicationContext
        // У OpenDocument права на uri держатся, пока жив процесс — копируем в фоне.
        Thread { AudioImporter.importUri(appCtx, uri) }.start()
    }

    override fun onResume() {
        super.onResume()
        RecordStorage.addChangeListener(storageListener)
        loadEntriesForDay(selectedDateStr)
    }

    override fun onPause() {
        RecordStorage.removeChangeListener(storageListener)
        super.onPause()
    }

    override fun onDestroyView() {
        swipeHelperAttached = false
        super.onDestroyView()
    }

    private fun updateDateLabel() {
        val date = fmtKey.parse(selectedDateStr) ?: Date()
        tvSelectedDate.text = fmtDisp.format(date)
    }

    private fun openDatePicker() {
        // MaterialDatePicker считает в UTC: выбор — полночь UTC нужной даты. С местной
        // полуночью в UTC+7 календарь открывался на вчерашнем дне, а в UTC−N
        // выбранный день превращался во вчерашний.
        val utcKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val millis = utcKey.parse(selectedDateStr)?.time ?: MaterialDatePicker.todayInUtcMilliseconds()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Выберите дату")
            .setSelection(millis)
            .build()
        picker.addOnPositiveButtonClickListener { ms ->
            selectedDateStr = utcKey.format(Date(ms))
            updateDateLabel()
            loadEntriesForDay(selectedDateStr)
        }
        picker.show(parentFragmentManager, "date_picker")
    }

    private fun loadEntriesForDay(date: String) {
        val ctx = context?.applicationContext ?: return
        val owner = viewLifecycleOwnerLiveData.value ?: return
        val version = ++entriesLoadVersion
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val records = RecordStorage.getAllRecords(ctx)
            val record  = records.find { it.date == date }
            val entries = record?.entries?.sortedByDescending { it.timestamp } ?: emptyList()
            val summary = record?.daySummary.orEmpty()

            withContext(Dispatchers.Main) {
                if (!isAdded || selectedDateStr != date || entriesLoadVersion != version) return@withContext
                currentEntries = entries
                savedSummary   = summary
                btnAnalyzeDay.isEnabled = entries.isNotEmpty() || activeJobId != 0L
                rebuildList()
            }
        }
    }

    private fun rebuildList() {
        val rawQuery = if (::etSearch.isInitialized) etSearch.text?.toString()?.trim().orEmpty() else ""
        val query    = rawQuery.lowercase()
        val items = mutableListOf<HistoryItem>()

        if (query.isNotEmpty()) {
            val ctx = context?.applicationContext
            if (ctx != null) {
                val useSemantic = ProFeatures.canUse(ctx, ProFeatures.Feature.SEMANTIC_SEARCH)
                lifecycleScope.launch(Dispatchers.IO) {
                    val allRecords = RecordStorage.getAllRecords(ctx)
                    val hits = mutableListOf<HistoryItem>()
                    // Поиск по собеседнику (ТЗ проекта, П-6): запрос совпал с именем клиента —
                    // обычный поиск, он найдёт все записи, привязанные к этому клиенту.
                    val clientIds = ClientStorage.getAll(ctx)
                        .filter { it.name.lowercase().contains(query) }.map { it.id }.toSet()
                    if (useSemantic && clientIds.isEmpty()) {
                        val matches = SemanticSearch.search(rawQuery, allRecords)
                        // Группируем по дате, сохраняя порядок ранжирования — заголовок даты
                        // ставим перед первым hit из этого дня.
                        val seenDates = mutableSetOf<String>()
                        for (m in matches) {
                            if (m.date !in seenDates) {
                                val d = try { fmtKey.parse(m.date) } catch (_: Exception) { null }
                                hits += HistoryItem.DateHeader(if (d != null) fmtDisp.format(d) else m.date)
                                seenDates += m.date
                            }
                            hits += HistoryItem.Entry(LogEntry(
                                timestamp   = m.entry.timestamp,
                                rawText     = m.entry.text,
                                structured  = m.entry.structured,
                                keyThoughts = m.entry.keyThoughts,
                                tags        = m.entry.tags,
                                source      = m.entry.source,
                                audioPath   = m.entry.audioPath ?: "",
                                isEncrypted = m.entry.isEncrypted,
                                segments    = m.entry.segments
                            ))
                        }
                    } else {
                        // Bez semantic — обычный substring, но ищем не только в text,
                        // а также в тегах / структурированной версии / шаблоне / key thoughts.
                        // Терапевту нужно быстро найти «тревога» и получить все записи,
                        // где Whisper это упомянул или LLM вынес в структурированный блок.
                        for (rec in allRecords) {
                            val matching = rec.entries.filter { e ->
                                e.text.lowercase().contains(query) ||
                                e.structured.lowercase().contains(query) ||
                                (e.templateResult?.lowercase()?.contains(query) == true) ||
                                e.keyThoughts.any { it.lowercase().contains(query) } ||
                                e.tags.any { it.lowercase().contains(query) } ||
                                (e.clientId != null && e.clientId in clientIds)
                            }
                            if (matching.isEmpty()) continue
                            val d = try { fmtKey.parse(rec.date) } catch (_: Exception) { null }
                            val label = if (d != null) fmtDisp.format(d) else rec.date
                            hits += HistoryItem.DateHeader(label)
                            matching.sortedByDescending { it.timestamp }.forEach { entry ->
                                hits += HistoryItem.Entry(LogEntry(
                                    timestamp   = entry.timestamp,
                                    rawText     = entry.text,
                                    structured  = entry.structured,
                                    keyThoughts = entry.keyThoughts,
                                    tags        = entry.tags,
                                    source      = entry.source,
                                    audioPath   = entry.audioPath ?: "",
                                    isEncrypted = entry.isEncrypted,
                                    segments    = entry.segments
                                ))
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        publishItems(hits)
                    }
                }
                return
            }
        }

        // Сохранённый анализ — первым элементом
        if (savedSummary.isNotBlank()) {
            items += HistoryItem.Summary(savedSummary)
        }

        currentEntries.forEach { entry ->
            items += HistoryItem.Entry(
                LogEntry(
                    timestamp   = entry.timestamp,
                    rawText     = entry.text,
                    structured  = entry.structured,
                    keyThoughts = entry.keyThoughts,
                    tags        = entry.tags,
                    source      = entry.source,
                    audioPath   = entry.audioPath ?: "",
                    isEncrypted = entry.isEncrypted,
                    segments    = entry.segments
                )
            )
        }
        publishItems(items)
    }

    private fun publishItems(items: List<HistoryItem>) {

        currentItems = items

        // Empty state виден только если действительно пусто — summary/DateHeader без записей не считаются
        val hasRealEntry = items.any { it is HistoryItem.Entry }
        emptyState.visibility = if (hasRealEntry) View.GONE else View.VISIBLE

        val adapter = HistoryAdapter(
            items           = items,
            date            = selectedDateStr,
            fragmentManager = parentFragmentManager,
            onDelete        = { timestamp ->
                val ctx = context?.applicationContext ?: return@HistoryAdapter
                lifecycleScope.launch(Dispatchers.IO) {
                    RecordStorage.deleteEntry(ctx, selectedDateStr, timestamp)
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        loadEntriesForDay(selectedDateStr)
                    }
                }
            },
            onDeleteSummary = { deleteSummary() }
        )
        rvHistory.adapter = adapter

        // Fix 7: Swipe to delete — attach once, read fresh items on each swipe
        if (!swipeHelperAttached) {
            val swipeCallback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                0, androidx.recyclerview.widget.ItemTouchHelper.LEFT
            ) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
                override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                    return if (vh is HistoryAdapter.EntryVH) super.getSwipeDirs(rv, vh) else 0
                }
                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                    val pos = vh.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return
                    val item = currentItems.getOrNull(pos) ?: run {
                        rvHistory.adapter?.notifyItemChanged(pos); return
                    }
                    if (item !is HistoryItem.Entry) {
                        rvHistory.adapter?.notifyItemChanged(pos); return
                    }
                    // Confirmation dialog: свайпнули по неосторожности → возвращаем карточку на место.
                    // Реальное удаление только после явного "Удалить".
                    AlertDialog.Builder(requireContext())
                        .setTitle("Удалить запись?")
                        .setMessage("Аудио и текст будут удалены безвозвратно.")
                        .setNegativeButton("Отмена") { _, _ ->
                            rvHistory.adapter?.notifyItemChanged(pos)
                        }
                        .setOnCancelListener {
                            rvHistory.adapter?.notifyItemChanged(pos)
                        }
                        .setPositiveButton("Удалить") { _, _ ->
                            val ctx = context?.applicationContext ?: return@setPositiveButton
                            lifecycleScope.launch(Dispatchers.IO) {
                                val date = RecordStorage.getAllRecords(ctx)
                                    .firstOrNull { rec -> rec.entries.any { it.timestamp == item.log.timestamp } }
                                    ?.date ?: selectedDateStr
                                RecordStorage.deleteEntry(ctx, date, item.log.timestamp)
                                withContext(Dispatchers.Main) {
                                    if (!isAdded) return@withContext
                                    rebuildList()
                                    loadEntriesForDay(selectedDateStr)
                                }
                            }
                        }
                        .show()
                }
            }
            androidx.recyclerview.widget.ItemTouchHelper(swipeCallback).attachToRecyclerView(rvHistory)
            swipeHelperAttached = true
        }
    }

    private fun deleteSummary() {
        val ctx = context?.applicationContext ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            RecordStorage.saveDaySummary(ctx, selectedDateStr, "")
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                savedSummary = ""
                rebuildList()
            }
        }
    }

    // ─── Диалог выбора записей ────────────────────────────────────────────────

    private fun showEntrySelectionDialog() {
        if (currentEntries.isEmpty()) return
        if(currentEntries.none{!it.isEncrypted}) {
            android.widget.Toast.makeText(requireContext(),"Защищённые записи не участвуют в анализе дня",android.widget.Toast.LENGTH_LONG).show()
            return
        }

        btnAnalyzeDay.isEnabled = false
        btnAnalyzeDay.text = "загрузка..."
        (activity as? MainActivity)?.ensureLlmLoaded {
            if (!isAdded || view == null) return@ensureLlmLoaded
            btnAnalyzeDay.isEnabled = true
            btnAnalyzeDay.text = "✦ Выделить главное"
            val llm = (activity as? MainActivity)?.llmHelper
            if (llm == null || !llm.isReady) {
                showAnalysisDownloadPrompt()
                return@ensureLlmLoaded
            }
            showEntryPicker(llm)
        }
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
                    android.content.Intent(requireContext(), SetupActivity::class.java)
                        .putExtra(SetupActivity.EXTRA_AUTO_LLM, true)
                )
            }
            .show()
    }

    private fun showEntryPicker(llm: LlmHelper) {
        val entries = currentEntries.filter{!it.isEncrypted}.toList()
        val targetDate = selectedDateStr
        val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val labels  = entries.map { entry ->
            val time    = fmtTime.format(Date(entry.timestamp))
            val preview = entry.text.take(60).replace('\n', ' ')
            "$time  —  $preview${if (entry.text.length > 60) "…" else ""}"
        }.toTypedArray()

        val checked = BooleanArray(entries.size) { true }

        AlertDialog.Builder(requireContext())
            .setTitle("Выберите записи для анализа")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Анализировать") { _, _ ->
                val selected = entries.filterIndexed { i, _ -> checked[i] }
                if (selected.isNotEmpty()) runAnalysis(llm, selected, targetDate)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Фоновый анализ ───────────────────────────────────────────────────────

    private fun runAnalysis(llm: LlmHelper, entries: List<DailyRecord.Entry>, targetDate: String = selectedDateStr) {
        if(!llm.retain())return
        analysisLlm = llm
        val combinedText = entries.joinToString("\n\n") { it.text }
        runAnalysis({ llm.structure(combinedText) }, { llm.cancel() }, targetDate, {llm.releaseUse()},entries.associate{it.timestamp to it.text})
    }

    internal fun runAnalysis(generate: () -> String, cancelGeneration: () -> Unit = {}, targetDate: String = selectedDateStr, onFinished: () -> Unit = {}, expectedEntries:Map<Long,String>?=null) {
        val jobId = System.nanoTime()
        val ctx = context?.applicationContext ?: run{onFinished();return}
        val lease = UserDataJobs.begin() ?: run{onFinished();return}
        val processingToken = try { RecordingService.processingStarted(ctx) }
        catch (e: Exception) { lease.close();onFinished();resetAnalyzeButton();return }
        activeJobId = jobId
        lease.onCancel {
            if (activeJobId == jobId) {
                activeJobId = 0L
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (activeJobId == 0L && isAdded && view != null) resetAnalyzeButton()
                }
            }
            cancelGeneration()
        }

        btnAnalyzeDay.text = "✕ Остановить"
        btnAnalyzeDay.isEnabled = true
        btnAnalyzeDay.setOnClickListener { cancelAnalysis() }

        // Отдельной полосы прогресса нет: кнопка сама показывает «✕ Остановить».
        val job=lifecycleScope.launch(Dispatchers.IO) {
            try {
                lease.check()
                val result = generate()
                lease.check()
                if (activeJobId != jobId || result.isBlank()) return@launch

                if(expectedEntries==null)RecordStorage.saveDaySummary(ctx,targetDate,result)
                else check(RecordStorage.saveDaySummaryIfUnchanged(ctx,targetDate,expectedEntries,result)) {
                    "Выбранные записи изменены или удалены. Повторите анализ"
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded || activeJobId != jobId) return@withContext
                    if (view == null || selectedDateStr != targetDate) return@withContext
                    savedSummary = result
                    rebuildList()
                    rvHistory.scrollToPosition(0)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("HistoryFragment", "Analysis failed", e)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if(activeJobId==jobId && isAdded && view!=null)
                        android.widget.Toast.makeText(ctx,ErrorMessages.humanize(e),android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (activeJobId == jobId) {
                        activeJobId = 0L
                        analysisLlm = null
                        if (isAdded && view != null) resetAnalyzeButton()
                    }
                }
            }
        }
        job.invokeOnCompletion {try{RecordingService.processingFinished(ctx,processingToken)}finally{try{lease.close()}finally{onFinished()}}}
    }

    private fun cancelAnalysis() {
        activeJobId = 0L   // любой пришедший результат будет проигнорирован
        analysisLlm?.cancel()   // и сама генерация останавливается, а не крутится в фоне
        analysisLlm = null
        resetAnalyzeButton()
    }

    private fun exportDayAsMarkdown() {
        val fmtDisp2 = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("ru"))
        val date = fmtKey.parse(selectedDateStr) ?: java.util.Date()
        val sb = StringBuilder()
        sb.appendLine("# GuzlikVoice — ${fmtDisp2.format(date)}")
        sb.appendLine()
        if (savedSummary.isNotBlank()) {
            sb.appendLine("## Анализ дня")
            sb.appendLine(savedSummary)
            sb.appendLine()
        }
        if (currentEntries.isEmpty()) {
            sb.appendLine("_Записей нет_")
        } else {
            sb.appendLine("## Записи")
            val fmtTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            currentEntries.sortedBy { it.timestamp }.forEach { entry ->
                val time = fmtTime.format(java.util.Date(entry.timestamp))
                sb.appendLine("### $time")
                sb.appendLine(entry.text)
                sb.appendLine()
            }
        }
        val text = sb.toString()
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "GuzlikVoice ${selectedDateStr}.md")
        }
        startActivity(android.content.Intent.createChooser(intent, "Экспорт заметок"))
    }

    private fun resetAnalyzeButton() {
        btnAnalyzeDay.text      = "✦ Выделить главное"
        btnAnalyzeDay.isEnabled = currentEntries.isNotEmpty()
        btnAnalyzeDay.setOnClickListener { showEntrySelectionDialog() }
    }
}
