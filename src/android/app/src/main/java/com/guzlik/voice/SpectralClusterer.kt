package com.guzlik.voice

import kotlin.math.*

/**
 * Spectral clustering for speaker diarization.
 * Uses self-tuning affinity (Zelnik-Manor 2004) + normalized Laplacian +
 * Jacobi eigendecomposition + K-means++ with multiple restarts.
 */
object SpectralClusterer {

    private const val MAX_SPEAKERS  = 8
    private const val JACOBI_SWEEPS = 40
    private const val JACOBI_TOL    = 1e-8f
    private const val LOCAL_SIGMA_K = 7     // K-NN for self-tuning sigma
    private const val KMEANS_RESTARTS = 5

    /** @param fixedK число говорящих, если пользователь его указал (null — определить автоматически). */
    fun cluster(embeddings: List<FloatArray>, fixedK: Int? = null, checkCancelled: () -> Unit = {}): IntArray {
        val n = embeddings.size
        checkCancelled()
        if (n <= 1) return IntArray(n)
        if (fixedK == 1) return IntArray(n)
        if (n == 2 && fixedK == null) {
            val sim = cosineSim(embeddings[0], embeddings[1])
            return if (sim > 0.85f) intArrayOf(0, 0) else intArrayOf(0, 1)
        }

        // 1. Pairwise cosine distances
        val dist = Array(n) { i ->
            FloatArray(n) { j ->
                (1f - cosineSim(embeddings[i], embeddings[j])).coerceAtLeast(0f)
            }
        }

        // 2. Self-tuning local sigma: distance to k-th nearest neighbor
        val k = minOf(LOCAL_SIGMA_K, n - 1)
        val sigmas = FloatArray(n) { i ->
            dist[i].copyOf().also { it.sort() }[k].coerceAtLeast(1e-5f)
        }

        // 3. Self-tuning affinity matrix: A(i,j) = exp(-d²/(σᵢ×σⱼ))
        val W = Array(n) { i ->
            FloatArray(n) { j ->
                val d = dist[i][j]
                exp(-(d * d) / (sigmas[i] * sigmas[j])).toFloat()
            }
        }

        // 4. Degree matrix → normalized Laplacian L = I - D^{-1/2} W D^{-1/2}
        val deg = FloatArray(n) { i -> W[i].sum().coerceAtLeast(1e-8f) }
        val L = Array(n) { i ->
            FloatArray(n) { j ->
                val wij = W[i][j] / sqrt(deg[i] * deg[j]).toFloat()
                if (i == j) 1f - wij else -wij
            }
        }

        // 5. Jacobi eigendecomposition
        val (eigenvalues, eigenvectors) = jacobiEigen(L, n, checkCancelled)
        val order = eigenvalues.indices.sortedBy { eigenvalues[it] }.toIntArray()
        val sortedVals = FloatArray(n) { eigenvalues[order[it]] }

        // 6. Eigen-gap K selection
        val maxK = minOf(MAX_SPEAKERS, n - 1)
        val gaps = FloatArray(maxK) { ki -> sortedVals[ki + 1] - sortedVals[ki] }

        // Find top-2 gaps and their positions
        var maxGap = 0f; var maxGapIdx = 0
        var secondGap = 0f; var secondGapIdx = 0
        for (ki in 0 until maxK) {
            if (gaps[ki] > maxGap) { secondGap = maxGap; secondGapIdx = maxGapIdx; maxGap = gaps[ki]; maxGapIdx = ki }
            else if (gaps[ki] > secondGap) { secondGap = gaps[ki]; secondGapIdx = ki }
        }
        // bestK = index+1 = number of clusters (gap at index ki is between sortedVals[ki] and sortedVals[ki+1])
        var bestK = maxGapIdx + 1
        // If top-2 gaps are ambiguous (within 20% of each other) and maxGap is at K=1,
        // prefer the higher K (likely a 2-speaker scenario vs monologue)
        if (bestK == 1 && maxGap > 0.05f && secondGap / maxGap > 0.70f) {
            bestK = secondGapIdx + 1
        }
        android.util.Log.i("SpectralCluster",
            "K=$bestK (gap1=${"%.4f".format(maxGap)}@${maxGapIdx+1} gap2=${"%.4f".format(secondGap)}@${secondGapIdx+1}) vals=${sortedVals.take(maxK + 1).map { "%.4f".format(it) }}")

        if (fixedK != null) bestK = fixedK.coerceIn(1, n)

        // 7. Embedding matrix U: first bestK eigenvectors, row-normalized
        val U = Array(n) { j -> FloatArray(bestK) { ki -> eigenvectors[j][order[ki]] } }
        for (row in U) {
            val norm = sqrt(row.sumOf { it * it.toDouble() }).toFloat().coerceAtLeast(1e-8f)
            for (i in row.indices) row[i] /= norm
        }

        // 8. K-means++ with multiple restarts — pick best by inertia
        return kMeansBest(U, bestK)
    }

    // ── Jacobi eigendecomposition ─────────────────────────────────────────────

