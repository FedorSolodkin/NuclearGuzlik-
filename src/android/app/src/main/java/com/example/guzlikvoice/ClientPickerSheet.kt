package com.example.guzlikvoice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet для выбора клиента, к которому будет привязана запись.
 * Отдельно поддерживает варианты «Без клиента» и «Создать нового клиента».
 */
class ClientPickerSheet : BottomSheetDialogFragment() {

    /** id уже привязанного клиента (для отметки галочкой). null = нет привязки. */
    var currentClientId: String? = null

    /** Вызывается когда пользователь выбрал клиента или сбросил привязку (null). */
    var onClientSelected: ((Client?) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_client_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rowNone  = view.findViewById<LinearLayout>(R.id.rowNone)
        val rowNew   = view.findViewById<LinearLayout>(R.id.rowNew)
        val tvCheck  = view.findViewById<TextView>(R.id.tvCheckNone)
        val rv       = view.findViewById<RecyclerView>(R.id.rvClientPick)
        val tvEmpty  = view.findViewById<TextView>(R.id.tvEmpty)

        tvCheck.visibility = if (currentClientId == null) View.VISIBLE else View.GONE

        rowNone.setOnClickListener {
            onClientSelected?.invoke(null)
            dismiss()
        }
        rowNew.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, ClientDetailActivity::class.java))
            dismiss()
        }

        val clients = ClientStorage.getAll(requireContext())
        if (clients.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rv.visibility      = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility      = View.VISIBLE
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = PickAdapter(clients) { client ->
                onClientSelected?.invoke(client)
                dismiss()
            }
        }
    }

    private inner class PickAdapter(
        private val items: List<Client>,
        private val onClick: (Client) -> Unit
    ) : RecyclerView.Adapter<PickAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvAvatar : TextView = v.findViewById(R.id.tvPickAvatar)
            val tvName   : TextView = v.findViewById(R.id.tvPickName)
            val tvMeta   : TextView = v.findViewById(R.id.tvPickMeta)
            val tvCheck  : TextView = v.findViewById(R.id.tvPickCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_client_pick, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            h.tvAvatar.text = initials(c.name)
            h.tvName.text   = c.name
            h.tvMeta.text   = listOfNotNull(
                c.phone?.takeIf { it.isNotBlank() },
                c.email?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            h.tvMeta.visibility = if (h.tvMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE
            h.tvCheck.visibility = if (c.id == currentClientId) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { onClick(c) }
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
}
