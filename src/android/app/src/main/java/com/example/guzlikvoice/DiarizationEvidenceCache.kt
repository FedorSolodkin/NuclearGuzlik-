package com.example.guzlikvoice

import android.content.Context
import java.io.File

/** Last recording only, RAM only. Never persisted or included in backups. */
object DiarizationEvidenceCache {
    data class Key(val timestamp: Long, val audioPath: String, val audioBytes: Long,
        val audioModified: Long, val models: String)
    data class Ticket(val timestamp: Long, val epoch: Long, val revision: Long)
    private data class Entry(val key: Key, val ticket: Ticket, val evidence: SpeakerDiarizer.EvidenceSnapshot)
    private const val MAX_BYTES = 8L * 1024 * 1024
    private var epoch = 0L
    private val revisions = mutableMapOf<Long, Long>()
    private var last: Entry? = null

    fun keyFor(ctx: Context, audio: File, timestamp: Long): Key {
        val models = listOf(SpeakerEmbedder.MODEL_NAME, PyannoteSegmenter.MODEL_NAME).flatMap { name ->
            listOfNotNull(File(ctx.filesDir, name), ctx.getExternalFilesDir(null)?.let { File(it, name) })
        }
        return Key(timestamp, audio.absolutePath, audio.length(), audio.lastModified(),
            // Refinement plans depend on the voice inventory. A completed plan
            // from the old cluster-support policy cannot be treated as current.
            "evidence-v5-complete-pcm|" + models.joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" })
    }

    @Synchronized fun ticket(timestamp: Long) = Ticket(timestamp, epoch, revisions[timestamp] ?: 0L)
    @Synchronized fun isCurrent(t: Ticket) = t.epoch == epoch && t.revision == (revisions[t.timestamp] ?: 0L)

    @Synchronized fun get(key: Key, ticket: Ticket): SpeakerDiarizer.EvidenceSnapshot? =
        last?.takeIf { it.key == key && it.ticket == ticket && isCurrent(ticket) }?.evidence

    @Synchronized fun put(key: Key, ticket: Ticket, evidence: SpeakerDiarizer.EvidenceSnapshot): Boolean {
        if (key.timestamp != ticket.timestamp || !isCurrent(ticket)) return false
        val bytes = evidence.embeddings.sumOf { it.size * 4L + 32 } + evidence.regions.size * 48L + evidence.windows.size * 32L +
            (evidence.boundaryRefinement?.observations?.sumOf {it.embedding.size*4L+64} ?: 0L)
        if (bytes > MAX_BYTES || evidence.embeddings.isEmpty()) return false
        last = Entry(key, ticket, evidence)
        return true
    }

    /** Rejects a late writer too, not just the entry currently present. */
    @Synchronized fun remove(timestamp: Long) {
        revisions[timestamp] = (revisions[timestamp] ?: 0L) + 1
        if (last?.key?.timestamp == timestamp) last = null
    }

    @Synchronized fun clearAll() { epoch++; revisions.clear(); last = null }
}
