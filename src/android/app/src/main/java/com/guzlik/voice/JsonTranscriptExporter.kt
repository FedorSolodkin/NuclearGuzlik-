package com.guzlik.voice

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.util.Locale

/** Nuclear IT Hack wire format. Times come only from saved acoustic inference. */
object JsonTranscriptExporter {
    data class Turn(
        @SerializedName("speaker_id") val speakerId: String,
        @SerializedName("start_ms") val startMs: Long,
        @SerializedName("end_ms") val endMs: Long,
        val text: String,
    )
    data class Document(val transcript: List<Turn>)
    private val header = Regex("\\[(?:Говорящий ([^\\]]+)|Говорят одновременно)]")
    private val word = Regex("[\\p{L}\\p{N}]+")
    private data class Anchor(val key: String, val start: Long, val end: Long)
    private const val MIN_TURN_MS = 100L

    fun export(text: String, segments: List<WhisperHelper.Segment>, labels: List<String>): String {
        require(labels.isNotEmpty()) { "Сначала выполните разделение по голосам" }
        require(labels.distinct().size == labels.size) { "Задайте разные имена говорящим" }
        val anchors = segments.flatMap { segment ->
            val units = segment.words?.takeIf { it.isNotEmpty() }
                ?: listOf(WhisperHelper.Word(segment.text, segment.startMs, segment.endMs, 0f))
            units.flatMap { unit ->
                require(unit.startMs >= 0 && unit.endMs >= unit.startMs) { "Некорректные временные метки; распознайте запись заново" }
                word.findAll(unit.text).map { Anchor(it.value.lowercase(Locale.ROOT), unit.startMs, unit.endMs) }.toList()
            }
        }
        val markers = header.findAll(text).toList()
        val chunks = mutableListOf<Pair<String, String>>()
        if (markers.isEmpty()) {
            require(labels.size == 1) { "Метки говорящих удалены; выполните разделение заново" }
            chunks += "speaker_1" to text.trim()
        } else {
            require(text.substring(0, markers.first().range.first).isBlank()) { "В начале текста нет метки говорящего; разделите запись заново" }
            markers.forEachIndexed { i, marker ->
                val label = marker.groups[1]?.value
                val id = when (label) {
                    null -> "overlap"
                    "не определён" -> "unknown"
                    else -> {
                        val index = labels.indexOf(label)
                        require(index >= 0) { "Неизвестная метка говорящего; разделите запись заново" }
                        "speaker_${index + 1}"
                    }
                }
                val end = markers.getOrNull(i + 1)?.range?.first ?: text.length
                chunks += id to text.substring(marker.range.last + 1, end).trim()
            }
        }
        var cursor = 0
        val turns = chunks.filter { it.second.isNotBlank() }.map { (id, body) ->
            val matched = mutableListOf<Anchor>()
            for (token in word.findAll(body)) {
                val key = token.value.lowercase(Locale.ROOT)
                val at = (cursor until minOf(anchors.size, cursor + 24)).firstOrNull { anchors[it].key == key }
                if (at != null) { matched += anchors[at]; cursor = at + 1 }
            }
            require(matched.isNotEmpty()) { "Нет временной привязки для реплики; распознайте запись заново" }
            val start = matched.minOf { it.start }
            val rawEnd = matched.maxOf { it.end }
            // Whisper may give a one-word reply identical start and end; the start is a real acoustic time, only the
            // duration is degenerate. Keep the text and widen the turn to a minimal span instead of cancelling the export.
            val end = if (rawEnd > start) rawEnd else start + MIN_TURN_MS
            Turn(id, start, end, body)
        }
        return toJson(turns)
    }

    private fun toJson(turns: List<Turn>): String =
        GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(Document(turns))

    enum class Mode { STRICT, LENIENT, SEGMENTS_ONLY, TEXT_ONLY }

    /** [reason] — почему строгий экспорт не получился (null для STRICT). */
    data class Outcome(val json: String, val mode: Mode, val reason: String?)

