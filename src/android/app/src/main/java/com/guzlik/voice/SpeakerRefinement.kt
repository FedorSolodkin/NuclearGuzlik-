package com.guzlik.voice

import kotlin.math.sqrt

/**
 * Второй проход кластеризации после порогового AHC.
 *
 * Пороговый AHC с единственным расстоянием [RobustSpeakerClustering.THRESHOLD] сливает
 * похожие голоса: заниженное число людей почти всегда объясняется этим. Проход повторяет
 * «диаризацию по фразам» и Recluster-этап проверенных решений:
 *   1. split — внутри каждого крупного кластера ищутся устойчивые подгруппы жёстким
 *      порогом; далёкие центры с независимой временно́й поддержкой — два человека.
 *   2. purify — центроиды пересчитываются по наблюдениям, близким к центру, затем все
 *      наблюдения переназначаются ближайшему центру с запасом.
 *   3. fill — слабые регионы приписываются ближайшему центру, а не отбрасываются.
 *
 * Пороги косинуса привязаны к L2-нормированным WeSpeaker-векторам, как и THRESHOLD.
 */
object SpeakerRefinement {

    // Внутренний поиск слитых голосов: жёстче основного порога 0.35.
    private const val SPLIT_DISTANCE = 0.25f
    // Косинус центров двух подгрупп выше — это вариации одного голоса, не два человека.
    private const val SPLIT_SEPARATION = 0.78f
    // Бимодальность: наблюдение обязано быть ближе к центру своей подгруппы с запасом;
    // плавная цепочка вариаций одного голоса этот запас не выдерживает.
    private const val SPLIT_MARGIN = 0.08f
    // Кластер меньшего размера делить бессмысленно.
    private const val MIN_SPLIT_OBS = 4
    // Подгруппа обязана быть наблюдаемой, а не парой перекрывающихся окон.
    private const val MIN_SUB_OBS = 2
    private const val MIN_SUB_SPREAD_MS = 5_000L
    private const val SUB_COHESION = 0.80f
    // Внутри одного кластера AHC считается не более чем на этой выборке.
    private const val SPLIT_FIT_LIMIT = 300
    // Purify: наблюдение чистое, если лежит не дальше этого косинуса от центра.
    private const val PURIFY_COS = 0.70f
    // Переназначение к чужому центру требует запаса (margin Recluster).
    private const val REASSIGN_MARGIN = 0.05f
    // Слабый регион приписывается центру только при достаточной близости.
    private const val FILL_COS = 0.50f

    /**
     * Split + purify поверх готовых меток первого прохода.
     * @param embeddings L2-нормированные векторы всех наблюдений
     * @param labels метки первого прохода (0..K-1)
     * @param anchorSpans интервалы речи каждого наблюдения (независимая поддержка во времени)
     * @param fixedK число людей, если задано пользователем: split не превышает его
     */
    fun refine(
        embeddings: List<FloatArray>,
        labels: IntArray,
        anchorSpans: List<List<RobustSpeakerClustering.SpeechSpan>>? = null,
        fixedK: Int? = null,
        checkCancelled: () -> Unit = {},
    ): IntArray {
        require(labels.size == embeddings.size)
        if (embeddings.size <= 1 || fixedK == 1) return labels
        val spans = anchorSpans ?: List(embeddings.size) { emptyList() }
        val split = splitMergedVoices(embeddings, labels, spans, fixedK, checkCancelled)
        return purifyCentroids(embeddings, split, checkCancelled)
    }

    /** Владелец слабого региона: ближайший центр, если тот достаточно близок. */
    fun attachUnreliable(embedding: FloatArray, centroids: List<FloatArray>): Int? {
        var best = -1; var bestCos = FILL_COS
        for (k in centroids.indices) {
            val c = cos(embedding, centroids[k])
            if (c >= bestCos) { bestCos = c; best = k }
        }
        return best.takeIf { it >= 0 }
    }

    // ── Split ─────────────────────────────────────────────────────────────────

