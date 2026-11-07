package com.example.voicemind

object TextFormatter {

    // Артефакты которые Whisper иногда генерирует
    private val whisperArtifacts = Regex(
        "\\[.*?\\]|\\(.*?\\)|<.*?>|" +
        "(?i)(thanks for watching|please subscribe|subtitles by|transcribed by)"
    )

    // Повторяющиеся фразы (Whisper-галлюцинация при тишине)
    private val repeatedPhrase = Regex("(.{10,})\\1{2,}")

    private val questionStarters = setOf(
        "как", "что", "зачем", "почему", "когда", "где", "куда", "откуда",
        "сколько", "кто", "какой", "какая", "какие", "чей", "ли", "разве",
        "неужели", "a", "an", "what", "where", "when", "why", "how", "who"
    )

    private val exclamationWords = setOf(
        "ура", "вау", "класс", "кайф", "круто", "жесть", "блин", "офигеть"
    )

    private val introductoryWords = setOf(
        "кстати", "однако", "значит", "короче", "возможно", "наверное",
        "вероятно", "итак", "например", "конечно", "разумеется", "видимо"
    )

    fun format(rawText: String): String {
        if (rawText.isBlank()) return ""

        // Если текст содержит метки говорящих — только чистим артефакты, не трогаем структуру
        if (rawText.contains(Regex("\\[(?:Говорящий [^\\]]+|Говорят одновременно)]"))) {
            // whisperArtifacts содержит [.*?] — нельзя применять, сотрёт метки говорящих
            return rawText
                .replace(repeatedPhrase, "$1")
                .let { fixMidSentencePeriods(it) }
                .trim()
        }

        var text = rawText
            .replace(whisperArtifacts, "")
            .replace(repeatedPhrase, "$1")
            .let { fixMidSentencePeriods(it) }
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank()) return ""

        return if (hasPunctuation(text)) {
            addParagraphBreaks(capitalizeSentences(text))
        } else {
            val chunks = splitToChunks(text.lowercase())
            val sentences = chunks.map { formatChunk(it) }.filter { it.isNotBlank() }
            addParagraphBreaks(sentences.joinToString(" "))
        }
    }

    // Точка перед строчной буквой — это пауза в речи, не конец предложения
    private fun fixMidSentencePeriods(text: String): String {
        // "слово. другое слово" → "слово, другое слово" (запятая вместо точки)
        var result = text.replace(Regex("\\.[ \t]+([\\p{Ll}а-яёa-z])")) { mr ->
            ", ${mr.groupValues[1]}"
        }
        // убираем лишние запятые перед союзами (", и", ", а" и т.п.)
        result = result.replace(Regex(",\\s+(и|а|но|или|то есть|то)\\s"), " $1 ")
        return result
    }

    private fun hasPunctuation(text: String) =
        text.count { it == '.' || it == '?' || it == '!' } >= text.split(" ").size / 8

    private fun capitalizeSentences(text: String): String {
        return text.replace(Regex("([.!?]\\s+)(\\p{L})")) { mr ->
            mr.groupValues[1] + mr.groupValues[2].uppercase()
        }.replaceFirstChar { it.uppercase() }
    }

    private fun addParagraphBreaks(text: String): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        if (sentences.size <= 4) return text
        return sentences.chunked(4).joinToString("\n\n") { it.joinToString(" ") }
    }

    private fun splitToChunks(text: String): List<String> {
        if (text.length < 100) return listOf(text)
        val words = text.split(" ")
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        for ((i, word) in words.withIndex()) {
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(word)
            val next = words.getOrNull(i + 1)
            val count = cur.count { it == ' ' } + 1
            if (count >= 12 && next != null && next in questionStarters) {
                result += cur.toString()
                cur.clear()
            } else if (count >= 18) {
                result += cur.toString()
                cur.clear()
            }
        }
        if (cur.isNotEmpty()) result += cur.toString()
        return result
    }

    private fun formatChunk(chunk: String): String {
        var s = chunk.trim().trimEnd('.', '!', '?', ',')
        if (s.isEmpty()) return ""
        for (w in introductoryWords) {
            s = s.replace(Regex("(^|\\s)($w)\\s+"), "$1$2, ")
        }
        val first = s.substringBefore(' ').lowercase()
        val ending = when {
            first in questionStarters -> "?"
            exclamationWords.any { s.contains(it) } -> "!"
            else -> "."
        }
        return s.replaceFirstChar { it.titlecase() } + ending
    }

    fun title(formatted: String, max: Int = 60): String {
        val first = formatted
            .substringBefore('.')
            .substringBefore('?')
            .substringBefore('!')
            .substringBefore('\n')
            .trim()
        return if (first.length <= max) first else first.take(max - 1) + "…"
    }
}
