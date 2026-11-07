package com.example.voicemind

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Офлайн-анализатор транскрипций.
 *
 * Алгоритм:
 *   1. Разбивка на предложения (поддерживает диалог [Говорящий X])
 *   2. TextRank (PageRank на графе схожести) для базовых рангов
 *   3. Буст за маркеры важности + позиционный буст (начало/конец)
 *   4. MMR-дедупликация при отборе ключевых мыслей
 *   5. Определение тегов-категорий по ключевым словам
 */
class TextAnalyzer {

    data class AnalysisResult(
        val structured:  String,
        val keyThoughts: List<String>,
        val tags:        List<String>,
    )

    // ─── Stop words ───────────────────────────────────────────────────────────

    private val stopWords = setOf(
        // русские
        "и","в","не","на","я","что","он","она","они","мы","вы","это","те","то",
        "с","а","но","по","до","из","за","же","как","от","или","при","для",
        "все","всё","был","была","было","были","есть","нет","уже","ещё","еще",
        "вот","так","там","тут","да","очень","более","менее","когда","если",
        "чтобы","потому","хотя","также","тоже","его","её","их","им","ей","нас",
        "вам","вас","нам","мне","тебе","себя","себе","ты","со","об","бы","ли",
        "же","ну","ведь","вот","вдруг","сам","сама","само","сами","только",
        // английские (на случай смешанной речи)
        "the","a","an","is","are","was","were","be","been","have","has","had",
        "do","does","did","will","would","could","should","may","might","it",
        "its","this","that","these","those","i","you","he","she","we","they",
        "me","him","her","us","them","my","your","his","our","their","of",
        "in","on","at","to","for","with","by","from","up","about","into","than"
    )

    // ─── Importance markers ───────────────────────────────────────────────────

    private val importanceMarkers = listOf(
        "важно", "важный", "важная", "важное",
        "нужно", "необходимо", "должен", "должна", "обязательно",
        "проблема", "решение", "идея", "вывод", "итог", "итого", "в итоге",
        "главное", "ключевое", "основное", "цель", "задача", "план",
        "запомнить", "не забыть", "todo", "сделать", "запланировать",
        "мысль", "инсайт", "понял", "осознал", "понял", "нашёл", "нашел",
        "результат", "выяснил", "узнал", "оказалось", "получается",
        "важно отметить", "стоит обратить", "ключевой момент",
        "нужно учесть", "нужно помнить", "главная мысль",
        "important", "key", "note", "remember", "todo", "action", "goal",
    )

    // ─── Tag patterns ─────────────────────────────────────────────────────────

    private val tagPatterns = mapOf(
        "#работа"    to listOf("работа","рабочий","проект","задача","код","программа",
                                "встреча","дедлайн","коллег","офис","клиент","заказ",
                                "разработка","релиз","sprint","таск","тикет"),
        "#идея"      to listOf("идея","придумал","придумала","можно","предложение",
                                "концепция","решение","вариант","подход","способ"),
        "#проблема"  to listOf("проблема","ошибка","баг","bug","не работает","сломал",
                                "сбой","зависает","упало","краш","фейл","fail"),
        "#личное"    to listOf("семья","дом","здоровье","самочувствие","настроение",
                                "отдых","сон","спорт","питание","личн"),
        "#обучение"  to listOf("изучил","изучила","прочитал","прочитала","узнал","узнала",
                                "книга","курс","лекция","урок","обучение","учёба","учеба",
                                "статья","доклад","видео","подкаст"),
        "#финансы"   to listOf("деньги","бюджет","расход","доход","платить","купить",
                                "стоимость","цена","зарплата","счёт","счет","оплата"),
        "#планы"     to listOf("план","планирую","планируем","буду","будем","собираюсь",
                                "хочу","хотим","завтра","на следующей","на будущей","потом"),
        "#встреча"   to listOf("встреча","встретился","встретилась","созвон","звонок",
                                "конференция","митинг","meeting","call","переговоры"),
        "#нейросеть" to listOf("нейросеть","модель","ai","ml","whisper","llm","gpt",
                                "распознавание","машинное обучение","нейронная"),
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    fun analyze(rawText: String): AnalysisResult {
        val text      = rawText.trim()
        val tags      = detectTags(text)
        val sentences = splitSentences(text)

        if (sentences.isEmpty()) {
            val snippet = text.take(280)
            return AnalysisResult(buildStructured(snippet, emptyList(), tags), emptyList(), tags)
        }

        // TextRank + boosts
        val ranks      = computeRanks(sentences)
        val keyThoughts = pickWithMMR(sentences, ranks, maxItems = 5)

        val structured = buildStructured(
            summary     = summarize(sentences, ranks),
            keyThoughts = keyThoughts,
            tags        = tags
        )
        return AnalysisResult(structured, keyThoughts, tags)
    }

    fun analyzeFullDayText(rawText: String): AnalysisResult = analyze(rawText)

    // ─── Sentence splitting ───────────────────────────────────────────────────

    private fun splitSentences(text: String): List<String> {
        // Разбиваем по: точка/!/?  + пробел, перевод строки, или конец блока [Говорящий X]
        val raw = text
            .replace(Regex("\\[Говорящий [^\\]]+]\\s*"), "\n")
            .split(Regex("(?<=[.!?…])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length > 8 }   // отбрасываем очень короткие обрывки

        // Объединяем слишком короткие фрагменты с предыдущим
        val merged = mutableListOf<String>()
        for (s in raw) {
            if (merged.isNotEmpty() && merged.last().length < 25 && !merged.last().last().let { it == '.' || it == '!' || it == '?' }) {
                merged[merged.lastIndex] = merged.last() + " " + s
            } else {
                merged += s
            }
        }
        return merged
    }

    // ─── TextRank + boosts ────────────────────────────────────────────────────

    private fun computeRanks(sentences: List<String>): DoubleArray {
        val n        = sentences.size
        val wordSets = sentences.map { tokenizeWords(it).toSet() }

        // Граф схожести Жаккара с логарифмическим знаменателем (как в оригинальном TextRank)
        val sim = Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j) return@DoubleArray 0.0
                val inter  = wordSets[i].intersect(wordSets[j]).size.toDouble()
                if (inter == 0.0) return@DoubleArray 0.0
                val denom  = ln(max(wordSets[i].size.toDouble(), 2.0)) +
                             ln(max(wordSets[j].size.toDouble(), 2.0))
                inter / denom
            }
        }

        // Row-normalize → стохастическая матрица
        for (i in 0 until n) {
            val s = sim[i].sum()
            if (s > 0.0) for (j in 0 until n) sim[i][j] /= s
        }

        // PageRank итерации
        val damping = 0.85
        var ranks   = DoubleArray(n) { 1.0 / n }
        repeat(30) {
            ranks = DoubleArray(n) { i ->
                (1.0 - damping) / n + damping * (0 until n).sumOf { j -> sim[j][i] * ranks[j] }
            }
        }

        // Буст за маркеры важности
        val lowerSents = sentences.map { it.lowercase() }
        for (i in 0 until n) {
            val boost = importanceMarkers.count { lowerSents[i].contains(it) }
            if (boost > 0) ranks[i] *= (1.0 + 0.25 * boost)
        }

        // Позиционный буст: первые 15% и последние 15% предложений чуть важнее
        val edge = max(1, (n * 0.15).toInt())
        for (i in 0 until min(edge, n))          ranks[i] *= 1.15
        for (i in max(0, n - edge) until n)       ranks[i] *= 1.10

        return ranks
    }

