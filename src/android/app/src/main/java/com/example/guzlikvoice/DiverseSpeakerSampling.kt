package com.example.guzlikvoice

import kotlin.math.sqrt

/** Uniform time coverage plus observed pairs from acoustically unusual regions.
 * Projections only choose observations; clustering always receives original vectors. */
object DiverseSpeakerSampling {
    fun indices(embeddings: List<FloatArray>, limit: Int, checkCancelled: () -> Unit = {}): List<Int> {
        if (embeddings.size <= limit) return embeddings.indices.toList()
        if (limit < 8) return (0 until limit).map { (it.toLong() * embeddings.size / limit).toInt() }
        val n = embeddings.size; val dim = embeddings.first().size
        val sketchDim = 16
        val signs = Array(sketchDim) { k -> FloatArray(dim) { d ->
            var x = (k + 1) * 0x45d9f3b xor (d + 1) * 0x119de1f3
            x = (x xor (x ushr 16)) * 0x45d9f3b
            if (x and 1 == 0) 1f else -1f
        } }
        val sketch = Array(n) { i ->
            checkCancelled()
            val e = embeddings[i]
            FloatArray(sketchDim) { k ->
                val projection = signs[k]
                var sum = 0.0
                for (d in e.indices) sum += (e[d] * projection[d]).toDouble()
                sum.toFloat()
            }.also { v -> val norm = sqrt(v.sumOf { it.toDouble()*it }).coerceAtLeast(1e-9); for (k in v.indices) v[k] = (v[k]/norm).toFloat() }
        }
        fun distance(a: Int, b: Int): Float {
            val x=sketch[a];val y=sketch[b];var dot=0.0
            for (k in x.indices) dot += (x[k]*y[k]).toDouble()
            return 1f-dot.toFloat()
        }
        val reserve = minOf(128, limit / 4)
        val uniform = limit - reserve
        val selected = (0 until uniform).map { (it.toLong()*n/uniform).toInt() }.toMutableSet()
        val nearest = FloatArray(n) { Float.POSITIVE_INFINITY }
        fun update(anchor: Int) { for (i in 0 until n) nearest[i] = minOf(nearest[i], distance(i,anchor)) }
        // A bounded coarse cover is enough to rank novelty; retain ALL uniform points in the final fit.
        for (i in 0 until minOf(64,uniform)) update((i.toLong()*n/minOf(64,uniform)).toInt())
        while (selected.size < limit) {
            checkCancelled()
            val candidate = (0 until n).filter { it !in selected }.maxByOrNull { nearest[it] } ?: break
            selected.add(candidate); update(candidate)
            if (selected.size == limit) break
            // A second genuine observation preserves evidence for a rare, brief speaker.
            repeat(minOf(2, limit - selected.size)) {
                val x=embeddings[candidate]
                var partner=-1;var best=Double.NEGATIVE_INFINITY
                for (j in 0 until n) if (j !in selected) {
                    val y=embeddings[j];var dot=0.0
                    for(d in x.indices) dot += x[d].toDouble()*y[d]
                    if(dot>best) {best=dot;partner=j}
                }
                selected.add(partner); update(partner)
            }
        }
        return selected.sorted()
    }
}
