package com.example.guzlikvoice

import java.util.UUID

/**
 * Передача больших текстов (расшифровка, протокол) между экранами без Bundle.
 *
 * Аргументы фрагментов Android сохраняет при уходе приложения в фон и передаёт
 * системе через Binder (лимит ~1 МБ на всё сразу). Расшифровка часовой записи с
 * фразами весила ~0,9 МБ — приложение падало с TransactionTooLargeException.
 * Здесь текст живёт в памяти процесса, в аргументах — только короткий ключ.
 * Заодно расшифрованный текст защищённых записей не попадает в сохранённое
 * состояние. После перезапуска процесса ключ не найдётся — экран закрывается.
 */
object TextHandoff {
    private const val MAX = 8

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun put(text: String?): String? = text?.let { t -> UUID.randomUUID().toString().also { map[it] = t } }

    @Synchronized
    fun get(id: String?): String? = id?.let { map[it] }
}
