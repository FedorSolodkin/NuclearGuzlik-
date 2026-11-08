package com.guzlik.voice

/**
 * Отсев типичных галлюцинаций Whisper — по сегментам, а не по целому куску.
 *
 * Раньше кусок 40–58 с выбрасывался целиком, если в нём где-то было 4 одинаковых
 * слова подряд («нет, нет, нет, нет») — вместе с минутой нормальной речи вокруг.
 * Теперь:
 *   • сегмент из одних «[музыка] [музыка] [музыка]» или зацикленный (мало
 *     уникальных слов на длинном отрезке) — выкидывается только этот сегмент;
 *   • длинный повтор одного слова (6+ раз) схлопывается до трёх, остальной текст
 *     сегмента остаётся;
 *   • «титры» («Субтитры сделал DimaTorzok» и т.п.) вырезаются, концовки видео
 *     («Продолжение следует») — только если это весь сегмент.
 */
object HallucinationFilter {
    private const val MAX_RUN = 5          // до 5 повторов подряд — живая речь («да да да да да»)
    private const val COLLAPSED_RUN = 3
    private const val LOOP_MIN_WORDS = 20
    private const val LOOP_UNIQUE_RATIO = 0.25f

    private val BRACKET_RUN = Regex("""(\[[^\]]{2,30}\]\s*){3,}""")
    private val ONLY_BRACKETS = Regex("""^(\s*[\[(][^\])]{1,40}[\])]\s*)+$""")
    private val WS = Regex("""\s+""")

    // «Титры» из обучающих данных Whisper (русские субтитры YouTube): модель выдаёт их
    // на тишине и ровном шуме. Человек такого не произносит — вырезаем где угодно.
    // Замер на стенде (tools/vad_bench): 30 из 87 шумовых файлов без речи дали такую строку.
    private val CREDITS = Regex(
        """(?iu)\s*(?:(?:субтитры|титры)\s+(?:сделал|создавал|подготовил[аи]?|делал)\w*|добавил\w*\s+субтитры|""" +
        """редактор\s+субтитров|корректор)\s+[\p{L}.\-]+(?:\s+(?!корректор|редактор)[\p{L}.\-]+)?[\s.!]*"""
    )
    // Фразы-концовки видео: выкидываем, только если это ВЕСЬ сегмент — внутри речи они бывают.
    private val OUTRO_ONLY = Regex("""(?iu)^[\s.!]*(?:продолжение следует|спасибо за просмотр|подписывайтесь на канал)[\s.!…]*$""")

    /** Убирает «титры» из текста сегмента. */
    fun stripCredits(text: String): String = CREDITS.replace(text, " ").replace(WS, " ").trim()

    /** Нормализованное слово для сравнения повторов: без регистра и пунктуации по краям. */
    private fun norm(w: String) = w.trim { !it.isLetterOrDigit() }.lowercase()

    /** Весь текст — цикл/мусор, который не стоит показывать вовсе. */
    fun isGarbage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // Сегмент без слов или из одной буквы («.», «А», «...») — выдумка на тишине, не реплика.
        if (t.count { it.isLetterOrDigit() } < 2) return true
        if (ONLY_BRACKETS.matches(t)) return true
        if (OUTRO_ONLY.matches(t) || stripCredits(t).isEmpty()) return true
        if (BRACKET_RUN.containsMatchIn(t) && BRACKET_RUN.replace(t, "").isBlank()) return true
        val words = t.split(WS).map(::norm).filter { it.isNotEmpty() }
        if (words.size >= LOOP_MIN_WORDS) {
            val unique = words.toSet().size
            if (unique.toFloat() / words.size < LOOP_UNIQUE_RATIO) return true
        }
        return false
    }

    /** Схлопывает повтор одного слова длиннее [MAX_RUN] до [COLLAPSED_RUN]. */
    fun collapseRuns(text: String): String {
        val tokens = text.trim().split(WS).filter { it.isNotEmpty() }
        if (tokens.size <= MAX_RUN) return text.trim()
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val key = norm(tokens[i])
            var j = i + 1
            while (j < tokens.size && key.isNotEmpty() && norm(tokens[j]) == key) j++
            val run = j - i
            val keep = if (run > MAX_RUN) COLLAPSED_RUN else run
            for (k in 0 until keep) out.add(tokens[i + k])
            i = j
        }
        return out.joinToString(" ")
    }

    /** Чистит сегменты: мусорные убирает, длинные повторы схлопывает. */
    fun clean(segments: List<WhisperHelper.Segment>): List<WhisperHelper.Segment> =
        segments.mapNotNull { seg ->
            if (isGarbage(seg.text)) return@mapNotNull null
            val collapsed = collapseRuns(stripCredits(seg.text))
            if (collapsed == seg.text.trim()) seg
            // Пословные данные (уверенность) больше не совпадают с текстом — сбрасываем
            // только у этого сегмента.
            else seg.copy(text = collapsed, words = null)
        }

    /** Годится ли хвост прошлого куска в подсказку следующему (не тащим зацикливание дальше). */
    fun isSafePrompt(text: String): Boolean =
        !isGarbage(text) && collapseRuns(text) == text.trim()
}
