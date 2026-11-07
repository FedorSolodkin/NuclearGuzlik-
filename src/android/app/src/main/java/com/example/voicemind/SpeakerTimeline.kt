package com.example.voicemind

/** Indexed, unioned speech evidence. Overlapping analysis windows are not extra speech. */
class SpeakerTimeline(regions: List<Region>) {
    data class Region(val startMs: Long, val endMs: Long, val speaker: Int)
    companion object { const val UNKNOWN = -1; const val OVERLAP = -2 }
    data class Decision(val speaker: Int, val candidates: List<Int>, val coverage: Float, val margin: Float)
    private val runs: List<Region>
    private val ends: List<Region>
    private val prefixEnd: LongArray
    init {
        val merged = mutableListOf<Region>()
        for ((_, sameSpeaker) in regions.filter { it.endMs > it.startMs && it.speaker >= 0 }.groupBy { it.speaker }) {
            var current: Region? = null
            for (r in sameSpeaker.sortedBy { it.startMs }) {
                val c = current
                if (c != null && r.startMs <= c.endMs) current = c.copy(endMs = maxOf(c.endMs, r.endMs))
                else { c?.let { merged.add(it) }; current = r }
            }
            current?.let { merged.add(it) }
        }
        runs = merged.sortedWith(compareBy({ it.startMs }, { it.speaker }))
        ends = runs.sortedWith(compareBy({ it.endMs }, { it.speaker }))
        prefixEnd = LongArray(runs.size)
        for (i in runs.indices) prefixEnd[i] = maxOf(if (i > 0) prefixEnd[i - 1] else Long.MIN_VALUE, runs[i].endMs)
    }

    fun assign(startMs: Long, endMs: Long): Int {
        if (runs.isEmpty()) return 0
        val end = maxOf(startMs + 1, endMs) // Whisper can emit zero-duration words.
        var lo = 0; var hi = runs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (prefixEnd[mid] <= startMs) lo = mid + 1 else hi = mid }
        val votes = mutableMapOf<Int, Long>()
        var i = lo
        while (i < runs.size && runs[i].startMs < end) {
            val r = runs[i++]
            val overlap = minOf(end, r.endMs) - maxOf(startMs, r.startMs)
            if (overlap > 0) votes[r.speaker] = (votes[r.speaker] ?: 0L) + overlap
        }
        if (votes.isNotEmpty()) return votes.keys.minWith(compareBy<Int> { -votes.getValue(it) }.thenBy { it })
        // Only immediate temporal neighbours can be closest; no full scan per word.
        lo = 0; hi = ends.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ends[mid].endMs <= startMs) lo = mid + 1 else hi = mid }
        val before = ends.getOrNull(lo - 1)
        lo = 0; hi = runs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (runs[mid].startMs < end) lo = mid + 1 else hi = mid }
        val after = runs.getOrNull(lo)
        return when {
            before == null -> after!!.speaker
            after == null -> before.speaker
            startMs - before.endMs <= after.startMs - end -> before.speaker
            else -> after.speaker
        }
    }

    /** No identity extrapolation across silence. Simultaneity is measured in time,
     * not inferred from two different sequential speakers within a long word. */
    fun decide(startMs: Long, endMs: Long): Decision {
        // Token timestamp heuristics can collapse a whole word to a point. Near a
        // change of voice such a point cannot identify the word's owner reliably.
        if (endMs <= startMs) return decide(maxOf(0L, startMs - 80), maxOf(1L, startMs + 80))
        val end = endMs
        var lo = 0; var hi = runs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (prefixEnd[mid] <= startMs) lo = mid + 1 else hi = mid }
        val events = sortedMapOf<Long, MutableList<Pair<Int, Int>>>()
        var i = lo
        while (i < runs.size && runs[i].startMs < end) {
            val r = runs[i++]
            val a = maxOf(startMs, r.startMs); val b = minOf(end, r.endMs)
            if (b > a) {
                events.getOrPut(a) { mutableListOf() }.add(r.speaker to 1)
                events.getOrPut(b) { mutableListOf() }.add(r.speaker to -1)
            }
        }
        val votes = mutableMapOf<Int, Long>(); val active = mutableSetOf<Int>()
        var covered = 0L; var simultaneous = 0L; var previous = startMs
        for ((t, changes) in events) {
            val dt = t - previous
            if (active.isNotEmpty()) covered += dt
            if (active.size > 1) simultaneous += dt
            for (speaker in active) votes[speaker] = (votes[speaker] ?: 0L) + dt
            for ((speaker, delta) in changes) if (delta > 0) active.add(speaker) else active.remove(speaker)
            previous = t
        }
        val ranked = votes.keys.sortedWith(compareByDescending<Int> { votes.getValue(it) }.thenBy { it })
        val duration = (end - startMs).toFloat()
        val coverage = covered / duration
        val top = ranked.firstOrNull()?.let { votes.getValue(it) } ?: 0L
        val second = ranked.getOrNull(1)?.let { votes.getValue(it) } ?: 0L
        val margin = if (top > 0) (top - second).toFloat() / top else 0f
        val speaker = when {
            simultaneous >= minOf(60L, (end - startMs) / 2) && simultaneous / duration >= 0.2f -> OVERLAP
            coverage < 0.3f || margin < 0.25f -> UNKNOWN
            else -> ranked.first()
        }
        return Decision(speaker, ranked, coverage, margin)
    }
}
