package com.example.voicemind

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText

/**
 * Q&A по одной записи через локальную Gemma. Pro-фича.
 *
 * Сам запрос выполняет QaJobRunner (application-scoped), а этот sheet — только UI:
 * подписывается на состояние, показывает прогресс/ответ и позволяет задать новый вопрос.
 * Уход с вкладки не отменяет генерацию — при возврате будет виден готовый ответ.
 */
class QaSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TRANSCRIPT = "transcript"
        private const val ARG_RECORD_KEY = "record_key"

        fun newInstance(recordKey: String, transcript: String) = QaSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TRANSCRIPT, TextHandoff.put(transcript))   // не в Bundle: лимит ~1 МБ
                putString(ARG_RECORD_KEY, recordKey)
            }
        }
    }

    private var stateListener: ((QaJobRunner.State) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_qa, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val transcriptOrNull = TextHandoff.get(arguments?.getString(ARG_TRANSCRIPT))
        if (transcriptOrNull == null) {   // процесс перезапускался — текста в памяти нет (см. TextHandoff)
            android.widget.Toast.makeText(requireContext(), "Откройте запись ещё раз", android.widget.Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss(); return
        }
        val transcript: String = transcriptOrNull
        val recordKey  = arguments?.getString(ARG_RECORD_KEY).orEmpty()

        val etQuestion = view.findViewById<TextInputEditText>(R.id.etQaQuestion)
        val btnAsk     = view.findViewById<MaterialButton>(R.id.btnAsk)
        val tvAnswer   = view.findViewById<TextView>(R.id.tvQaAnswer)
        val btnCopy    = view.findViewById<MaterialButton>(R.id.btnQaCopy)
        val progress   = view.findViewById<ProgressBar>(R.id.progressQa)
        val card       = view.findViewById<MaterialCardView>(R.id.cardQaAnswer)

        val chipSummary   = view.findViewById<Chip>(R.id.chipSummary)
        val chipDecisions = view.findViewById<Chip>(R.id.chipDecisions)
        val chipTodos     = view.findViewById<Chip>(R.id.chipTodos)

        val quickAsk: (String) -> Unit = { text ->
            etQuestion.setText(text)
            triggerAsk(recordKey, transcript, text)
        }
        chipSummary.setOnClickListener   { quickAsk("Кратко перескажи о чём эта запись, 2-3 предложения.") }
        chipDecisions.setOnClickListener { quickAsk("Какие решения приняты или обсуждаются в записи? Списком.") }
        chipTodos.setOnClickListener     { quickAsk("Какие задачи и действия упоминаются? Списком с исполнителями если названы.") }

        btnAsk.setOnClickListener {
            val q = etQuestion.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) {
                Toast.makeText(requireContext(), "Введите вопрос", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            triggerAsk(recordKey, transcript, q)
        }

        btnCopy.setOnClickListener {
            val txt = tvAnswer.text.toString()
            if (txt.isBlank()) return@setOnClickListener
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@setOnClickListener
            cb.setPrimaryClip(ClipData.newPlainText("answer", txt))
            Toast.makeText(requireContext(), "Скопировано", Toast.LENGTH_SHORT).show()
        }

        val listener: (QaJobRunner.State) -> Unit = { state ->
            if (isAdded) applyState(state, recordKey, btnAsk, progress, card, tvAnswer)
        }
        stateListener = listener
        QaJobRunner.addListener(listener)

        dialog?.setOnShowListener {
            val bottomSheet = dialog!!.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
    }

    override fun onDestroyView() {
        stateListener?.let { QaJobRunner.removeListener(it) }
        stateListener = null
        super.onDestroyView()
    }

    private fun applyState(
        state: QaJobRunner.State,
        myKey: String,
        btnAsk: MaterialButton,
        progress: ProgressBar,
        card: MaterialCardView,
        tvAnswer: TextView
    ) {
        // Реагируем только на состояния, относящиеся к нашей записи. Если пользователь
        // открыл другую запись — здесь показываем чистый экран.
        when (state) {
            is QaJobRunner.State.Running -> if (state.recordKey == myKey) {
                btnAsk.isEnabled = false
                btnAsk.text = "Готовит ответ..."
                progress.visibility = View.VISIBLE
                if (state.partial.isNotBlank()) {
                    card.visibility = View.VISIBLE
                    tvAnswer.text = state.partial
                }
            } else resetIdleUi(btnAsk, progress, card)
            is QaJobRunner.State.Done -> if (state.recordKey == myKey) {
                btnAsk.isEnabled = true
                btnAsk.text = "Спросить ещё"
                progress.visibility = View.GONE
                card.visibility = View.VISIBLE
                tvAnswer.text = state.answer.ifBlank { "(пустой ответ)" }
            } else resetIdleUi(btnAsk, progress, card)
            is QaJobRunner.State.Failed -> if (state.recordKey == myKey) {
                btnAsk.isEnabled = true
                btnAsk.text = "Спросить"
                progress.visibility = View.GONE
                Toast.makeText(requireContext(), state.error.take(160), Toast.LENGTH_LONG).show()
            } else resetIdleUi(btnAsk, progress, card)
            QaJobRunner.State.Idle -> resetIdleUi(btnAsk, progress, card)
        }
    }

    private fun resetIdleUi(btnAsk: MaterialButton, progress: ProgressBar, card: MaterialCardView) {
        btnAsk.isEnabled = true
        btnAsk.text = "Спросить"
        progress.visibility = View.GONE
        card.visibility = View.GONE
    }

    private fun triggerAsk(recordKey: String, transcript: String, question: String) {
        val appCtx = requireContext().applicationContext
        if (transcript.isBlank()) {
            Toast.makeText(appCtx, "Пустая запись", Toast.LENGTH_SHORT).show()
            return
        }
        val main = activity as? MainActivity
        val alreadyLoaded = main?.llmHelper?.isReady == true
        // Даём мгновенный визуальный отклик: если LLM ещё не в памяти,
        // Running-стейт запустится только через 30-60 сек. Показываем прогресс
        // сейчас, чтобы пользователь не гадал, зависло приложение или нет.
        if (!alreadyLoaded) {
            view?.findViewById<ProgressBar>(R.id.progressQa)?.visibility = View.VISIBLE
            view?.findViewById<MaterialButton>(R.id.btnAsk)?.let {
                it.isEnabled = false
                it.text = "Загружается ИИ…"
            }
            Toast.makeText(
                appCtx,
                "Подготавливаю ИИ-помощника. Это занимает 20–60 секунд, один раз.",
                Toast.LENGTH_LONG
            ).show()
        }
        main?.ensureLlmLoaded {
            val llm = main.llmHelper
            if (llm == null || !llm.isReady) {
                if (isAdded) {
                    view?.findViewById<ProgressBar>(R.id.progressQa)?.visibility = View.GONE
                    view?.findViewById<MaterialButton>(R.id.btnAsk)?.let {
                        it.isEnabled = true
                        it.text = "Спросить"
                    }
                    Toast.makeText(appCtx, "Не удалось запустить ИИ-помощника. Проверьте свободное место.", Toast.LENGTH_LONG).show()
                }
                return@ensureLlmLoaded
            }
            QaJobRunner.ask(appCtx, llm, recordKey, transcript, question)
        }
    }
}
