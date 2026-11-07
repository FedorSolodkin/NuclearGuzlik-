package com.guzlik.voice

/** Attach time links to the current transcript; never rebuild or discard user edits. */
object TranscriptTiming {
    fun align(text: String, segments: List<WhisperHelper.Segment>): List<DoubtfulWords.Mark> {
        val searchable = text.toCharArray()
        Regex("\\[(?:Говорящий[^\\]]*|Говорят одновременно)\\]").findAll(text).forEach { match ->
            for (i in match.range) searchable[i] = ' '
        }
        val words = segments.flatMap { segment ->
            segment.words?.takeIf { it.isNotEmpty() }?.map { it.copy(p = 0f) }
                ?: Regex("\\S+").findAll(segment.text).map {
                    WhisperHelper.Word(it.value,segment.startMs,segment.endMs,0f)
                }.toList()
        }
        return DoubtfulWords.align(String(searchable),words,1f)
    }
}
