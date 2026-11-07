package com.example.guzlikvoice

/**
 * Разбивка длинного текста на смысловые чанки для последовательной обработки LLM.
 *
 * Прежний подход (500 симв «в лоб» с обрывом на пробеле) резал мысли пополам:
 * «Договорились встретиться в среду в 10» — в одном чанке, «утра у Кости»
 * — в следующем. Gemma по такому обрезку либо выдумывала контекст, либо
 * пропускала пункт.
 *
 * Здесь режем по осмысленным границам, от самых жёстких к самым мягким:
 *   1. Метки диарии `[Говорящий X]` — реплика одного говорящего не разрывается.
 *   2. Пустая строка / двойной перевод строки — абзац.
 *   3. Конец предложения (.!?…) — если абзац сам по себе слишком длинный.
 *   4. Запятые / пробел на границе MAX — крайний случай для реплики без пунктуации.
 *
 * Целимся в ~[TARGET_CHARS] на чанк, жёсткий потолок [MAX_CHARS]. Мелкие куски
 * копим до тех пор, пока не наберётся достаточно текста, а затем закрываем чанк.
 */
object SemanticChunker {

    /** Желаемый размер чанка. При n_ctx=8192 и оценке ~2.4 симв/токен для русского,
     *  3500 симв = ~1460 токенов. С few-shot промптом (~625 t) и ответом (~625 t) —
     *  всего ~2710 токенов, что даёт трёхкратный запас в контексте Gemma-2.
     *  Было 2000 (v3.5) — ~40% лишних LLM-вызовов из-за консервативной оценки. */
    const val TARGET_CHARS = 3500

    /** Жёсткий потолок. Транскрипты короче этого идут в модель одним вызовом
     *  без чанкинга — обычная 3-4 минутная запись помещается целиком. */
    const val MAX_CHARS = 5000

    /** Ниже этого порога чанк не закрываем — цепляем следующий кусок,
     *  чтобы не гонять LLM ради пары предложений. */
    private const val MIN_CHARS = 400

    private val SPEAKER_MARKER = Regex("(?=\\[Говорящий [^\\]]+])")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+")
    private val PARAGRAPH_SPLIT = Regex("\\n{2,}")

    fun chunk(text: String): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.length <= MAX_CHARS) return listOf(cleaned)

        // Шаг 1 — режем на «единицы» по самым жёстким границам:
        // сначала по репликам говорящих (маркер остаётся в начале следующего юнита
        // благодаря look-ahead), затем внутри каждого — по абзацам.
        val units = cleaned
            .split(SPEAKER_MARKER)
            .flatMap { it.split(PARAGRAPH_SPLIT) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Шаг 2 — юниты > MAX режем на предложения, а очень длинные предложения
        // — на куски по MAX_CHARS с попыткой попасть на пробел.
        val atoms = units.flatMap { unit ->
            if (unit.length <= MAX_CHARS) listOf(unit)
            else unit.split(SENTENCE_SPLIT).flatMap { splitHard(it) }
        }.filter { it.isNotEmpty() }

        // Шаг 3 — жадно склеиваем атомы в чанки, стараясь попасть в TARGET_CHARS
        // и никогда не выходя за MAX_CHARS.
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (atom in atoms) {
            val separator = if (current.isEmpty()) "" else " "
            val projected = current.length + separator.length + atom.length

            when {
                current.isEmpty() -> current.append(atom)

                projected > MAX_CHARS -> {
                    chunks += current.toString().trim()
                    current.setLength(0)
                    current.append(atom)
                }

                current.length >= TARGET_CHARS -> {
                    chunks += current.toString().trim()
                    current.setLength(0)
                    current.append(atom)
                }

                else -> current.append(separator).append(atom)
            }
        }
        if (current.isNotEmpty()) {
            val tail = current.toString().trim()
            // Мелкий хвост подклеиваем к предыдущему чанку, если вместе не превысим MAX.
            if (tail.length < MIN_CHARS && chunks.isNotEmpty() &&
                chunks.last().length + 1 + tail.length <= MAX_CHARS) {
                chunks[chunks.lastIndex] = chunks.last() + " " + tail
            } else {
                chunks += tail
            }
        }
        return chunks
    }

    /** Хард-сплит для реплики без пунктуации: режем по пробелу ближе к MAX_CHARS. */
    private fun splitHard(piece: String): List<String> {
        if (piece.length <= MAX_CHARS) return listOf(piece)
        val out = mutableListOf<String>()
        var start = 0
        while (start < piece.length) {
            var end = (start + MAX_CHARS).coerceAtMost(piece.length)
            if (end < piece.length) {
                val lastSpace = piece.lastIndexOf(' ', end)
                if (lastSpace > start + MIN_CHARS) end = lastSpace
            }
            out += piece.substring(start, end).trim()
            start = end
        }
        return out
    }
}
