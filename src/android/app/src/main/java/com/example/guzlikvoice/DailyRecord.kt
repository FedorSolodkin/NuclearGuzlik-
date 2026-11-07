package com.example.guzlikvoice

import java.text.SimpleDateFormat
import java.util.*

data class DailyRecord(
    val date: String, // "yyyy-MM-dd"
    val entries: MutableList<Entry> = mutableListOf(),
    var daySummary: String = ""   // сохранённый анализ дня
) {
    data class Entry(
        val timestamp: Long,
        val text: String,
        val source: String, // "voice", "text"
        val structured: String = "",
        val keyThoughts: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val audioPath: String = "",        // путь к AAC-файлу записи
        val templateId: String? = null,    // id применённого шаблона (LlmHelper.Template.id)
        val templateResult: String? = null,// Markdown-результат применения шаблона
        val clientId: String? = null,      // Client.id, если запись привязана к клиенту
        val isEncrypted: Boolean = false,  // Vault: text/structured/templateResult в VaultManager, а не здесь
        val calendarEventId: String? = null, // provider-native id события календаря
        // Фразы с таймкодами (startMs/endMs), уже посчитанные Whisper во время записи —
        // сохраняем, чтобы кнопка "Тайм-коды" в TranscriptionDetailSheet не гоняла
        // распознавание заново, а сразу строила кликабельный текст.
        val segments: List<WhisperHelper.Segment>? = null
    )

    fun getAllText(): String = entries.joinToString("\n\n") { it.text }
    fun getStructuredSummary(): String {
        return entries.joinToString("\n\n---\n\n") { it.structured.ifEmpty { it.text } }
    }
}
