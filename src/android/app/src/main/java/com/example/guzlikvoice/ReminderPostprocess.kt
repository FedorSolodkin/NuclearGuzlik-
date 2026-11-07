package com.example.guzlikvoice

/**
 * Чистка пунктов «Выделить главное» после модели. Ошибки найдены прогоном на
 * телефоне (tools/device_test, run.py llm):
 *  • Gemma на длинном тексте склеивает 3–4 задачи в один пункт
 *    («Созвониться с Ильёй… Отправить Марине счёт… Купить подарок маме.»);
 *  • дообученная Qwen 0.6B изредка вставляет иероглифы («отправить счёт на奖金»).
 */
object ReminderPostprocess {
    private val SENTENCE_BREAK = Regex("""(?<=[.!?…])\s+(?=[А-ЯЁA-Z])""")
    private val CJK = Regex("""[぀-ヿ㐀-䶿一-鿿가-힯]""")

    /** Пункт из нескольких предложений → отдельные пункты (если каждое — не меньше двух слов). */
    fun splitMerged(item: String): List<String> {
        val parts = item.split(SENTENCE_BREAK).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.any { it.split(Regex("\\s+")).size < 2 }) return listOf(item.trim())
        return parts
    }

    fun hasForeignScript(item: String): Boolean = CJK.containsMatchIn(item)

    fun clean(items: List<String>): List<String> =
        items.flatMap(::splitMerged).filterNot(::hasForeignScript)
}
