package com.guzlik.voice

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClientsFragment : Fragment(R.layout.fragment_clients) {

    private lateinit var rvClients      : RecyclerView
    private lateinit var etSearch       : TextInputEditText
    private lateinit var emptyState     : LinearLayout
    private lateinit var proPromoState  : LinearLayout
    private lateinit var fabAddClient   : FloatingActionButton
    private lateinit var btnUnlockPro   : MaterialButton
    private lateinit var adapter        : ClientsAdapter

    private var allRows: List<ClientsAdapter.Row> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvClients     = view.findViewById(R.id.rvClients)
        etSearch      = view.findViewById(R.id.etClientSearch)
        emptyState    = view.findViewById(R.id.emptyState)
        proPromoState = view.findViewById(R.id.proPromoState)
        fabAddClient  = view.findViewById(R.id.fabAddClient)
        btnUnlockPro  = view.findViewById(R.id.btnUnlockPro)

        rvClients.layoutManager = LinearLayoutManager(requireContext())
        adapter = ClientsAdapter(emptyList()) { client -> openDetail(client) }
        rvClients.adapter = adapter

        fabAddClient.setOnClickListener {
            if (!ProFeatures.requirePro(requireContext(), ProFeatures.Feature.CLIENT_MANAGEMENT)) return@setOnClickListener
            openDetail(null)
        }

        btnUnlockPro.setOnClickListener {
            ProFeatures.requirePro(requireContext(), ProFeatures.Feature.CLIENT_MANAGEMENT)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilter() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val ctx = context ?: return
        val isPro = ProFeatures.isProUser(ctx)

        if (!isPro) {
            proPromoState.visibility = View.VISIBLE
            emptyState.visibility    = View.GONE
            rvClients.visibility     = View.GONE
            fabAddClient.hide()
            return
        }

        proPromoState.visibility = View.GONE
        fabAddClient.show()
        loadClients()
    }

    private fun loadClients() {
        val ctx = context?.applicationContext ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val clients = ClientStorage.getAll(ctx)
            val rows = clients.map { c ->
                val count = RecordStorage.getEntriesForClient(ctx, c.id).size
                ClientsAdapter.Row(c, count)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                allRows = rows
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (query.isEmpty()) allRows
                       else allRows.filter { row ->
                           row.client.name.lowercase().contains(query) ||
                           (row.client.phone?.lowercase()?.contains(query) == true) ||
                           (row.client.email?.lowercase()?.contains(query) == true)
                       }
        adapter.update(filtered)

        if (filtered.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            rvClients.visibility  = View.GONE
        } else {
            emptyState.visibility = View.GONE
            rvClients.visibility  = View.VISIBLE
        }
    }

    private fun openDetail(client: Client?) {
        val ctx = context ?: return
        val intent = Intent(ctx, ClientDetailActivity::class.java)
        if (client != null) intent.putExtra(ClientDetailActivity.EXTRA_CLIENT_ID, client.id)
        startActivity(intent)
    }
}
