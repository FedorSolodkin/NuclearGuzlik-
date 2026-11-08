package com.guzlik.voice

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

    private fun hasContinuousOnset(speaker: Int, startMs: Long, latestStartMs: Long, nextWordMs: Long): Boolean {
        var lo = 0; var hi = runs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (runs[mid].startMs < startMs) lo = mid + 1 else hi = mid }
        var i = lo
        while (i < runs.size && runs[i].startMs <= latestStartMs) {
            val r = runs[i++]
            if (r.speaker == speaker && r.endMs > nextWordMs) return true
        }
        return false
    }

    /** Repair isolated short word edges only, using unchanged acoustic decisions
     * on adjacent words. Never bridge a change of speaker or an overlap. */
    fun decideWords(intervals: List<Pair<Long, Long>>): IntArray {
        val decisions = intervals.map { (a, b) -> decide(a, b) }
        val original = decisions.map { it.speaker }
        return IntArray(intervals.size) { i ->
            val (a, b) = intervals[i]
            val d = decisions[i]
            if (d.speaker != UNKNOWN || b <= a || b - a > 400) d.speaker
            else {
                val before = original.getOrNull(i - 1)
                val after = original.getOrNull(i + 1)
                when {
                    before != null && before >= 0 && before == after &&
                        a - intervals[i - 1].second in 0..100 &&
                        intervals[i + 1].first - b in 0..100 &&
                        d.candidates == listOf(before) -> before
                    // A short word at a new turn may have only its voiced end
                    // detected. Extend its unique onset by at most two 80-ms
                    // timestamp steps, only when that SAME region continues into
                    // the next confidently assigned word. Silence/other voices
                    // cannot supply this evidence.
                    after != null && after >= 0 && d.candidates == listOf(after) &&
                        intervals[i + 1].first - b in 0..100 &&
                        hasContinuousOnset(after, a, minOf(b, a + 160), intervals[i + 1].first) -> after
                    // VAD can miss the unvoiced onset of the very first word.
                    i == 0 && after != null && after >= 0 &&
                        intervals[1].first - b in 0..100 && d.candidates.isEmpty() &&
                        runs.firstOrNull()?.let { it.speaker == after && it.startMs - b in 0..80 } == true -> after
                    // The final word can lose its unvoiced ending in exactly
                    // the same way. Require the last acoustic region to belong
                    // to the preceding voice; never bridge a different speaker.
                    i == intervals.lastIndex && before != null && before >= 0 &&
                        a - intervals[i - 1].second in 0..100 &&
                        (d.candidates.isEmpty() || d.candidates == listOf(before)) &&
                        ends.lastOrNull()?.let { it.speaker == before && it.endMs in (a - 80)..b } == true -> before
                    else -> UNKNOWN
                }
            }
        }
    }
}
