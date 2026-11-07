package com.example.voicemind

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Карточка клиента. Открывается как из ClientsFragment (создание/редактирование),
 * так и потенциально из привязки к записи. При отсутствии EXTRA_CLIENT_ID работает
 * в режиме "новый клиент".
 */
class ClientDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLIENT_ID = "client_id"
    }

    private lateinit var etName        : TextInputEditText
    private lateinit var etPhone       : TextInputEditText
    private lateinit var etEmail       : TextInputEditText
    private lateinit var etNotes       : TextInputEditText
    private lateinit var btnSave       : MaterialButton
    private lateinit var btnBack       : ImageButton
    private lateinit var btnDelete     : ImageButton
    private lateinit var tvTitle       : TextView
    private lateinit var entriesHeader : LinearLayout
    private lateinit var tvEntriesCount: TextView
    private lateinit var rvEntries     : RecyclerView

    private var existingClient: Client? = null

    private val fmtDay = SimpleDateFormat("d MMMM yyyy · HH:mm", Locale("ru"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccessGate.check(this)) return
        setContentView(R.layout.activity_client_detail)

        etName         = findViewById(R.id.etName)
        etPhone        = findViewById(R.id.etPhone)
        etEmail        = findViewById(R.id.etEmail)
        etNotes        = findViewById(R.id.etNotes)
        btnSave        = findViewById(R.id.btnSave)
        btnBack        = findViewById(R.id.btnBack)
        btnDelete      = findViewById(R.id.btnDelete)
        tvTitle        = findViewById(R.id.tvTitle)
        entriesHeader  = findViewById(R.id.entriesHeader)
        tvEntriesCount = findViewById(R.id.tvEntriesCount)
        rvEntries      = findViewById(R.id.rvClientEntries)

        rvEntries.layoutManager = LinearLayoutManager(this)

        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { save() }

        val id = intent.getStringExtra(EXTRA_CLIENT_ID)
        if (id == null) {
            tvTitle.text = "Новый клиент"
            btnDelete.visibility = View.GONE
        } else {
            loadClient(id)
        }
    }

    private fun loadClient(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val client = ClientStorage.getById(this@ClientDetailActivity, id)
            val entries = if (client != null) RecordStorage.getEntriesForClient(this@ClientDetailActivity, client.id)
                          else emptyList()
            withContext(Dispatchers.Main) {
                if (client == null) { finish(); return@withContext }
                existingClient = client
                fillForm(client)
                renderEntries(entries)
            }
        }
    }

    private fun fillForm(c: Client) {
        tvTitle.text = c.name
        etName.setText(c.name)
        etPhone.setText(c.phone.orEmpty())
        etEmail.setText(c.email.orEmpty())
        etNotes.setText(c.notes.orEmpty())
        btnDelete.visibility = View.VISIBLE
        btnDelete.setOnClickListener { confirmDelete(c) }
    }

    private fun renderEntries(entries: List<Pair<String, DailyRecord.Entry>>) {
        if (entries.isEmpty()) {
            entriesHeader.visibility = View.GONE
            rvEntries.adapter = null
            return
        }
        entriesHeader.visibility = View.VISIBLE
        tvEntriesCount.text = "${entries.size}"
        rvEntries.adapter = EntriesAdapter(entries)
    }

    private fun save() {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            etName.error = "Введите имя"
            return
        }
        val base = existingClient?.copy(
            name  = name,
            phone = etPhone.text?.toString()?.trim()?.ifEmpty { null },
            email = etEmail.text?.toString()?.trim()?.ifEmpty { null },
            notes = etNotes.text?.toString()?.trim()?.ifEmpty { null }
        ) ?: Client(
            name  = name,
            phone = etPhone.text?.toString()?.trim()?.ifEmpty { null },
            email = etEmail.text?.toString()?.trim()?.ifEmpty { null },
            notes = etNotes.text?.toString()?.trim()?.ifEmpty { null }
        )

        lifecycleScope.launch(Dispatchers.IO) {
            ClientStorage.upsert(this@ClientDetailActivity, base)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ClientDetailActivity, "Сохранено", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun confirmDelete(client: Client) {
        AlertDialog.Builder(this)
            .setTitle("Удалить клиента?")
            .setMessage("Карточка будет удалена. Записи останутся, но потеряют привязку к клиенту.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ClientStorage.delete(this@ClientDetailActivity, client.id)
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Inline adapter for client's entries ─────────────────────────────

    private inner class EntriesAdapter(
        private val items: List<Pair<String, DailyRecord.Entry>>
    ) : RecyclerView.Adapter<EntriesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvDate    : TextView = v.findViewById(R.id.tvEntryDate)
            val tvPreview : TextView = v.findViewById(R.id.tvEntryPreview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_client_entry, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val (_, entry) = items[position]
            h.tvDate.text    = fmtDay.format(Date(entry.timestamp))
            h.tvPreview.text = entry.text.replace('\n', ' ').take(180)

            h.itemView.setOnClickListener {
                val date = items[position].first
                val log = LogEntry(
                    timestamp   = entry.timestamp,
                    rawText     = entry.text,
                    structured  = entry.structured,
                    keyThoughts = entry.keyThoughts,
                    tags        = entry.tags,
                    source      = entry.source,
                    audioPath   = entry.audioPath,
                    isEncrypted = entry.isEncrypted,
                    segments    = entry.segments
                )
                TranscriptionDetailSheet.newInstance(log, date)
                    .show(supportFragmentManager, "entry_detail")
            }
        }
    }
}
