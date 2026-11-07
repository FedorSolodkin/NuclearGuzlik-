package com.guzlik.voice

/**
 * Страховка от выдуманных пунктов: пункт оставляем, только если его ключевые
 * слова реально звучат в тексте записи. Модель 2B иногда копирует пункты из
 * инструкции или придумывает правдоподобные дела («позвонить маме до пятницы»
 * в записи, где про маму не было ни слова).
 *
 * Сравнение по основам слов (первые 5 букв после стемминга [SemanticSearch]):
 * «массаж» совпадёт с «массажа», «записаться» — с «запишусь» не обязательно,
 * поэтому достаточно совпадения хотя бы трети значимых слов пункта.
 */
object ReminderGrounding {
    private const val MIN_WORD = 4
    private const val PREFIX = 5

    private fun keys(text: String): List<String> =
        SemanticSearch.tokenize(text)
            .filter { it.length >= MIN_WORD }
            .map { it.take(PREFIX) }

    fun isGrounded(item: String, source: Set<String>): Boolean {
        val k = keys(item)
        if (k.isEmpty()) return true            // нечего проверять («Спать в 12»)
        val hits = k.count { it in source }
        return hits >= 1 && hits * 3 >= k.size
    }

    fun filter(items: List<String>, sourceText: String): List<String> {
        val source = keys(sourceText).toHashSet()
        return items.filter { isGrounded(it, source) }
    }
}