    private fun splitMergedVoices(
        embeddings: List<FloatArray>,
        labels: IntArray,
        spans: List<List<RobustSpeakerClustering.SpeechSpan>>,
        fixedK: Int?,
        checkCancelled: () -> Unit,
    ): IntArray {
        val byCluster = labels.indices.groupBy { labels[it] }
        val out = labels.copyOf()
        var nextId = (labels.maxOrNull() ?: 0) + 1
        val candidates = byCluster.entries.filter { it.value.size >= MIN_SPLIT_OBS }
            .sortedByDescending { it.value.size }
        for ((cluster, indices) in candidates) {
            checkCancelled()
            if (indices.size < MIN_SPLIT_OBS) continue
            val fit = if (indices.size > SPLIT_FIT_LIMIT)
                DiverseSpeakerSampling.indices(indices.map { embeddings[it] }, SPLIT_FIT_LIMIT, checkCancelled)
                    .map { indices[it] }
            else indices
            val sub = AgglomerativeClusterer.cluster(fit.map { embeddings[it] },
                threshold = SPLIT_DISTANCE, maxClusters = 4, checkCancelled = checkCancelled)
            val groups = fit.withIndex().groupBy({ sub[it.index] }, { it.value })
            if (groups.size < 2) continue
            // Подгруппа жива, когда у неё есть наблюдения, речь во времени и сходимость к центру.
            val valid = groups.values.map { subgroup -> subgroup to center(embeddings, subgroup) }
                .filter { (subgroup, c) ->
                    subgroup.size >= MIN_SUB_OBS &&
                        speechSpread(spans, subgroup) >= MIN_SUB_SPREAD_MS &&
                        subgroup.sumOf { i -> cos(embeddings[i], c).toDouble() } / subgroup.size >= SUB_COHESION
                }
            if (valid.size < 2) continue
            // Все попарные центры достаточно далеки — иначе это вариации одного голоса.
            val centers = valid.map { it.second }
            val separated = centers.indices.all { a -> (a + 1 until centers.size).all { b ->
                cos(centers[a], centers[b]) <= SPLIT_SEPARATION
            } }
            if (!separated) continue
            // Бимодальность: каждое наблюдение ближе к центру своей подгруппы с запасом;
            // плавная цепочка вариаций одного голоса этот запас не выдерживает.
            val bimodal = valid.withIndex().all { (idx, pair) ->
                pair.first.all { i ->
                    val own = cos(embeddings[i], pair.second)
                    centers.withIndex().all { (k, other) -> k == idx || own >= cos(embeddings[i], other) + SPLIT_MARGIN }
                }
            }
            if (!bimodal) continue
            if (fixedK != null && out.toSet().size + valid.size - 1 > fixedK) continue
            // Разделение подтверждено; переносим его на все наблюдения кластера по близости к центрам.
            for (i in byCluster.getValue(cluster)) {
                val best = centers.indices.maxByOrNull { k -> cos(embeddings[i], centers[k]) } ?: 0
                if (best > 0) out[i] = nextId + best - 1
            }
            nextId += valid.size - 1
        }
        return compact(out)
    }

    /** Суммарная неперекрывающаяся речь наблюдений: перекрывающиеся окна не умножают поддержку. */
    private fun speechSpread(spans: List<List<RobustSpeakerClustering.SpeechSpan>>, indices: List<Int>): Long {
        var end = -1L; var coverage = 0L
        for (span in indices.flatMap { spans[it] }.sortedBy { it.startMs }) {
            coverage += (span.endMs - maxOf(span.startMs, end)).coerceAtLeast(0)
            end = maxOf(end, span.endMs)
        }
        return coverage
    }

    // ── Purify ────────────────────────────────────────────────────────────────

    private fun purifyCentroids(
        embeddings: List<FloatArray>,
        labels: IntArray,
        checkCancelled: () -> Unit,
    ): IntArray {
        val byCluster = labels.indices.groupBy { labels[it] }
        if (byCluster.size < 2) return labels
        // Чистые центроиды: только наблюдения, близкие к первичному центру кластера.
        val centers = byCluster.mapValues { (_, indices) ->
            val raw = center(embeddings, indices)
            val clean = indices.filter { cos(embeddings[it], raw) >= PURIFY_COS }
            if (clean.size >= 2) center(embeddings, clean) else raw
        }
        // Переназначение с запасом: наблюдение уходит к чужому центру только при явном выигрыше.
        return compact(IntArray(labels.size) { i ->
            checkCancelled()
            val current = labels[i]
            var bestKey = current
            var bestCos = centers[current]?.let { cos(embeddings[i], it) } ?: -1f
            for (k in centers.keys) {
                if (k == current) continue
                val c = cos(embeddings[i], centers.getValue(k))
                if (c > bestCos + REASSIGN_MARGIN) { bestCos = c; bestKey = k }
            }
            bestKey
        })
    }

    // ── Общее ─────────────────────────────────────────────────────────────────

    /** Убирает опустевшие метки, сохраняя порядок первых появлений. */
    private fun compact(labels: IntArray): IntArray {
        val remap = mutableMapOf<Int, Int>()
        return IntArray(labels.size) { i -> remap.getOrPut(labels[i]) { remap.size } }
    }

    private fun center(embeddings: List<FloatArray>, indices: List<Int>): FloatArray {
        val dim = embeddings.first().size
        val sum = FloatArray(dim)
        for (i in indices) for (d in 0 until dim) sum[d] += embeddings[i][d]
        val norm = sqrt(sum.sumOf { it.toDouble() * it }).coerceAtLeast(1e-9)
        return FloatArray(dim) { (sum[it] / norm).toFloat() }
    }

    private fun cos(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }
}
