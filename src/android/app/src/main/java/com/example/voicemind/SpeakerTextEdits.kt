package com.example.voicemind

/** Relabel a selected passage, retaining the surrounding speaker and every word. */
object SpeakerTextEdits {
    private val marker = Regex("\\[(?:Говорящий [^\\]]+|Говорят одновременно)]")
    fun relabel(text: String, from: Int, to: Int, header: String): String {
        require(from in 0..text.length && to in from..text.length && from < to)
        require(marker.matches(header))
        // A selection may include whole markers but must never cut a marker in half.
        val all = marker.findAll(text).toList()
        var start = from; var end = to
        all.forEach { m ->
            if (start > m.range.first && start <= m.range.last) start = m.range.first
            if (end > m.range.first && end <= m.range.last) end = m.range.last + 1
        }
        val body = marker.replace(text.substring(start,end), "").trim()
        require(body.isNotBlank())
        val before = text.substring(0,start).trimEnd()
        val after = text.substring(end).trimStart()
        val restore = all.lastOrNull { it.range.first < end }?.value
        val suffix = if (after.isEmpty()) "" else if (marker.find(after)?.range?.first == 0) "\n\n$after"
            else "\n\n${restore ?: "[Говорящий не определён]"}\n$after"
        val prefix = if (before.isEmpty()) "" else "$before\n\n"
        // Drop headers left empty by selecting the first words of a block.
        return (prefix + header + "\n" + body + suffix)
            .replace(Regex("(\\[(?:Говорящий [^\\]]+|Говорят одновременно)])\\s*(?=\\[(?:Говорящий [^\\]]+|Говорят одновременно)])"), "").trim()
    }
}
