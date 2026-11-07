package com.guzlik.voice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.*

data class DayItem(val date: String, val isSelected: Boolean = false)

class DayAdapter(
    private var days: List<DayItem>,
    private val onDaySelected: (String) -> Unit
) : RecyclerView.Adapter<DayAdapter.DayVH>() {

    private val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayFormat   = SimpleDateFormat("dd",        Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMM",       Locale.getDefault())

    inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        val card:   MaterialCardView = v.findViewById(R.id.cardDay)
        val tvDay:  TextView         = v.findViewById(R.id.tvDayNum)
        val tvMonth:TextView         = v.findViewById(R.id.tvMonth)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_day, parent, false)
        return DayVH(v)
    }

    override fun onBindViewHolder(holder: DayVH, position: Int) {
        val ctx = holder.itemView.context
        val item = days[position]
        try {
            val date = inputFormat.parse(item.date) ?: Date()
            holder.tvDay.text   = dayFormat.format(date)
            holder.tvMonth.text = monthFormat.format(date).uppercase()
        } catch (_: Exception) {
            holder.tvDay.text = "—"
        }

        if (item.isSelected) {
            holder.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.primary))
            holder.card.strokeWidth = 0
            holder.tvDay.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
            holder.tvMonth.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
        } else {
            holder.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
            holder.card.strokeWidth = 1
            holder.tvDay.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
            holder.tvMonth.setTextColor(ContextCompat.getColor(ctx, R.color.textMuted))
        }

        holder.itemView.setOnClickListener { onDaySelected(item.date) }
    }

    override fun getItemCount() = days.size

    fun updateDays(newDays: List<DayItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = days.size
            override fun getNewListSize() = newDays.size
            override fun areItemsTheSame(old: Int, new: Int) = days[old].date == newDays[new].date
            override fun areContentsTheSame(old: Int, new: Int) = days[old] == newDays[new]
        })
        days = newDays
        diff.dispatchUpdatesTo(this)
    }
}
