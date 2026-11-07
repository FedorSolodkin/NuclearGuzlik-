package com.example.guzlikvoice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * «Сегодня»: события календаря на сегодня + связанные записи.
 * Если календарь не подключён — большая кнопка перехода в CalendarConnectActivity.
 */
class TodayFragment : Fragment(R.layout.fragment_today) {

    private lateinit var rv:              RecyclerView
    private lateinit var tvEmpty:         TextView
    private lateinit var emptyBox:        LinearLayout
    private lateinit var btnConnect:      MaterialButton
    private lateinit var progress:        ProgressBar
    private lateinit var btnRefresh:      MaterialButton
    private lateinit var btnEmptyRecord:  MaterialButton

    private val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv             = view.findViewById(R.id.rvTodayEvents)
        tvEmpty        = view.findViewById(R.id.tvTodayEmpty)
        emptyBox       = view.findViewById(R.id.todayEmptyStateBox)
        btnConnect     = view.findViewById(R.id.btnConnectCalendar)
        progress       = view.findViewById(R.id.progressToday)
        btnRefresh     = view.findViewById(R.id.btnRefreshToday)
        btnEmptyRecord = view.findViewById(R.id.btnEmptyRecordToday)

        btnEmptyRecord.setOnClickListener { goToRecord() }

        rv.layoutManager = LinearLayoutManager(requireContext())

        btnConnect.setOnClickListener {
            val ctx = requireContext()
            if (!ProFeatures.requirePro(ctx, ProFeatures.Feature.CALENDAR_INTEGRATION)) return@setOnClickListener
            AlertDialog.Builder(ctx)
                .setTitle("Какой календарь подключить?")
                .setItems(arrayOf("Яндекс Календарь (CalDAV)", "Google Календарь")) { _, which ->
                    val target = if (which == 0) YandexConnectActivity::class.java
                                 else CalendarConnectActivity::class.java
                    startActivity(Intent(ctx, target))
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        btnRefresh.setOnClickListener { refresh(force = true) }
    }

    override fun onResume() {
        super.onResume()
        refresh(force = false)
    }

    private fun refresh(force: Boolean) {
        val ctx = context?.applicationContext ?: return
        if (!CalendarProvider.isAnyConnected(ctx)) {
            renderNotConnected()
            return
        }

        val cached = if (force) null else CalendarEventCache.getFresh(ctx)
        if (cached != null) {
            renderEvents(filterToToday(cached))
            return
        }

        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val (from, to) = todayRange()
            val fetched = CalendarProvider.fetchEvents(ctx, from, to)
            CalendarEventCache.save(ctx, fetched)
            MeetingNotificationScheduler.rescheduleAll(ctx, fetched)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                progress.visibility = View.GONE
                renderEvents(filterToToday(fetched))
            }
        }
    }

    private fun todayRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val from = cal.timeInMillis
        return from to (from + 24L * 60 * 60 * 1000)
    }

    private fun filterToToday(events: List<CalendarEvent>): List<CalendarEvent> {
        val (from, to) = todayRange()
        return events.filter { it.startMillis in from until to }.sortedBy { it.startMillis }
    }

    /** TodayFragment теперь живёт только в отдельном ToolsActivity (не в главном
     *  ViewPager2), поэтому "к записи" — это переход в MainActivity на вкладку
     *  "Запись" (позиция 0) и закрытие текущего экрана инструментов. */
    private fun goToRecord() {
        val ctx = context ?: return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_tab", "record")
        }
        startActivity(intent)
        activity?.finish()
    }

    private fun renderNotConnected() {
        emptyBox.visibility        = View.VISIBLE
        btnConnect.visibility      = View.VISIBLE
        btnRefresh.visibility      = View.GONE
        // Даём кнопку записи и до подключения календаря — календарь опция,
        // а не обязательный шаг. Иначе пользователь застревает на этой вкладке.
        btnEmptyRecord.visibility  = View.VISIBLE
        rv.adapter = null
        tvEmpty.text = "Календарь не подключён.\nПодключите — увидите встречи. Или сразу к записи."
    }

    private fun renderEvents(events: List<CalendarEvent>) {
        btnConnect.visibility = View.GONE
        btnRefresh.visibility = View.VISIBLE
        if (events.isEmpty()) {
            emptyBox.visibility       = View.VISIBLE
            btnEmptyRecord.visibility = View.VISIBLE
            tvEmpty.text = "На сегодня встреч нет."
            rv.adapter = null
            return
        }
        emptyBox.visibility       = View.GONE
        btnEmptyRecord.visibility = View.GONE
        rv.adapter = EventsAdapter(events, context?.applicationContext)
    }

    // ── Inline adapter ────────────────────────────────────────────────────────

    private inner class EventsAdapter(
        private val items: List<CalendarEvent>,
        private val appCtx: android.content.Context?
    ) : RecyclerView.Adapter<EventsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvEventTitle)
            val tvTime : TextView = v.findViewById(R.id.tvEventTime)
            val tvLoc  : TextView = v.findViewById(R.id.tvEventLocation)
            val tvBadge: TextView = v.findViewById(R.id.tvEventLinkedBadge)
            val btnRec : MaterialButton = v.findViewById(R.id.btnEventRecord)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_today_event, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.tvTitle.text = e.title
            h.tvTime.text  = "${fmtTime.format(Date(e.startMillis))} – ${fmtTime.format(Date(e.endMillis))}"

            if (e.location.isNullOrBlank()) h.tvLoc.visibility = View.GONE
            else { h.tvLoc.visibility = View.VISIBLE; h.tvLoc.text = e.location }

            val alreadyLinked = appCtx?.let {
                RecordStorage.getEntriesForCalendarEvent(it, e.id).isNotEmpty()
            } ?: false
            h.tvBadge.visibility = if (alreadyLinked) View.VISIBLE else View.GONE

            h.btnRec.setOnClickListener {
                // Явный выбор события — RecordStorage.addToToday подхватит его из
                // PendingCalendarLink при сохранении следующей записи.
                PendingCalendarLink.set(e.id)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Запись будет привязана к «${e.title}»",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                goToRecord()
            }
        }
    }
}
