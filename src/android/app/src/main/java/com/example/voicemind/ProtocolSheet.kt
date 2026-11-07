package com.example.voicemind

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * Протокол совещания по записи: участники, темы, решения, задачи, открытые вопросы.
 * Генерация — в [ProtocolRunner] (живёт дольше листа); готовый протокол уже сохранён
 * в записи, при повторном открытии показываем его без новой генерации.
 */
class ProtocolSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TS = "ts"
        private const val ARG_TRANSCRIPT = "transcript"
        private const val ARG_EXISTING = "existing"

        fun newInstance(timestamp: Long, transcript: String, existing: String?) = ProtocolSheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_TS, timestamp)
                // Тексты — через TextHandoff, не в Bundle (лимит ~1 МБ при уходе в фон).
                putString(ARG_TRANSCRIPT, TextHandoff.put(transcript))
                putString(ARG_EXISTING, TextHandoff.put(existing))
            }
        }
    }

    private var listener: ((ProtocolRunner.State) -> Unit)? = null
    private var current: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_protocol, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ts = requireArguments().getLong(ARG_TS)
        val transcriptOrNull = TextHandoff.get(requireArguments().getString(ARG_TRANSCRIPT))
        if (transcriptOrNull == null) {   // процесс перезапускался — текста в памяти нет (см. TextHandoff)
            android.widget.Toast.makeText(requireContext(), "Откройте запись ещё раз", android.widget.Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss(); return
        }
        val transcript: String = transcriptOrNull
        val existing = TextHandoff.get(requireArguments().getString(ARG_EXISTING))

        val tvStatus = view.findViewById<TextView>(R.id.tvProtocolStatus)
        val tvText = view.findViewById<TextView>(R.id.tvProtocol)
        val progress = view.findViewById<ProgressBar>(R.id.progressProtocol)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnProtocolCopy)
        val btnShare = view.findViewById<MaterialButton>(R.id.btnProtocolShare)
        val btnAgain = view.findViewById<MaterialButton>(R.id.btnProtocolAgain)
        val btnYouGile = view.findViewById<MaterialButton>(R.id.btnProtocolYouGile)

        fun showText(md: String) { current = md; tvText.text = ProtocolFormat.render(md) }
        fun setBusy(busy: Boolean) {
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            btnAgain.isEnabled = !busy
            btnCopy.isEnabled = !busy && current.isNotBlank()
            btnShare.isEnabled = !busy && current.isNotBlank()
            btnYouGile.isEnabled = !busy && current.isNotBlank()
        }

        btnYouGile.visibility = if (YouGile.isConfigured(requireContext())) View.VISIBLE else View.GONE
        btnYouGile.setOnClickListener {
            YouGileSendSheet.newInstance(ts, ProtocolFormat.plain(current), transcript)
                .show(parentFragmentManager, "yougile_send")
        }

        listener = { s ->
            if (isAdded) when (s) {
                is ProtocolRunner.State.Running -> if (s.timestamp == ts) {
                    tvStatus.text = if (s.partial.isEmpty()) "Читаю запись…" else "Пишется протокол…"
                    if (s.partial.isNotEmpty()) showText(s.partial)
                    setBusy(true)
                }
                is ProtocolRunner.State.Done -> if (s.timestamp == ts) {
                    showText(s.text)
                    tvStatus.text = if(s.saved) "Готово. Протокол сохранён в записи и попадёт в экспорт PDF/Word."
                        else "Готово. Протокол защищённой записи доступен для копирования; в открытом виде он не сохранён."
                    setBusy(false)
                }
                is ProtocolRunner.State.Failed -> if (s.timestamp == ts) {
                    tvStatus.text = s.error; setBusy(false)
                }
                ProtocolRunner.State.Idle -> Unit
            }
        }
        ProtocolRunner.addListener(listener!!)

        val st = ProtocolRunner.state
        val runningHere = st is ProtocolRunner.State.Running && st.timestamp == ts
        when {
            runningHere -> Unit
            !existing.isNullOrBlank() -> {
                showText(existing); tvStatus.text = "Сохранённый протокол"; setBusy(false)
            }
            else -> generate(ts, transcript, tvStatus, ::setBusy)
        }

        btnAgain.setOnClickListener { generate(ts, transcript, tvStatus, ::setBusy) }
        btnCopy.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Протокол", ProtocolFormat.plain(current)))
            Toast.makeText(requireContext(), "Скопировано", Toast.LENGTH_SHORT).show()
        }
        btnShare.setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Протокол совещания")
                putExtra(Intent.EXTRA_TEXT, ProtocolFormat.plain(current))
            }, "Отправить протокол"))
        }
    }

    private fun generate(ts: Long, transcript: String, tvStatus: TextView, setBusy: (Boolean) -> Unit) {
        val appCtx = requireContext().applicationContext
        if (transcript.isBlank()) { tvStatus.text = "Пустая запись"; return }
        // Протокол строит универсальная модель; дообученная Qwen умеет только «Выделить главное».
        if (!LlmModels.selected(appCtx).supportsQa) {
            tvStatus.text = "Протокол недоступен в этой версии."
            return
        }
        val main = activity as? MainActivity ?: return
        setBusy(true)
        if (main.llmHelper?.isReady != true) tvStatus.text = "Подготавливаю ИИ-помощника — 20–60 секунд…"
        main.ensureLlmLoaded {
            val llm = main.llmHelper
            if (llm == null || !llm.isReady) {
                if (isAdded) {
                    tvStatus.text = "Не удалось запустить ИИ-помощника. Проверьте свободное место и попробуйте ещё раз."
                    setBusy(false)
                }
                return@ensureLlmLoaded
            }
            ProtocolRunner.start(appCtx, llm, ts, transcript)
        }
    }

    override fun onDestroyView() {
        listener?.let { ProtocolRunner.removeListener(it) }
        listener = null
        super.onDestroyView()
    }
}
