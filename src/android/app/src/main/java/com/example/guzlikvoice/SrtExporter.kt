package com.example.guzlikvoice

/**
 * Конвертирует сегменты Whisper в SRT-строку (SubRip subtitle format).
 *
 * SRT-блок:
 *   1
 *   00:00:00,000 --> 00:00:04,500
 *   Первая реплика.
 *   (пустая строка)
 */
object SrtExporter {

    fun toSrt(segments: List<WhisperHelper.Segment>): String {
        val sb = StringBuilder()
        var idx = 1
        for (seg in segments) {
            val text = seg.text.trim()
            if (text.isEmpty()) continue
            if (seg.endMs <= seg.startMs) continue
            sb.append(idx).append('\n')
            sb.append(formatTime(seg.startMs)).append(" --> ").append(formatTime(seg.endMs)).append('\n')
            sb.append(text).append('\n').append('\n')
            idx++
        }
        return sb.toString()
    }

    /** SRT-таймкод: HH:MM:SS,mmm (миллисекунды через запятую). */
    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe / 60_000) % 60
        val s = (safe / 1_000) % 60
        val millis = safe % 1_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
