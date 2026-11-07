package com.guzlik.voice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ClientsAdapter(
    private var items: List<Row>,
    private val onClick: (Client) -> Unit
) : RecyclerView.Adapter<ClientsAdapter.VH>() {

    data class Row(val client: Client, val entryCount: Int)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvAvatar     : TextView = v.findViewById(R.id.tvAvatar)
        val tvClientName : TextView = v.findViewById(R.id.tvClientName)
        val tvClientMeta : TextView = v.findViewById(R.id.tvClientMeta)
        val tvEntryCount : TextView = v.findViewById(R.id.tvEntryCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_client, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = items[position]
        val c = row.client
        h.tvAvatar.text     = initials(c.name)
        h.tvClientName.text = c.name

        val metaParts = buildList {
            c.phone?.takeIf { it.isNotBlank() }?.let { add(it) }
            c.email?.takeIf { it.isNotBlank() }?.let { add(it) }
            c.notes?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        h.tvClientMeta.text = metaParts.joinToString(" · ").ifEmpty { "Нет контактов" }

        h.tvEntryCount.text = "${row.entryCount} зап."

        h.itemView.setOnClickListener { onClick(c) }
    }

    fun update(newItems: List<Row>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else            -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }
}
