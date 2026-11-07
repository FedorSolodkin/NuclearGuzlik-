package com.example.voicemind

/** A legacy/missing word timeline must affect only its own phrase. */
object DiarizationUnits {
    private fun normalized(text: String) = text.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
    private fun usable(s: WhisperHelper.Segment) = !s.words.isNullOrEmpty() &&
        s.words.all { it.startMs >= 0 && it.endMs >= it.startMs && it.text.isNotBlank() } &&
        normalized(s.text) == normalized(s.words.joinToString("") { it.text })

    fun canReuse(segments: List<WhisperHelper.Segment>) = segments.any { usable(it) }

    fun expand(segments: List<WhisperHelper.Segment>): List<WhisperHelper.Segment>? {
        if (!canReuse(segments)) return null
        return segments.flatMap { s ->
            if (usable(s)) s.words!!.map { WhisperHelper.Segment(it.text, it.startMs, it.endMs) }
            else listOf(s.copy(words = null))
        }
    }
}