    /** Cyclic sweeps rotate every off-diagonal pair, rather than only 150 pairs total. */
    internal fun jacobiEigen(a: Array<FloatArray>, n: Int, checkCancelled: () -> Unit = {}): Pair<FloatArray, Array<FloatArray>> {
        val mat = Array(n) { i -> DoubleArray(n) { j -> a[i][j].toDouble() } }
        val vecs = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (sweep in 0 until JACOBI_SWEEPS) {
            checkCancelled()
            var maxVal = 0.0
            for (p in 0 until n) for (q in p + 1 until n) {
                val apq = mat[p][q]
                maxVal = maxOf(maxVal, abs(apq))
                if (abs(apq) < JACOBI_TOL) continue
                val tau = (mat[q][q] - mat[p][p]) / (2.0 * apq)
                val t = if (tau >= 0) 1.0 / (tau + hypot(1.0, tau)) else -1.0 / (-tau + hypot(1.0, tau))
                val c = 1.0 / sqrt(1.0 + t * t)
                val sn = t * c
                mat[p][p] -= t * apq
                mat[q][q] += t * apq
                mat[p][q] = 0.0; mat[q][p] = 0.0
                for (r in 0 until n) {
                    if (r != p && r != q) {
                        val rp = mat[r][p]; val rq = mat[r][q]
                        mat[r][p] = c * rp - sn * rq; mat[p][r] = mat[r][p]
                        mat[r][q] = sn * rp + c * rq; mat[q][r] = mat[r][q]
                    }
                    val vp = vecs[r][p]; val vq = vecs[r][q]
                    vecs[r][p] = c * vp - sn * vq
                    vecs[r][q] = sn * vp + c * vq
                }
            }
            if (maxVal < JACOBI_TOL) break
        }
        return FloatArray(n) { mat[it][it].toFloat() } to Array(n) { i -> FloatArray(n) { j -> vecs[i][j].toFloat() } }
    }

    // ── K-means++ with multiple restarts ──────────────────────────────────────

    private fun kMeansBest(points: Array<FloatArray>, k: Int): IntArray {
        var bestAssignment = IntArray(points.size)
        var bestInertia = Float.MAX_VALUE
        for (seed in 0 until KMEANS_RESTARTS) {
            val assignment = kMeansOnce(points, k, seed.toLong() * 137 + 42)
            val inertia = computeInertia(points, assignment, k)
            if (inertia < bestInertia) { bestInertia = inertia; bestAssignment = assignment }
        }
        return bestAssignment
    }

    private fun kMeansOnce(points: Array<FloatArray>, k: Int, seed: Long, maxIter: Int = 150): IntArray {
        val n = points.size
        if (k >= n) return IntArray(n) { it }
        val dim = points[0].size
        val rng = java.util.Random(seed)

        // K-means++ init
        val centroids = Array(k) { FloatArray(dim) }
        centroids[0] = points[rng.nextInt(n)].copyOf()
        for (ci in 1 until k) {
            val dists = FloatArray(n) { i ->
                (0 until ci).minOf { c ->
                    var d = 0f
                    for (j in 0 until dim) { val diff = points[i][j] - centroids[c][j]; d += diff * diff }
                    d
                }
            }
            val sum = dists.sum().coerceAtLeast(1e-8f)
            var r = rng.nextFloat() * sum
            var chosen = n - 1
            for (i in dists.indices) { r -= dists[i]; if (r <= 0f) { chosen = i; break } }
            centroids[ci] = points[chosen].copyOf()
        }

        val assignment = IntArray(n) { -1 }
        val counts     = IntArray(k)

        repeat(maxIter) {
            var changed = false
            for (i in 0 until n) {
                var bestDist = Float.MAX_VALUE; var bestC = 0
                for (c in 0 until k) {
                    var d = 0f
                    for (j in 0 until dim) { val diff = points[i][j] - centroids[c][j]; d += diff * diff }
                    if (d < bestDist) { bestDist = d; bestC = c }
                }
                if (bestC != assignment[i]) { assignment[i] = bestC; changed = true }
            }
            if (!changed) return assignment

            for (c in 0 until k) { centroids[c].fill(0f); counts[c] = 0 }
            for (i in 0 until n) {
                val c = assignment[i]; counts[c]++
                for (j in 0 until dim) centroids[c][j] += points[i][j]
            }
            for (c in 0 until k) if (counts[c] > 0) for (j in 0 until dim) centroids[c][j] = centroids[c][j] / counts[c]
        }
        return assignment
    }

    private fun computeInertia(points: Array<FloatArray>, assignment: IntArray, k: Int): Float {
        val dim = points[0].size
        val centroids = Array(k) { FloatArray(dim) }
        val counts = IntArray(k)
        for (i in points.indices) {
            val c = assignment[i]; counts[c]++
            for (j in 0 until dim) centroids[c][j] += points[i][j]
        }
        for (c in 0 until k) if (counts[c] > 0) for (j in 0 until dim) centroids[c][j] = centroids[c][j] / counts[c]
        return points.indices.sumOf { i ->
            val c = assignment[i]
            var d = 0f
            for (j in 0 until dim) { val diff = points[i][j] - centroids[c][j]; d += diff * diff }
            d.toDouble()
        }.toFloat()
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; for (i in a.indices) dot += a[i] * b[i]; return dot
    }
}
