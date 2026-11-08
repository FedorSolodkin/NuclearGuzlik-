package com.guzlik.voice

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Выравнивание громкости куска перед распознаванием. Whisper хуже работает на тихой записи
 * (дальний микрофон, −40 дБ и ниже): усиливаем только тихие куски, громкие не трогаем.
 * Исходное аудио для диаризации и воспроизведения не меняется — работаем с копией куска.
 */
object PcmNormalizer {
    private const val FRAME = 800                 // 50 мс при 16 кГц
    private const val TARGET_RMS = 3000.0         // около −20 дБFS для речи
    private const val MAX_GAIN = 40.0             // не больше +32 дБ: дальше усиливается один шум
    private const val MIN_GAIN = 1.5              // небольшую разницу не трогаем
    private const val PEAK_LIMIT = 30000.0
    private const val SPEECH_PERCENTILE = 0.90

    /** Коэффициент усиления для [pcm] (1.0 — не менять). */
    fun gainFor(pcm: ShortArray): Double {
        val frames = pcm.size / FRAME
        if (frames < 4) return 1.0
        val rms = DoubleArray(frames)
        var peak = 0
        for (f in 0 until frames) {
            var e = 0.0
            val base = f * FRAME
            for (i in base until base + FRAME) { val s = pcm[i].toInt(); e += s.toDouble() * s; val a = abs(s); if (a > peak) peak = a }
            rms[f] = sqrt(e / FRAME)
        }
        rms.sort()
        val speech = rms[min(frames - 1, (frames * SPEECH_PERCENTILE).toInt())]
        if (speech < 1.0 || peak == 0) return 1.0
        val gain = min(min(TARGET_RMS / speech, MAX_GAIN), PEAK_LIMIT / peak)
        return if (gain >= MIN_GAIN) gain else 1.0
    }

    /** Копия [pcm] с усилением; сам [pcm] возвращается, если усиление не нужно. */
    fun normalize(pcm: ShortArray): ShortArray {
        val gain = gainFor(pcm)
        if (gain == 1.0) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) out[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        return out
    }
}
