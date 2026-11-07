package com.example.voicemind

/** Single-speaker evidence within ONE segmentation chunk. Local IDs must never cross chunks. */
object SegmentationSpeech {
    data class Run(val speaker: Int, val startSample: Int, val endSample: Int)

    /** Keep independently embedded turns separate even when segmentation reuses a local ID. */
    fun anchorActivity(activity: List<Run>, anchors: List<Run>): List<Pair<Run, Int>> {
        val out = mutableListOf<Pair<Run, Int>>()
        for (run in activity) {
            val candidates = anchors.indices.filter { anchors[it].speaker == run.speaker }
            if (candidates.isEmpty()) continue
            val cuts = sortedSetOf(run.startSample, run.endSample)
            val ordered = candidates.sortedBy { anchors[it].startSample }
            for (index in ordered) {
                val a = anchors[index]
                if (a.startSample in run.startSample..run.endSample) cuts.add(a.startSample)
                if (a.endSample in run.startSample..run.endSample) cuts.add(a.endSample)
            }
            for ((left, right) in ordered.zipWithNext()) {
                val middle = (anchors[left].endSample.toLong() + anchors[right].startSample) / 2
                if (middle > run.startSample && middle < run.endSample) cuts.add(middle.toInt())
            }
            for ((start, end) in cuts.zipWithNext()) {
                val mid = (start.toLong() + end) / 2
                val anchor = candidates.minByOrNull { i ->
                    val a = anchors[i]
                    maxOf(a.startSample - mid, mid - a.endSample, 0L)
                }!!
                out.add(Run(run.speaker, start, end) to anchor)
            }
        }
        return out
    }

    /** Full activity, including two simultaneous voices. Never use this mask for embeddings. */
    fun activityRuns(logits: Array<FloatArray>, chunkSamples: Int, validSamples: Int): List<Run> {
        require(chunkSamples > 0 && validSamples in 0..chunkSamples)
        if (logits.isEmpty()) return emptyList()
        val masks = intArrayOf(0, 1, 2, 4, 3, 5, 6)
        val active = IntArray(logits.size) { i ->
            val frame = logits[i]
            if (frame.size != 7 || frame.any { !it.isFinite() }) 0
            else masks[frame.indices.maxByOrNull { frame[it] }!!]
        }
        val result = mutableListOf<Run>()
        fun sample(frame: Int) = (frame.toLong() * chunkSamples / logits.size).toInt().coerceAtMost(validSamples)
        for (speaker in 1..3) {
            val bit = 1 shl (speaker - 1)
            var i = 0
            while (i < active.size) {
                if (active[i] and bit == 0) { i++; continue }
                var j = i + 1
                while (j < active.size && active[j] and bit != 0) j++
                val from = sample(i); val to = sample(j)
                if (to > from) result.add(Run(speaker, from, to))
                i = j
            }
        }
        return result
    }

    fun runs(logits: Array<FloatArray>, chunkSamples: Int, validSamples: Int): List<Run> {
        require(chunkSamples > 0 && validSamples in 0..chunkSamples)
        if (logits.isEmpty()) return emptyList()
        fun speaker(frame: FloatArray): Int {
            if (frame.size != 7 || frame.any { !it.isFinite() }) return 0
            val best = frame.indices.maxByOrNull { frame[it] } ?: return 0
            return if (best in 1..3) best else 0 // exclude silence and overlapping voices
        }
        fun sample(frame: Int) = (frame.toLong() * chunkSamples / logits.size).toInt().coerceAtMost(validSamples)
        val result = mutableListOf<Run>()
        var start = 0
        while (start < logits.size) {
            val id = speaker(logits[start])
            var end = start + 1
            while (end < logits.size && speaker(logits[end]) == id) end++
            val from = sample(start)
            val to = sample(end)
            if (id != 0 && to > from) result.add(Run(id, from, to))
            start = end
        }
        return result
    }

    /** Pool clean speech of the same local speaker, including separate short replies. */
    fun samples(pcm: ShortArray, runs: List<Run>, speaker: Int): ShortArray {
        val selected = runs.filter { it.speaker == speaker }
        val out = ShortArray(selected.sumOf { it.endSample - it.startSample })
        var offset = 0
        for (run in selected) {
            pcm.copyInto(out, offset, run.startSample, run.endSample)
            offset += run.endSample - run.startSample
        }
        return out
    }
}
