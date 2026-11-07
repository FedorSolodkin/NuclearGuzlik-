package com.example.voicemind

import kotlin.math.roundToInt

/** Reconstruct activity across windows: conflicting IDs are not evidence of overlap. */
object SpeakerActivityFusion {
    data class Window(val startMs: Long, val endMs: Long)
    data class Evidence(val startMs: Long, val endMs: Long, val speaker: Int, val window: Int)
    private data class Event(val window: Int, val speaker: Int, val delta: Int)

    fun fuse(windows: List<Window>, evidence: List<Evidence>, checkCancelled: () -> Unit = {}): List<SpeakerTimeline.Region> {
        val events = sortedMapOf<Long, MutableList<Event>>()
        fun event(t: Long, w: Int, s: Int, d: Int) { events.getOrPut(t) { mutableListOf() }.add(Event(w, s, d)) }
        windows.forEachIndexed { i, w -> event(w.startMs, i, -1, 1); event(w.endMs, i, -1, -1) }
        for (e in evidence) {
            require(e.window in windows.indices && e.speaker >= 0)
            val w = windows[e.window]
            val start = maxOf(w.startMs, e.startMs); val end = minOf(w.endMs, e.endMs)
            if (end > start) { event(start, e.window, e.speaker, 1); event(end, e.window, e.speaker, -1) }
        }
        val covered = mutableSetOf<Int>()
        val active = Array(windows.size) { mutableMapOf<Int, Int>() }
        val result = mutableListOf<SpeakerTimeline.Region>()
        val open = mutableMapOf<Int, Long>()
        val times = events.keys.toList()
        for (i in times.indices) {
            checkCancelled()
            val t = times[i]
            for (e in events.getValue(t)) {
                if (e.speaker < 0) { if (e.delta > 0) covered.add(e.window) else covered.remove(e.window) }
                else {
                    val counts = active[e.window]
                    val n = (counts[e.speaker] ?: 0) + e.delta
                    if (n <= 0) counts.remove(e.speaker) else counts[e.speaker] = n
                }
            }
            val next = times.getOrNull(i + 1)
            val selected = if (covered.isEmpty() || next == null) emptySet() else {
                val count = (covered.sumOf { active[it].size }.toDouble() / covered.size).roundToInt()
                val votes = mutableMapOf<Int, Double>()
                val mid = t + (next - t) / 2.0
                for (w in covered) {
                    val window = windows[w]
                    // Edges carry less context than the middle of a segmentation window.
                    val weight = minOf(mid - window.startMs, window.endMs - mid).coerceAtLeast(1.0)
                    for (speaker in active[w].keys) votes[speaker] = (votes[speaker] ?: 0.0) + weight
                }
                votes.keys.sortedWith(compareByDescending<Int> { votes.getValue(it) }.thenBy { it }).take(count).toSet()
            }
            for (speaker in open.keys.toList()) if (speaker !in selected) {
                val start = open.remove(speaker)!!
                if (t > start) result.add(SpeakerTimeline.Region(start, t, speaker))
            }
            for (speaker in selected) open.putIfAbsent(speaker, t)
        }
        return result.sortedWith(compareBy({ it.startMs }, { it.speaker }))
    }
}
