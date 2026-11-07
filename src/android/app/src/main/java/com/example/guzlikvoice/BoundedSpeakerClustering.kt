package com.example.guzlikvoice

/** Fit the quadratic clustering matrix on a time-stratified sample, then label every window. */
object BoundedSpeakerClustering {
    fun cluster(embeddings: List<FloatArray>, limit: Int, checkCancelled: () -> Unit = {},
                fit: (List<FloatArray>) -> IntArray): IntArray {
        require(limit > 0)
        if (embeddings.size <= limit) return fit(embeddings)
        val indices = DiverseSpeakerSampling.indices(embeddings, limit, checkCancelled)
        val sampled = indices.map { embeddings[it] }
        checkCancelled()
        val labels = fit(sampled)
        val k = (labels.maxOrNull() ?: 0) + 1
        val centroids = Array(k) { FloatArray(embeddings.first().size) }
        sampled.forEachIndexed { i, e -> e.indices.forEach { j -> centroids[labels[i]][j] += e[j] } }
        for (c in centroids) {
            val norm = kotlin.math.sqrt(c.sumOf { it.toDouble() * it }).coerceAtLeast(1e-9)
            c.indices.forEach { c[it] = (c[it] / norm).toFloat() }
        }
        return IntArray(embeddings.size) { i ->
            checkCancelled()
            centroids.indices.maxByOrNull { j -> embeddings[i].indices.sumOf { d ->
                embeddings[i][d].toDouble() * centroids[j][d]
            } } ?: 0
        }.also { out -> indices.forEachIndexed { i, n -> out[n] = labels[i] } }
    }
}
