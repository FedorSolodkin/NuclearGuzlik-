package com.guzlik.voice

/** Align native token clocks to the recognizer's canonical text, including literal spaces. */
internal object TokenWordTiming {
    fun build(text: String, tokens: Array<String>, timestamps: FloatArray,
              startMs: Long, endMs: Long): WhisperHelper.Segment? {
        val canonical = text.trim()
        if (canonical.isEmpty() || endMs <= startMs) return null
        fun coarse() = WhisperHelper.Segment(canonical, startMs, endMs, null)
        if (tokens.size != timestamps.size || timestamps.any { !it.isFinite() || it < 0 }) return coarse()
        val decoded = StringBuilder()
        val clocks = ArrayList<Long>()
        for (i in tokens.indices) {
            val time = (startMs + (timestamps[i] * 1000).toLong()).coerceIn(startMs, endMs - 1)
            for (char in tokens[i].replace('▁', ' ')) if (!char.isWhitespace()) {
                decoded.append(char); clocks.add(time)
            }
        }
        // Do not fabricate word times when native postprocessing changes the token text.
        if (decoded.toString() != canonical.filterNot { it.isWhitespace() } ||
            clocks.zipWithNext().any { (a, b) -> b < a }) return coarse()
        data class Word(var text: String, val first: Long, val last: Long)
        val items = mutableListOf<Word>()
        var position = 0
        var prefix = ""
        for (match in Regex("\\S+").findAll(canonical)) {
            val part = match.value
            val first = part.indexOfFirst { it.isLetterOrDigit() }
            val last = part.indexOfLast { it.isLetterOrDigit() }
            if (first < 0) {
                if (items.isEmpty()) prefix += part + " "
                else items.last().text += " " + part
            } else {
                items += Word(prefix + part, clocks[position + first], clocks[position + last])
                prefix = ""
            }
            position += part.length
        }
        if (items.isEmpty()) return coarse()
        val words = items.mapIndexed { i, word ->
            val naturalEnd = maxOf(word.first + 300, word.last + 120)
            val next = items.getOrNull(i + 1)?.first ?: naturalEnd
            val end = minOf(next, naturalEnd).coerceIn(word.first + 1, endMs)
            WhisperHelper.Word(word.text, word.first, end, 1f)
        }
        return WhisperHelper.Segment(canonical, words.first().startMs, words.last().endMs, words)
    }
}
