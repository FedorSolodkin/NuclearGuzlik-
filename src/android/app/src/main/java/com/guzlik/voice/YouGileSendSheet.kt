package com.guzlik.voice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Отправка протокола в YouGile: новой задачей (задачи протокола — чек-листом,
 * протокол и расшифровка — в чат задачи) или сообщением в чат существующей задачи.
 */
class YouGileSendSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TS = "ts"
        private const val ARG_PROTOCOL = "protocol"
        private const val ARG_TRANSCRIPT = "transcript"

        fun newInstance(ts: Long, protocol: String, transcript: String) = YouGileSendSheet().apply {
            arguments = Bundle().apply {
                // Тексты — через TextHandoff, не в Bundle (лимит ~1 МБ при уходе в фон).
                putLong(ARG_TS, ts)
                putString(ARG_PROTOCOL, TextHandoff.put(protocol))
                putString(ARG_TRANSCRIPT, TextHandoff.put(transcript))
            }
        }
    }

    private var existingTask: YouGile.Item? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_yougile_send, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val ts = requireArguments().getLong(ARG_TS)
        val protocolOrNull = TextHandoff.get(requireArguments().getString(ARG_PROTOCOL))
        if (protocolOrNull == null) {   // процесс перезапускался — текста в памяти нет (см. TextHandoff)
            android.widget.Toast.makeText(requireContext(), "Откройте запись ещё раз", android.widget.Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss(); return
        }
        val protocol: String = protocolOrNull
        val transcript = TextHandoff.get(requireArguments().getString(ARG_TRANSCRIPT)).orEmpty()

        val rbNew = view.findViewById<RadioButton>(R.id.rbYgNew)
        val rbExisting = view.findViewById<RadioButton>(R.id.rbYgExisting)
        val etTitle = view.findViewById<EditText>(R.id.etYgTitle)
        val tvColumn = view.findViewById<TextView>(R.id.tvYgColumn)
        val btnPickTask = view.findViewById<MaterialButton>(R.id.btnYgPickTask)
        val cbTranscript = view.findViewById<CheckBox>(R.id.cbYgTranscript)
        val btnSend = view.findViewById<MaterialButton>(R.id.btnYgSend)
        val tvStatus = view.findViewById<TextView>(R.id.tvYgStatus)

        val date = SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(ts))
        etTitle.setText("Совещание $date")
        val column = YouGile.defaultColumn(ctx)
        tvColumn.text = column?.let { "Колонка: ${it.title}" } ?: "Колонка не выбрана — выберите в Настройки → YouGile"

        fun refresh() {
            etTitle.isEnabled = rbNew.isChecked
            btnPickTask.isEnabled = rbExisting.isChecked
            btnPickTask.text = existingTask?.title?.let { "Задача: $it" } ?: "Найти задачу"
            btnSend.isEnabled = if (rbNew.isChecked) column != null else existingTask != null
        }
        rbNew.setOnClickListener { refresh() }
        rbExisting.setOnClickListener { refresh() }
        refresh()

        btnPickTask.setOnClickListener { searchTask(column?.id) { existingTask = it; refresh() } }

        btnSend.setOnClickListener {
            val app = ctx.applicationContext
            val message = buildString {
                append("Протокол совещания — $date\n\n").append(protocol.trim())
                if (cbTranscript.isChecked && transcript.isNotBlank()) append("\n\n— Расшифровка —\n\n").append(transcript.trim())
                append("\n\n(GuzlikVoice)")
            }
            btnSend.isEnabled = false
            tvStatus.text = "Отправляю…"
            viewLifecycleOwner.lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val taskId = if (rbNew.isChecked) {
                            YouGile.createTask(app, column!!.id, etTitle.text.toString().ifBlank { "Совещание $date" },
                                "Протокол и расшифровка — в чате задачи. Создано в GuzlikVoice.",
                                YouGile.tasksFromProtocol(protocol))
                        } else existingTask!!.id
                        YouGile.postToTask(app, taskId, message)
                    }
                }
                if (!isAdded) return@launch
                res.onSuccess {
                    Toast.makeText(ctx, "Отправлено в YouGile", Toast.LENGTH_SHORT).show()
                    dismiss()
                }.onFailure {
                    tvStatus.text = it.message ?: "Ошибка сети"
                    btnSend.isEnabled = true
                }
            }
        }
    }

    private fun searchTask(columnId: String?, onPick: (YouGile.Item) -> Unit) {
        val ctx = requireContext()
        val et = EditText(ctx).apply { hint = "Часть названия задачи" }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Найти задачу")
            .setView(et)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Искать") { _, _ ->
                val q = et.text.toString().trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            // Сначала в колонке протоколов, потом по всей компании.
                            columnId?.let { YouGile.searchTasks(ctx, q, it) }?.takeIf { it.isNotEmpty() }
                                ?: YouGile.searchTasks(ctx, q, null)
                        }
                    }
                    if (!isAdded) return@launch
                    res.onSuccess { tasks ->
                        if (tasks.isEmpty()) { Toast.makeText(ctx, "Ничего не нашлось", Toast.LENGTH_SHORT).show(); return@onSuccess }
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Задача")
                            .setItems(tasks.map { it.title }.toTypedArray()) { _, i -> onPick(tasks[i]) }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }.onFailure { Toast.makeText(ctx, it.message ?: "Ошибка сети", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }
}
