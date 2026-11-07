package com.example.guzlikvoice

import android.content.Context

/**
 * Подсказка для Whisper из «Моего словаря» ([AppPrefs.getCustomDictionary]).
 *
 * Whisper видит подсказку как «предыдущий текст», поэтому слова из неё чаще
 * пишутся именно так — это помогает с именами, фамилиями, названиями.
 * Лимит подсказки ~224 токена (≈500 символов кириллицы) — оставляем место
 * под пример разговорной речи, который добавляет [TranscriptionSession].
 */
object Glossary {
    private const val MAX_CHARS = 300

    fun buildPrompt(ctx: Context): String {
        val words = AppPrefs.getCustomDictionary(ctx)
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        for (w in words) {
            val add = if (sb.isEmpty()) w else ", $w"
            if (sb.length + add.length > MAX_CHARS) break   // не режем слово посередине
            sb.append(add)
        }
        return sb.toString()
    }
}
