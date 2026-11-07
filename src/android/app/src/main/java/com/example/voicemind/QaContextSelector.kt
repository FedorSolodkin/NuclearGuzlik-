package com.example.voicemind

/**
 * Выбор фрагментов длинной записи под вопрос пользователя.
 *
 * Раньше запись, не влезающая в контекст Gemma, обрезалась посередине: на вопрос
 * о середине встречи модель уверенно отвечала «в записи этого нет». Теперь текст
 * режется на фрагменты по предложениям, фрагменты ранжируются по совпадению
 * основ слов с вопросом (как в [SemanticSearch]) и в промпт идут лучшие — в
 * исходном порядке, плюс соседи, чтобы не терять контекст реплики.
 */
object QaContextSelector {
    private const val PIECE_CHARS = 700
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+|\\n+")
    const val GAP = "\n[…]\n"

    data class Selection(val text: String, val complete: Boolean, val usedPieces: Int, val totalPieces: Int)

    /** Режет текст на фрагменты ~[PIECE_CHARS] по границам предложений. */
    fun pieces(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (sentence in text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (cur.isNotEmpty() && cur.length + sentence.length + 1 > PIECE_CHARS) {
                out += cur.toString(); cur.setLength(0)
            }
            var s = sentence
            while (s.length > PIECE_CHARS) {           // предложение без точек — режем по пробелу
                val cut = s.lastIndexOf(' ', PIECE_CHARS).takeIf { it > PIECE_CHARS / 2 } ?: PIECE_CHARS
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                out += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (s.isEmpty()) continue
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun score(piece: Set<String>, query: List<String>): Float {
        if (query.isEmpty()) return 0f
        var s = 0f
        for (q in query) when {
            q in piece -> s += 1f
            piece.any { it.length >= 4 && q.length >= 4 && (it.startsWith(q) || q.startsWith(it)) } -> s += 0.5f
        }
        return s
    }

    /** Возвращает текст не длиннее [maxChars]: целиком, если влезает, иначе лучшие фрагменты. */
    fun select(text: String, question: String, maxChars: Int): Selection {
        if (text.length <= maxChars) return Selection(text, true, 1, 1)
        val pcs = pieces(text)
        val query = SemanticSearch.tokenize(question).distinct()
        val stems = pcs.map { SemanticSearch.tokenize(it).toHashSet() }
        val base = pcs.indices.map { score(stems[it], query) }
        // Сосед делит часть веса: ответ часто в реплике сразу после упоминания.
        val ranked = pcs.indices.sortedWith(
            compareByDescending<Int> {
                base[it] + 0.3f * ((base.getOrNull(it - 1) ?: 0f) + (base.getOrNull(it + 1) ?: 0f))
            }.thenBy { it }   // при равенстве (вопрос «о чём запись?») — с начала
        )
        val chosen = sortedSetOf<Int>()
        var used = 0
        for (i in ranked) {
            val cost = pcs[i].length + GAP.length
            if (used + cost > maxChars) continue
            chosen += i; used += cost
        }
        if (chosen.isEmpty()) chosen += ranked.first()
        val sb = StringBuilder()
        var prev = -2
        for (i in chosen) {
            if (sb.isNotEmpty()) sb.append(if (i == prev + 1) " " else GAP)
            else if (i > 0) sb.append(GAP.trimStart())
            sb.append(pcs[i])
            prev = i
        }
        if (prev < pcs.lastIndex) sb.append(GAP.trimEnd())
        return Selection(sb.toString().take(maxChars), false, chosen.size, pcs.size)
    }
}
