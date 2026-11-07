package com.example.guzlikvoice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

sealed class HistoryItem {
    data class DateHeader(val date: String)  : HistoryItem()
    data class Entry(val log: LogEntry)      : HistoryItem()
    data class Summary(val text: String)     : HistoryItem()
}

data class LogEntry(
    val timestamp:   Long,
    val rawText:     String,
    val structured:  String,
    val keyThoughts: List<String>,
    val tags:        List<String>,
    val source:      String,
    val audioPath:   String = "",
    val isEncrypted: Boolean = false,
    val segments:    List<WhisperHelper.Segment>? = null
)

class HistoryAdapter(
    private val items: List<HistoryItem>,
    private val date: String,
    private val fragmentManager: FragmentManager,
    private val onDelete: (Long) -> Unit,
    private val onDeleteSummary: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** «45 с», «12 мин», «1 ч 05 мин». */
    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60   -> "$sec с"
            sec < 3600 -> "${sec / 60} мин"
            else       -> "${sec / 3600} ч %02d мин".format((sec % 3600) / 60)
        }
    }

    // Timestamps записей, у которых сейчас виден значок корзины.
    // Ключ — timestamp, а не позиция, чтобы после удаления/refresh состояние не сбилось.
    private val deleteModeTimestamps = mutableSetOf<Long>()

    companion object {
        private const val TYPE_HEADER  = 0
        private const val TYPE_ENTRY   = 1
        private const val TYPE_SUMMARY = 2
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is HistoryItem.DateHeader -> TYPE_HEADER
        is HistoryItem.Entry      -> TYPE_ENTRY
        is HistoryItem.Summary    -> TYPE_SUMMARY
    }

    inner class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime:    TextView     = v.findViewById(R.id.tvTime)
        val tvSource:  TextView     = v.findViewById(R.id.tvSource)
        val tvText:    TextView     = v.findViewById(R.id.tvText)
        val tvTags:    TextView     = v.findViewById(R.id.tvTags)
        val btnDel:    ImageButton  = v.findViewById(R.id.btnDeleteEntry)
        val btnOpen:   MaterialButton = v.findViewById(R.id.btnOpenDetail)
        val btnPlay:   MaterialButton = v.findViewById(R.id.btnPlayAudio)
        var player:    MediaPlayer? = null
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvDateHeader)
    }

    inner class SummaryVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvSummary: TextView       = v.findViewById(R.id.tvSummaryText)
        val btnDelete: ImageButton    = v.findViewById(R.id.btnDeleteSummary)
        val btnCopy:   MaterialButton = v.findViewById(R.id.btnCopySummary)
        val btnShare:  MaterialButton = v.findViewById(R.id.btnShareSummary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER  -> HeaderVH(inflater.inflate(R.layout.item_date_header, parent, false))
            TYPE_SUMMARY -> SummaryVH(inflater.inflate(R.layout.item_day_summary, parent, false))
            else         -> EntryVH(inflater.inflate(R.layout.item_today_entry, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HistoryItem.DateHeader -> (holder as HeaderVH).tvDate.text = item.date
            is HistoryItem.Summary -> {
                val vh = holder as SummaryVH
                vh.tvSummary.text = item.text
                vh.btnDelete.setOnClickListener { onDeleteSummary() }
                vh.btnCopy.setOnClickListener {
                    val ctx = vh.itemView.context
                    val clip = ClipData.newPlainText("GuzlikVoice анализ", item.text)
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return@setOnClickListener
                    cb.setPrimaryClip(clip)
                    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                }
                vh.btnShare.setOnClickListener {
                    val ctx = vh.itemView.context
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, item.text)
                        putExtra(Intent.EXTRA_SUBJECT, "GuzlikVoice — анализ дня")
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Сохранить или поделиться"))
                }
            }
            is HistoryItem.Entry -> {
                val vh = holder as EntryVH
                val entry = item.log
                // Длительность (ТЗ проекта, П-5): по концу последней фразы — у записей с таймкодами.
                val durMs = entry.segments?.maxOfOrNull { it.endMs } ?: 0L
                vh.tvTime.text = fmtTime.format(Date(entry.timestamp)) +
                    if (durMs > 0) " · " + formatDuration(durMs) else ""
                vh.tvSource.text = when {
                    entry.isEncrypted           -> "Защищено"
                    entry.source == "voice"     -> "Голос"
                    else                        -> "Текст"
                }
                vh.tvText.text   = if (entry.isEncrypted && !VaultSession.isUnlocked())
                    RecordStorage.ENCRYPTED_PLACEHOLDER else entry.rawText

                if (entry.tags.isNotEmpty()) {
                    vh.tvTags.visibility = View.VISIBLE
                    vh.tvTags.text = entry.tags.joinToString("  ")
                } else {
                    vh.tvTags.visibility = View.GONE
                }

                // Кнопка воспроизведения (audioPath может быть null в старых записях)
                val audioPath = entry.audioPath ?: ""
                val audioFile = if (audioPath.isNotBlank()) File(audioPath) else null
                if (audioFile != null && audioFile.exists()) {
                    vh.btnPlay.visibility = View.VISIBLE
                    vh.btnPlay.setOnClickListener {
                        if (vh.player?.isPlaying == true) {
                            vh.player?.stop()
                            vh.player?.release()
                            vh.player = null
                            vh.btnPlay.text = "▶ Слушать"
                        } else {
                            vh.player?.release()
                            val btn = vh.btnPlay
                            try {
                                val mp = MediaPlayer()
                                mp.setDataSource(audioFile.absolutePath)
                                mp.prepare()
                                mp.start()
                                vh.player = mp
                                btn.text = "■ Стоп"
                                mp.setOnCompletionListener { done ->
                                    // ViewHolder может уже быть переиспользован под другую запись —
                                    // не трогаем UI/поле, если player сменился, иначе перезапишем
                                    // состояние соседней воспроизводящейся записи.
                                    if (vh.player === done) {
                                        btn.text = "▶ Слушать"
                                        vh.player = null
                                    }
                                    runCatching { done.release() }
                                }
                            } catch (e: Exception) {
                                Log.e("HistoryAdapter", "MediaPlayer error: ${e.message}", e)
                                vh.player?.release()
                                vh.player = null
                                btn.text = "▶ Слушать"
                            }
                        }
                    }
                } else {
                    vh.btnPlay.visibility = View.GONE
                }

                val openDetail = {
                    TranscriptionDetailSheet.newInstance(entry, date)
                        .show(fragmentManager, "detail")
                }

                val ts = entry.timestamp
                val inDeleteMode = ts in deleteModeTimestamps
                vh.btnDel.visibility = if (inDeleteMode) View.VISIBLE else View.GONE

                vh.btnOpen.setOnClickListener { openDetail() }
                vh.itemView.setOnClickListener {
                    // Повторный тап по записи в режиме удаления просто скрывает корзину
                    // — интуитивно ожидаемое «отмена», без открытия деталей.
                    val pos = vh.bindingAdapterPosition
                    if (ts in deleteModeTimestamps) {
                        deleteModeTimestamps.remove(ts)
                        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                    } else {
                        openDetail()
                    }
                }
                vh.itemView.setOnLongClickListener {
                    val pos = vh.bindingAdapterPosition
                    if (ts in deleteModeTimestamps) deleteModeTimestamps.remove(ts)
                    else deleteModeTimestamps.add(ts)
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                    true
                }
                vh.btnDel.setOnClickListener {
                    deleteModeTimestamps.remove(ts)
                    onDelete(ts)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is EntryVH) {
            holder.player?.release()
            holder.player = null
            holder.btnPlay.text = "▶ Слушать"
        }
    }
}
