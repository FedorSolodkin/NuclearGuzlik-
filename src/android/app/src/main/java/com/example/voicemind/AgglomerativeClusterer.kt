package com.example.voicemind

import kotlin.math.sqrt

/**
 * Агломеративная иерархическая кластеризация со связыванием по центроидам (centroid linkage)
 * на косинусном расстоянии. Останавливается, когда минимальное расстояние
 * между кластерами превышает threshold — так автоматически подбирается K.
 *
 * Используется для «Level 4» диаризации (пайплайн pyannote-style):
 *   segmentation → per-region embeddings → AgglomerativeClusterer.
 *
 * Порог относится только к текущим эмбеддингам; требует калибровки на размеченной речи.
 * Меньше — больше кластеров, аккуратнее для сильно похожих голосов;
 * больше — меньше кластеров, толерантнее к вариациям одного спикера.
 */
object AgglomerativeClusterer {

    /**
     * @param embeddings список L2-нормированных векторов
     * @param threshold порог косинусного расстояния для остановки слияний (0..2)
     * @param minClusters нижняя граница K (не сливать дальше)
     * @param maxClusters верхняя граница K для sanity
     * @return массив labels[i] — id кластера для embeddings[i], нумерация 0..K-1
     */
    fun cluster(
        embeddings: List<FloatArray>,
        threshold: Float = 0.72f,
        minClusters: Int = 1,
        maxClusters: Int = 8,
        checkCancelled: () -> Unit = {},
    ): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        if (n == 1) return IntArray(1)

        // Матрица расстояний между кластерами + для каждой строки ближайший сосед.
        // Раньше на КАЖДОМ слиянии заново считались все пары (n² скалярных
        // произведений по 256 измерениям) — на часовой записи (тысячи регионов)
        // это минуты. Теперь после слияния пересчитывается одна строка, а поиск
        // минимума идёт по кэшу ближайших соседей.
        val parent = IntArray(n) { it }   // кто в кого слит — для восстановления меток
        fun root(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        val alive = BooleanArray(n) { true }
        val size = IntArray(n) { 1 }
        val sums = Array(n) { embeddings[it].copyOf() }
        val centroids = Array(n) { embeddings[it].copyOf() }
        val dist = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = cosineDistance(centroids[i], centroids[j])
            dist[i][j] = d; dist[j][i] = d
        }
        val nn = IntArray(n)
        val nnDist = FloatArray(n)
        // Непригодное расстояние (NaN из битого эмбеддинга) = «бесконечно далеко»,
        // но пара всё равно остаётся кандидатом: если пользователь указал число
        // людей, слияние до этого числа должно произойти при любых входных данных.
        fun refreshNn(i: Int) {
            var best = -1; var bd = Float.POSITIVE_INFINITY
            for (j in 0 until n) if (j != i && alive[j]) {
                val d = dist[i][j].let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }
                if (best < 0 || d < bd) { bd = d; best = j }
            }
            nn[i] = best; nnDist[i] = bd
        }
        for (i in 0 until n) refreshNn(i)

        var clusters = n
        while (clusters > minClusters) {
            checkCancelled()
            var a = -1; var bd = Float.POSITIVE_INFINITY
            for (i in 0 until n) if (alive[i] && nn[i] >= 0 && (a < 0 || nnDist[i] < bd)) { bd = nnDist[i]; a = i }
            if (a < 0) break
            val b = nn[a]
            // Уложились в maxClusters и ближайшая пара дальше порога — стоп.
            if (clusters <= maxClusters && bd > threshold) break

            // Сливаем b в a: центроид — средневзвешенное (centroid linkage).
            // Preserve the true vector sum: repeatedly normalizing weighted means
            // makes the centroid depend on the merge tree.
            for (k in sums[a].indices) sums[a][k] += sums[b][k]
            val merged = sums[a].copyOf()
            normalizeL2(merged)
            centroids[a] = merged
            size[a] += size[b]
            alive[b] = false
            clusters--
            parent[b] = a

            for (j in 0 until n) if (alive[j] && j != a) {
                val d = cosineDistance(centroids[a], centroids[j]).let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }
                dist[a][j] = d; dist[j][a] = d
            }
            refreshNn(a)
            // Строки, чьим ближайшим был a или b, или для которых a стал ближе.
            for (j in 0 until n) if (alive[j] && j != a) {
                if (nn[j] == a || nn[j] == b) refreshNn(j)
                else if (dist[j][a] < nnDist[j]) { nn[j] = a; nnDist[j] = dist[j][a] }
            }
        }

        // Метки: корень каждого исходного вектора → номер живого кластера 0..K-1.
        val labelOf = IntArray(n) { -1 }
        var next = 0
        for (i in 0 until n) if (alive[i]) labelOf[i] = next++
        return IntArray(n) { i -> labelOf[root(i)] }
    }

    /** Косинусное расстояние = 1 − cos(sim). Ожидаем L2-нормированные векторы. */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        // Клипуем: небольшая численная ошибка после усреднения может дать |dot| > 1.
        val clipped = dot.coerceIn(-1f, 1f)
        return 1f - clipped
    }

    private fun normalizeL2(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
    }
}
