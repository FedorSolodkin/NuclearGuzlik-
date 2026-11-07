package com.example.guzlikvoice

import java.util.Locale

/** Re-align only local boundary clips. Inference supplies timing, never replacement text. */
object BoundaryWordTiming {
    const val VERSION = 3
    data class Clip(val startMs: Long, val endMs: Long)
    private data class Ref(val segment: Int, val index: Int, val word: WhisperHelper.Word)
    private fun refs(segments: List<WhisperHelper.Segment>) = segments.flatMapIndexed { s, part ->
        part.words.orEmpty().mapIndexed { i, w -> Ref(s, i, w) }
    }
    private fun key(text: String) = text.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT).replace('ё', 'е')

    fun plan(segments: List<WhisperHelper.Segment>, turns: List<SpeakerTimeline.Region>, durationMs: Long): List<Clip> {
        val words = refs(segments).map { it.word }
        if (words.isEmpty()) return emptyList()
        val proposed = turns.sortedBy { it.startMs }.zipWithNext().mapNotNull { (before, after) ->
            if (before.speaker == after.speaker || after.startMs - before.endMs !in 0..2000) return@mapNotNull null
            val boundary = (before.endMs + after.startMs) / 2
            val split = words.indexOfFirst { it.startMs >= boundary }.let { if (it < 0) words.size else it }
            val nearby = words.subList(maxOf(0, split - 3), minOf(words.size, split + 3))
            if (nearby.none { it.boundaryPassVersion < VERSION }) return@mapNotNull null
            Clip(maxOf(0, boundary - 4000), minOf(durationMs, boundary + 4000))
        }
        val merged = mutableListOf<Clip>()
        for (clip in proposed.sortedBy { it.startMs }) {
            val last = merged.lastOrNull()
            if (last != null && clip.startMs <= last.endMs && maxOf(last.endMs, clip.endMs) - last.startMs <= 16000)
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, clip.endMs))
            else if (clip.endMs > clip.startMs) merged.add(clip)
        }
        return merged
    }

    fun apply(segments: List<WhisperHelper.Segment>, clip: Clip, refined: List<WhisperHelper.Word>,
              turns: List<SpeakerTimeline.Region>): List<WhisperHelper.Segment> {
        val all = refs(segments)
        val local = all.filter { it.word.startMs < clip.endMs && it.word.endMs > clip.startMs }
        val raw = refined.filter { it.startMs >= clip.startMs && it.endMs <= clip.endMs && it.endMs > it.startMs && key(it.text).isNotEmpty() }
        // A local decode can join a name with a hyphen while the saved text uses
        // separate words. Match the same letters without changing the saved text.
        val localKeys = local.map { key(it.word.text) }
        val fresh = raw.flatMap { word ->
            val joined = key(word.text)
            val matches = (2..3).flatMap { size ->
                (0..(local.size - size)).mapNotNull { start ->
                    val keys = localKeys.subList(start, start + size)
                    if (keys.all { it.isNotEmpty() } && keys.joinToString("") == joined)
                        local.subList(start, start + size) else null
                }
            }
            if (joined in localKeys || matches.size != 1) listOf(word)
            else {
                val matched = matches.single()
                var offset = 0L
                matched.map { ref ->
                    val next = offset + key(ref.word.text).length
                    val duration = word.endMs - word.startMs
                    word.copy(text = ref.word.text, startMs = word.startMs + duration * offset / joined.length,
                        endMs = word.startMs + duration * next / joined.length).also { offset = next }
                }
            }
        }
        if (local.isEmpty() || fresh.isEmpty()) return segments
        val a = local.map { key(it.word.text) }; val b = fresh.map { key(it.text) }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else maxOf(dp[i + 1][j], dp[i][j + 1])
        val pairs = mutableMapOf<Int, Int>(); var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { pairs[i++] = j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        fun uniqueContext(i: Int, j: Int): Boolean {
            val left = i > 0 && j > 0 && pairs[i - 1] == j - 1 && a[i - 1] != a[i]
            val right = i + 1 < a.size && j + 1 < b.size && pairs[i + 1] == j + 1 && a[i + 1] != a[i]
            if (!left && !right) return false
            val from = if (left) i - 1 else i; val to = if (right) i + 2 else i + 1
            val signature = a.subList(from, to)
            fun count(keys: List<String>) = (0..(keys.size - signature.size)).count {
                keys.subList(it, it + signature.size) == signature
            }
            return count(a) == 1 && count(b) == 1
        }
        val timeline = SpeakerTimeline(turns)
        val replacements = mutableMapOf<Pair<Int, Int>, WhisperHelper.Word>()
        for ((at, next) in pairs) {
            val old = local[at]; val candidate = fresh[next]
            if (candidate.p < .35f || !uniqueContext(at, next)) continue
            if (candidate.startMs < clip.startMs + 250 || candidate.endMs > clip.endMs - 250) continue
            if (kotlin.math.abs((candidate.startMs + candidate.endMs - old.word.startMs - old.word.endMs) / 2) > 3500) continue
            // A correctly aligned word may still straddle a pause. Keep its timing so
            // it cannot block the neighbouring matches; identity stays UNKNOWN.
            if (timeline.decide(candidate.startMs, candidate.endMs).speaker == SpeakerTimeline.OVERLAP) continue
            replacements[old.segment to old.index] = old.word.copy(startMs = candidate.startMs, endMs = candidate.endMs)
        }
        // Do not introduce ordering inversions against unchanged surrounding words.
        var changed: Boolean
        do {
            changed = false
            for ((left, right) in all.zipWithNext()) {
                val l = replacements[left.segment to left.index] ?: left.word
                val r = replacements[right.segment to right.index] ?: right.word
                if (l.startMs > r.startMs || l.endMs > r.endMs) {
                    changed = (replacements.remove(left.segment to left.index) != null) || changed
                    changed = (replacements.remove(right.segment to right.index) != null) || changed
                }
            }
        } while (changed)
        return segments.mapIndexed { s, part ->
            val words = part.words?.mapIndexed { w, old ->
                val result = replacements[s to w] ?: old
                if (old.startMs < clip.endMs && old.endMs > clip.startMs)
                    result.copy(boundaryPassVersion = VERSION) else result
            }
            if (words.isNullOrEmpty()) part else part.copy(words = words,
                startMs = minOf(part.startMs, words.minOf { it.startMs }),
                endMs = maxOf(part.endMs, words.maxOf { it.endMs }))
        }
    }
}
