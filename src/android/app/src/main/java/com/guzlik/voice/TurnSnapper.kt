package com.guzlik.voice

/**
 * Доводка пословного разделения по голосам. Говорящий назначается каждому слову
 * по окнам эмбеддингов (~1.5 с), поэтому граница смены собеседника плавает на
 * 1–3 слова: «Запусти фильм в | стиле научной лекции» доставалось двум людям
 * (найдено прогоном на телефоне, tools/device_test).
 *
 *  1. Каждую смену говорящего сдвигаем к самой «естественной» точке рядом
 *     (±[WINDOW] слов): длинная пауза между словами, конец предложения.
 *  2. Короткий «перескок» (≤ [SHORT_WORDS] слов и < [SHORT_MS] мс) посреди сплошной
 *     речи одного человека — шум кластеризации, отдаём его соседям. Если же короткий
 *     кусок отделён паузами ≥ [TURN_GAP_MS] — это настоящая реплика («Да.», «угу»),
 *     её не трогаем.
 */
object TurnSnapper {
    const val WINDOW = 4
    const val SHORT_WORDS = 2
    const val SHORT_MS = 1200L
    const val TURN_GAP_MS = 300L
    private const val SENTENCE_BONUS_MS = 600L
    private const val COMMA_BONUS_MS = 150L

    /** [words] и [speakers] параллельны; возвращает новый массив говорящих. */
    fun snap(words: List<WhisperHelper.Segment>, speakers: IntArray): IntArray {
        val n = words.size
        if (n < 2 || speakers.size != n) return speakers.copyOf()
        val a = speakers.copyOf()
        removeShortRuns(words, a)
        moveBoundaries(words, a)
        removeShortRuns(words, a)
        return a
    }

    /** Насколько хорошо поставить границу ПЕРЕД словом i (больше — лучше). */
    private fun boundaryScore(words: List<WhisperHelper.Segment>, i: Int): Long {
        val prev = words[i - 1]
        var s = (words[i].startMs - prev.endMs).coerceAtLeast(0)
        val t = prev.text.trimEnd()
        if (t.endsWith('.') || t.endsWith('?') || t.endsWith('!') || t.endsWith('…')) s += SENTENCE_BONUS_MS
        else if (t.endsWith(',') || t.endsWith(';') || t.endsWith(':') || t.endsWith('—')) s += COMMA_BONUS_MS
        return s
    }

    private fun moveBoundaries(words: List<WhisperHelper.Segment>, a: IntArray) {
        var i = 1
        while (i < a.size) {
            if (a[i] == a[i - 1]) { i++; continue }
            // Двигаемся только внутри двух соседних реплик, чужие границы не пересекаем.
            var lo = i; while (lo - 1 > 0 && a[lo - 2] == a[i - 1] && i - lo < WINDOW) lo--
            var hi = i; while (hi + 1 < a.size && a[hi + 1] == a[i] && hi - i < WINDOW) hi++
            var best = i
            var bestScore = boundaryScore(words, i)
            for (j in lo..hi) {
                if (j < 1 || j >= a.size) continue
                val sc = boundaryScore(words, j)
                if (sc > bestScore) { best = j; bestScore = sc }
            }
            if (best != i) {
                val left = a[i - 1]; val right = a[i]
                for (k in minOf(i, best) until maxOf(i, best)) a[k] = if (k < best) left else right
            }
            i = maxOf(i, best) + 1
        }
    }

    private fun removeShortRuns(words: List<WhisperHelper.Segment>, a: IntArray) {
        var start = 0
        while (start < a.size) {
            var end = start
            while (end + 1 < a.size && a[end + 1] == a[start]) end++
            val len = end - start + 1
            val dur = words[end].endMs - words[start].startMs
            val prevSpk = if (start > 0) a[start - 1] else -1
            val nextSpk = if (end + 1 < a.size) a[end + 1] else -1
            val short = len <= SHORT_WORDS && dur < SHORT_MS
            val gapBefore = if (start > 0) words[start].startMs - words[start - 1].endMs else 0L
            val gapAfter = if (end + 1 < a.size) words[end + 1].startMs - words[end].endMs else 0L
            val isolated = gapBefore >= TURN_GAP_MS && gapAfter >= TURN_GAP_MS
            if (short && !isolated && prevSpk >= 0 && prevSpk == nextSpk) {
                for (k in start..end) a[k] = prevSpk
            }
            start = end + 1
        }
    }
}