    /**
     * Экспорт, который не отказывает. Сначала строгий [export]; если он бросил исключение, по очереди пробуем более
     * терпимые способы: [lenient] (метки и времена восстанавливаются приблизительно), только фразы распознавания
     * (один говорящий), только текст. Время начала берётся из распознавания, где оно есть; выдуманных личностей не
     * создаётся (неизвестные остаются `speaker_N` по порядку, `unknown` и `overlap` сохраняются).
     */
    fun exportBestEffort(text: String, segments: List<WhisperHelper.Segment>?, labels: List<String>): Outcome {
        val segs = segments.orEmpty()
        val reason: String
        try { return Outcome(export(text, segs, labels), Mode.STRICT, null) }
        catch (e: Exception) { reason = e.message ?: e.javaClass.simpleName }
        try { return Outcome(lenient(text, segs, labels), Mode.LENIENT, reason) } catch (_: Exception) { }
        try {
            val turns = segs.filter { it.text.isNotBlank() }.map {
                val start = it.startMs.coerceAtLeast(0)
                Turn("speaker_1", start, maxOf(it.endMs, start + MIN_TURN_MS), it.text.trim())
            }
            if (turns.isNotEmpty()) return Outcome(toJson(turns), Mode.SEGMENTS_ONLY, reason)
        } catch (_: Exception) { }
        val body = text.replace(header, " ").trim()
        val turns = if (body.isEmpty()) emptyList() else
            listOf(Turn("speaker_1", 0, maxOf(1000L, word.findAll(body).count() * 400L), body))
        return Outcome(toJson(turns), Mode.TEXT_ONLY, reason)
    }

    private fun lenient(text: String, segments: List<WhisperHelper.Segment>, labels: List<String>): String {
        val anchors = segments.flatMap { segment ->
            val units = segment.words?.takeIf { it.isNotEmpty() }
                ?: listOf(WhisperHelper.Word(segment.text, segment.startMs, segment.endMs, 0f))
            units.flatMap { unit ->
                val start = unit.startMs.coerceAtLeast(0)
                val end = maxOf(unit.endMs, start)
                word.findAll(unit.text).map { Anchor(it.value.lowercase(Locale.ROOT), start, end) }.toList()
            }
        }
        val lastAnchorEnd = anchors.maxOfOrNull { it.end } ?: 0L
        val extra = mutableMapOf<String, String>()
        fun idFor(label: String?): String = when (label) {
            null -> "overlap"
            "не определён" -> "unknown"
            else -> {
                val index = labels.indexOf(label)
                if (index >= 0) "speaker_${index + 1}"
                else extra.getOrPut(label) { "speaker_${labels.size + extra.size + 1}" }
            }
        }
        val markers = header.findAll(text).toList()
        val chunks = mutableListOf<Pair<String, String>>()
        if (markers.isEmpty()) chunks += "speaker_1" to text.trim()
        else {
            val preface = text.substring(0, markers.first().range.first).trim()
            if (preface.isNotEmpty()) chunks += "speaker_1" to preface
            markers.forEachIndexed { i, marker ->
                val end = markers.getOrNull(i + 1)?.range?.first ?: text.length
                chunks += idFor(marker.groups[1]?.value) to text.substring(marker.range.last + 1, end).trim()
            }
        }
        var cursor = 0
        var previousEnd = 0L
        val turns = chunks.filter { it.second.isNotBlank() }.map { (id, body) ->
            val matched = mutableListOf<Anchor>()
            for (token in word.findAll(body)) {
                val key = token.value.lowercase(Locale.ROOT)
                val at = (cursor until minOf(anchors.size, cursor + 24)).firstOrNull { anchors[it].key == key }
                if (at != null) { matched += anchors[at]; cursor = at + 1 }
            }
            val start: Long
            var end: Long
            if (matched.isNotEmpty()) {
                start = matched.minOf { it.start }
                end = matched.maxOf { it.end }
            } else {
                // Нет привязки (текст переписан): ставим реплику сразу после предыдущей, длительность по числу слов.
                start = previousEnd
                end = start + maxOf(MIN_TURN_MS, word.findAll(body).count() * 300L)
                if (lastAnchorEnd > start) end = minOf(end, lastAnchorEnd).coerceAtLeast(start + MIN_TURN_MS)
            }
            if (end <= start) end = start + MIN_TURN_MS
            previousEnd = maxOf(previousEnd, end)
            Turn(id, start, end, body)
        }
        return toJson(turns)
    }
}
