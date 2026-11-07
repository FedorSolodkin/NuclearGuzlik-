package com.guzlik.voice

/**
 * Где в показанном тексте стоят слова, в которых распознавание не уверено.
 *
 * Прежний поиск искал только сомнительные слова и брал первое вхождение строки после предыдущей отметки.
 * Короткое слово («то», «не», «да») попадало внутрь обычных слов («что», «нельзя», «когда»), и подсвечивалось
 * не то место; нажатие на него переносило к времени другого слова. Теперь все слова записи по порядку
 * выравниваются по тексту целиком, как отдельные слова, а отмечаются только сомнительные.
 */
object DoubtfulWords {

    data class Mark(val start: Int, val end: Int, val startMs: Long)

    /** Сколько символов вперёд искать следующее слово: слова, которых нет в тексте (правка, фильтр), не должны утаскивать курсор. */
    private const val LOOKAHEAD = 80
    /** После стольких подряд ненайденных слов ищем дальше без ограничения: текст мог разойтись с распознаванием на длинном куске. */
    private const val MISSES_BEFORE_RESYNC = 4

    fun align(text: String, words: List<WhisperHelper.Word>, threshold: Float): List<Mark> {
        val lower = text.lowercase()
        val marks = mutableListOf<Mark>()
        var cursor = 0
        var misses = 0
        for (w in words) {
            val core = w.text.lowercase().trim { !it.isLetterOrDigit() }
            if (core.isEmpty()) continue
            val limit = if (misses >= MISSES_BEFORE_RESYNC) Int.MAX_VALUE else cursor + LOOKAHEAD
            val at = indexOfWord(lower, core, cursor, limit)
            if (at < 0) { misses++; continue }
            misses = 0
            val end = at + core.length
            if (w.p < threshold && core.length >= 2) marks += Mark(at, end, w.startMs)
            cursor = end
        }
        return marks
    }

    /** Первое вхождение [core] как отдельного слова (по краям не буква и не цифра) не дальше [limit]. */
    private fun indexOfWord(lower: String, core: String, from: Int, limit: Int): Int {
        var i = lower.indexOf(core, from)
        while (i in 0..limit) {
            val end = i + core.length
            val startsWord = i == 0 || !lower[i - 1].isLetterOrDigit()
            val endsWord = end >= lower.length || !lower[end].isLetterOrDigit()
            if (startsWord && endsWord) return i
            i = lower.indexOf(core, i + 1)
        }
        return -1
    }
}
