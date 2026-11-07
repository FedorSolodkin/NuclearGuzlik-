package com.example.guzlikvoice

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

/**
 * Именование говорящих в записи (Pro-фича).
 * Показывает список detected speakers из последней диаризации,
 * позволяет присвоить имя — оно сохраняется как VoiceProfile и подставляется в текст.
 *
 * Улучшения этой версии:
 *  - Цветные круглые аватары (палитра совпадает с цветными чипами в транскрипте).
 *  - Preview-кнопка: играет 2.5-сек фрагмент голоса из исходного аудио.
 *  - «Похоже на …»: если центроид попал в серую зону совпадения (0.62..0.72),
 *    показываем подсказку с готовым именем — юзер подтверждает одним тапом.
 */
class NameSpeakersSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TIMESTAMP = "timestamp"
        private const val PREVIEW_MS = 2_500
        private const val TAG = "NameSpeakersSheet"
        internal fun resultKey(timestamp:Long)="speaker-renamed-$timestamp"

        fun newInstance(timestamp: Long) = NameSpeakersSheet().apply {
            arguments = Bundle().apply { putLong(ARG_TIMESTAMP, timestamp) }
        }
    }

    private lateinit var adapter: SpeakerRowsAdapter
    private var timestamp: Long = 0L
    private var audioPath: String = ""
    private var player: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopPreviewRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_name_speakers, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        timestamp = arguments?.getLong(ARG_TIMESTAMP) ?: 0L
        val ctx = requireContext().applicationContext

        // Достаём audioPath из записи для preview-плеера.
        val entry = findEntryFor(timestamp)
        audioPath = entry?.audioPath.orEmpty()

        val snap = SpeakerCentroidStorage.get(ctx, timestamp)
        val suggestions = computeSuggestions(snap)

        val rv = view.findViewById<RecyclerView>(R.id.rvSpeakers)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = SpeakerRowsAdapter(
            snap = snap,
            suggestions = suggestions,
            audioPresent = audioPath.isNotBlank() && File(audioPath).exists(),
            onRename = { index -> promptRename(index) },
            onPreview = { index -> playPreview(index) },
            onAcceptSuggestion = { index, name -> applyRename(index, name) }
        )
        rv.adapter = adapter

        dialog?.setOnShowListener { shown ->
            val bs = (shown as? android.app.Dialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.let {
                val b = BottomSheetBehavior.from(it)
                b.state = BottomSheetBehavior.STATE_EXPANDED
                b.skipCollapsed = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPreview()
    }

    /**
     * Смотрим в текущий снапшот и для каждого ещё-НЕ-имя-нованного спикера
     * проверяем VoiceProfileStorage. Если попали в серую зону — предлагаем.
     * Возвращает map: speakerIndex → (suggestedName, similarity).
     */
    private fun computeSuggestions(snap: SpeakerCentroidStorage.Snapshot?): Map<Int, Pair<String, Float>> {
        if (snap == null) return emptyMap()
        val ctx = requireContext().applicationContext
        val out = mutableMapOf<Int, Pair<String, Float>>()
        for (i in snap.centroids.indices) {
            // Уже сматчено (0.72+) — тогда name уже подставлен, подсказка не нужна.
            if (snap.matchedProfileIds.getOrNull(i) != null) continue
            val c = snap.centroids[i]
            if (c.isEmpty()) continue
            val match = VoiceProfileStorage.matchBest(ctx, c, VoiceProfileStorage.MATCH_THRESHOLD_SUGGEST)
                ?: continue
            // Только серая зона: 0.62..0.72. Выше — уже был бы применён автоматически.
            if (match.second < VoiceProfileStorage.MATCH_THRESHOLD_CONFIRM) {
                out[i] = match.first.name to match.second
            }
        }
        return out
    }

    private fun findEntryFor(timestamp: Long): DailyRecord.Entry? {
        val ctx = requireContext().applicationContext
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return RecordStorage.getEntry(ctx, date, timestamp)
    }

    // ── Preview playback ──────────────────────────────────────────────────────

    private fun playPreview(speakerIndex: Int) {
        val ctx = requireContext().applicationContext
        val snap = SpeakerCentroidStorage.get(ctx, timestamp) ?: return
        val offsetMs = snap.previewOffsetsMs.getOrNull(speakerIndex)?.toInt() ?: return
        val file = File(audioPath)
        if (!file.exists()) return

        stopPreview()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                seekTo(offsetMs)
                start()
            }
            player = mp
            adapter.setPlayingIndex(speakerIndex)
            val stopper = Runnable {
                stopPreview()
                adapter.setPlayingIndex(-1)
            }
            stopPreviewRunnable = stopper
            mainHandler.postDelayed(stopper, PREVIEW_MS.toLong())
            mp.setOnCompletionListener {
                stopPreviewRunnable?.let { mainHandler.removeCallbacks(it) }
                stopPreview()
                adapter.setPlayingIndex(-1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playPreview failed: ${e.message}", e)
            stopPreview()
        }
    }

    private fun stopPreview() {
        stopPreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        stopPreviewRunnable = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    // ── Rename flow ───────────────────────────────────────────────────────────

    private fun promptRename(speakerIndex: Int) {
        val ctx = requireContext().applicationContext
        val snap = SpeakerCentroidStorage.get(ctx, timestamp) ?: return
        val current = snap.labels.getOrNull(speakerIndex) ?: return

        val et = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Например: Александр"
            setText(if (current.length > 3) current else "")
            setSelection(text.length)
        }
        val padded = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(et)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Имя говорящего")
            .setView(padded)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) applyRename(speakerIndex, name)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyRename(speakerIndex: Int, name: String) {
        val ctx = requireContext().applicationContext
        if(name.isBlank() || name.any{it=='[' || it==']' || it=='\n' || it=='\r'}) {
            android.widget.Toast.makeText(ctx,"Введите имя без скобок и переносов строк",android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if(findEntryFor(timestamp)?.isEncrypted!=false)return
        val snap = SpeakerCentroidStorage.get(ctx, timestamp) ?: return
        val centroid = snap.centroids.getOrNull(speakerIndex) ?: return
        if (centroid.isEmpty()) return

        val oldLabel = snap.labels.getOrNull(speakerIndex) ?: return

        val existingId = snap.matchedProfileIds.getOrNull(speakerIndex)
        val profile = if (existingId != null) {
            val existing = VoiceProfileStorage.getById(ctx, existingId)
            if (existing != null && existing.name == name) {
                VoiceProfileStorage.addSample(ctx, existingId, centroid)
                existing
            } else {
                VoiceProfileStorage.upsert(ctx, VoiceProfile(name = name, samples = listOf(centroid)))
            }
        } else {
            // Проверяем: есть ли уже профиль с таким именем? Тогда добавим sample к нему.
            val existingByName = VoiceProfileStorage.getAll(ctx).firstOrNull { it.name == name }
            if (existingByName != null) {
                VoiceProfileStorage.addSample(ctx, existingByName.id, centroid)
                existingByName
            } else {
                VoiceProfileStorage.upsert(ctx, VoiceProfile(name = name, samples = listOf(centroid)))
            }
        }

        if(!RecordStorage.renameSpeaker(ctx,timestamp,speakerIndex,name,profile.id))return
        val fresh = SpeakerCentroidStorage.get(ctx, timestamp)
        adapter.refreshFrom(fresh, computeSuggestions(fresh))
        if(oldLabel!=name)parentFragmentManager.setFragmentResult(resultKey(timestamp),Bundle.EMPTY)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class SpeakerRowsAdapter(
        private var snap: SpeakerCentroidStorage.Snapshot?,
        private var suggestions: Map<Int, Pair<String, Float>>,
        private val audioPresent: Boolean,
        private val onRename: (Int) -> Unit,
        private val onPreview: (Int) -> Unit,
        private val onAcceptSuggestion: (Int, String) -> Unit,
    ) : RecyclerView.Adapter<SpeakerRowsAdapter.VH>() {

        private var playingIndex: Int = -1

        fun refreshFrom(newSnap: SpeakerCentroidStorage.Snapshot?, newSuggestions: Map<Int, Pair<String, Float>>) {
            snap = newSnap
            suggestions = newSuggestions
            notifyDataSetChanged()
        }

        fun setPlayingIndex(index: Int) {
            val old = playingIndex
            playingIndex = index
            if (old >= 0) notifyItemChanged(old)
            if (index >= 0) notifyItemChanged(index)
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar:  TextView    = v.findViewById(R.id.tvSpeakerAvatar)
            val name:    TextView    = v.findViewById(R.id.tvSpeakerName)
            val sub:     TextView    = v.findViewById(R.id.tvSpeakerSub)
            val preview: ImageButton = v.findViewById(R.id.btnSpeakerPreview)
            val action:  TextView    = v.findViewById(R.id.tvSpeakerAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_speaker_row, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = snap?.labels?.size ?: 0

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = snap ?: return
            val label = s.labels[position]
            val isNamed = s.matchedProfileIds.getOrNull(position) != null
            val suggestion = suggestions[position]

            // Цветной круглый аватар: фон = мягкий цвет, текст = насыщенный цвет.
            val fg = SpeakerColors.forIndex(position)
            val bg = SpeakerColors.softForIndex(position)
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
            holder.avatar.background = bgDrawable
            holder.avatar.setTextColor(fg)
            holder.avatar.text = (if (isNamed) label else label).take(1).uppercase()

            // Имя и подпись
            if (isNamed) {
                holder.name.text = label
                holder.sub.text = "Голос сохранён в профилях"
                holder.action.text = "Переименовать"
                holder.action.setOnClickListener { onRename(position) }
            } else if (suggestion != null) {
                holder.name.text = "Говорящий $label"
                holder.sub.text = "Похоже на ${suggestion.first} (%.0f%%)".format(suggestion.second * 100f)
                holder.action.text = "Это ${suggestion.first}"
                holder.action.setOnClickListener { onAcceptSuggestion(position, suggestion.first) }
            } else {
                holder.name.text = "Говорящий $label"
                holder.sub.text = "Нажмите, чтобы назвать"
                holder.action.text = "Именовать"
                holder.action.setOnClickListener { onRename(position) }
            }

            // Preview кнопка — только если есть аудиофайл и валидный offset.
            val hasOffset = (s.previewOffsetsMs.getOrNull(position) ?: 0L) > 0L
            if (audioPresent && hasOffset) {
                holder.preview.visibility = View.VISIBLE
                holder.preview.setImageResource(
                    if (playingIndex == position) R.drawable.ic_pause else R.drawable.ic_play
                )
                holder.preview.setOnClickListener { onPreview(position) }
            } else {
                holder.preview.visibility = View.GONE
            }

            // Тап по всей строке — открывает переименование (или подсказку тоже открывает переименование).
            holder.itemView.setOnClickListener {
                if (suggestion != null && !isNamed) onAcceptSuggestion(position, suggestion.first)
                else onRename(position)
            }
        }
    }
}
