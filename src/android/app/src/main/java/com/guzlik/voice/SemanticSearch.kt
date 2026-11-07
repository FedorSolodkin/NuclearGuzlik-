package com.guzlik.voice

/**
 * Семантический поиск (Pro-фича). Не полный embedding-подход — лёгкий стеммер +
 * ранжирование по совпадениям стемов в text/structured/templateResult/tags/клиент.
 *
 * По сравнению с substring:
 * - падеж-инвариантно: "клиенту", "клиентом" сматчатся с запросом "клиент"
 * - многословный запрос ранжируется, а не жёстко фильтруется
 * - ищет по всем полям записи, не только по raw тексту
 * - авто-игнорирует стоп-слова и короткие частицы
 */
object SemanticSearch {

    private val PUNCT = Regex("[\\p{Punct}«»„“”…—–]+")
    private val WHITESPACE = Regex("\\s+")

    // Русские суффиксы от длинных к коротким — режем первый попавшийся
    private val RU_SUFFIXES = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ими", "ыми",
        "ов",  "ев",  "ой",  "ей",  "ий",  "ый",  "ая",  "яя",
        "ое",  "ее",  "ые",  "ие",  "ым",  "им",  "ом",  "ем",
        "ах",  "ях",  "ть",  "ся",
        "у",   "ю",   "а",   "я",   "ы",   "и",   "е"
    )

    // Стоп-слова: не участвуют ни в запросе, ни в документе
    private val STOP_WORDS = setOf(
        "и", "в", "на", "с", "по", "за", "к", "у", "о", "об", "от", "до",
        "не", "но", "а", "или", "что", "как", "это", "то", "же", "ли",
        "бы", "для", "из", "при", "без", "под", "над", "чтобы", "если",
        "так", "там", "тут", "здесь", "тоже", "также", "ещё", "уже",
        "быть", "есть", "мы", "вы", "он", "она", "они", "я", "ты", "мне",
        "тебе", "нам", "вам", "им", "их", "его", "её", "меня", "тебя"
    )

    data class Match(
        val entry: DailyRecord.Entry,
        val date: String,
        val score: Float
    )

    fun stem(word: String): String {
        if (word.length <= 4) return word
        for (suf in RU_SUFFIXES) {
            if (word.endsWith(suf) && word.length - suf.length >= 3) {
                return word.dropLast(suf.length)
            }
        }
        return word
    }

    fun tokenize(text: String): List<String> = text
        .lowercase()
        .replace('ё', 'е')
        .replace(PUNCT, " ")
        .split(WHITESPACE)
        .filter { it.length >= 2 && it !in STOP_WORDS }
        .map { stem(it) }
        .filter { it.isNotEmpty() }

    /**
     * Ищет по всем записям. Score в диапазоне [0..1] — доля запрос-стемов,
     * попавших в документ (exact match = 1.0, partial-prefix = 0.5).
     * Возвращает список отсортированный от лучшего к худшему.
     */
    fun search(query: String, records: List<DailyRecord>): List<Match> {
        val qStems = tokenize(query).distinct()
        if (qStems.isEmpty()) return emptyList()

        val matches = mutableListOf<Match>()
        for (rec in records) {
            for (entry in rec.entries) {
                val doc = buildString {
                    append(entry.text).append(' ')
                    append(entry.structured).append(' ')
                    entry.templateResult?.let { append(it).append(' ') }
                    append(entry.tags.joinToString(" "))
                }
                val docStems = tokenize(doc).toHashSet()
                if (docStems.isEmpty()) continue

                var exact = 0
                var partial = 0
                for (qs in qStems) {
                    when {
                        qs in docStems -> exact++
                        docStems.any { it.startsWith(qs) || qs.startsWith(it) } -> partial++
                    }
                }
                if (exact == 0 && partial == 0) continue

                val score = (exact + partial * 0.5f) / qStems.size
                matches.add(Match(entry, rec.date, score))
            }
        }
        return matches.sortedByDescending { it.score }
    }
}