    // ─── MMR-дедупликация ─────────────────────────────────────────────────────
    //
    // Maximal Marginal Relevance: на каждом шаге выбираем предложение с
    // максимальным (relevance - λ * max_sim_to_selected), λ = 0.7.
    // Это даёт разнообразие без потери важности.

    private fun pickWithMMR(
        sentences: List<String>,
        ranks:     DoubleArray,
        maxItems:  Int,
    ): List<String> {
        val n       = sentences.size
        if (n == 0) return emptyList()

        val target  = min(maxItems, max(1, n * 2 / 3))
        val wordSets = sentences.map { tokenizeWords(it).toSet() }
        val lambda  = 0.7

        val selected  = mutableListOf<Int>()
        val remaining = (0 until n).toMutableList()

        while (selected.size < target && remaining.isNotEmpty()) {
            val best = remaining.maxByOrNull { i ->
                val rel      = ranks[i]
                val maxSim   = if (selected.isEmpty()) 0.0
                               else selected.maxOf { j -> jaccardSim(wordSets[i], wordSets[j]) }
                lambda * rel - (1.0 - lambda) * maxSim
            } ?: break

            // Не включаем предложения короче 15 символов
            if (sentences[best].length >= 15) selected += best
            remaining -= best
        }

        // Возвращаем в порядке появления в тексте
        return selected.sorted().map { sentences[it] }
    }

    private fun jaccardSim(a: Set<String>, b: Set<String>): Double {
        val inter = a.intersect(b).size.toDouble()
        val union = (a + b).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    // ─── Summary ──────────────────────────────────────────────────────────────

    private fun summarize(sentences: List<String>, ranks: DoubleArray): String {
        val topK = min(2, sentences.size)
        val top  = ranks.indices
            .sortedByDescending { ranks[it] }
            .take(topK)
            .sorted()
            .map { sentences[it] }
        return top.joinToString(" ")
    }

    // ─── Tokenization ─────────────────────────────────────────────────────────

    private fun tokenizeWords(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^а-яёa-z0-9]+"))
            .filter { it.length > 2 && it !in stopWords }

    // ─── Tag detection ────────────────────────────────────────────────────────

    private fun detectTags(text: String): List<String> {
        val lower = text.lowercase()
        return tagPatterns
            .filter { (_, kws) -> kws.any { lower.contains(it) } }
            .keys
            .toList()
    }

    // ─── Structured output ────────────────────────────────────────────────────

    private fun buildStructured(
        summary:     String,
        keyThoughts: List<String>,
        tags:        List<String>,
    ): String = buildString {
        if (summary.isNotBlank()) {
            appendLine("Краткое содержание")
            appendLine(summary.trim().take(300))
            appendLine()
        }
        if (keyThoughts.isNotEmpty()) {
            appendLine("Ключевые мысли (${keyThoughts.size})")
            keyThoughts.forEachIndexed { i, t -> appendLine("${i + 1}. $t") }
            appendLine()
        }
        if (tags.isNotEmpty()) {
            append("Теги: ${tags.joinToString("  ")}")
        }
    }.trim()
}
