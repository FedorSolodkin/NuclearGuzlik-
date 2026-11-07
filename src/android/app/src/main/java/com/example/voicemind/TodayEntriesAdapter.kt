package com.example.voicemind

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class TodayEntriesAdapter(
    private val entries: List<DailyRecord.Entry>
) : RecyclerView.Adapter<TodayEntriesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvText: TextView = view.findViewById(R.id.tvText)
        val tvSource: TextView = view.findViewById(R.id.tvSource)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_today_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
        holder.tvTime.text = time
        holder.tvText.text = entry.text
        holder.tvSource.text = if (entry.source == "voice") "Голос" else "Текст"
    }

    override fun getItemCount() = entries.size
}
