package com.example.guzlikvoice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Log-mel fbank в точности как `torchaudio.compliance.kaldi.fbank` с настройками
 * WeSpeaker (80 мел-фильтров, окно 25 мс, шаг 10 мс, dither=0) — на таких
 * признаках обучена модель голосов. Сверено юнит-тестом с эталоном torchaudio
 * (FbankExtractorTest).
 *
 * Прежняя версия считала фильтры с округлением границ до бинов FFT: у нижних
 * фильтров левая граница и центр попадали в один бин, вес становился 0/0 = NaN,
 * и все признаки (а за ними эмбеддинги) были NaN — кластеризация не сливала
 * ни одного голоса («61 говорящий» на 2-минутной записи).
 */
object FbankExtractor {

    const val SAMPLE_RATE = 16000
    const val NUM_FILTERS = 80

    private const val FRAME_LEN   = 400   // 25 ms
    private const val FRAME_SHIFT = 160   // 10 ms
    private const val FFT_SIZE    = 512
    private const val NUM_BINS    = FFT_SIZE / 2
    private const val LOW_HZ      = 20.0
    private const val HIGH_HZ     = 8000.0
    private const val PRE_EMPH    = 0.97f
    private const val EPS         = 1.1920929e-07f   // torch.finfo(float32).eps

    /** Окно Povey (Kaldi по умолчанию): hann^0.85. */
    private val window: FloatArray by lazy {
        FloatArray(FRAME_LEN) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_LEN - 1))).pow(0.85).toFloat()
        }
    }

    /** banks[m][k] — вес бина k в фильтре m; треугольники в мел-шкале, как в Kaldi. */
    private val banks: Array<FloatArray> by lazy {
        val melLow = mel(LOW_HZ)
        val melHigh = mel(HIGH_HZ)
        val delta = (melHigh - melLow) / (NUM_FILTERS + 1)
        val binWidth = SAMPLE_RATE.toDouble() / FFT_SIZE
        Array(NUM_FILTERS) { m ->
            val left = melLow + m * delta
            val center = left + delta
            val right = center + delta
            FloatArray(NUM_BINS) { k ->
                val melK = mel(k * binWidth)
                val up = (melK - left) / (center - left)
                val down = (right - melK) / (right - center)
                max(0.0, min(up, down)).toFloat()
            }
        }
    }

    private fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)

    /**
     * Признаки [T][80] с вычитанием среднего по времени (CMN, как в WeSpeaker).
     * Дисперсию не нормализуем — модель на такое не обучена.
     */
    fun extract(pcm: ShortArray): Array<FloatArray> {
        val f = extractRaw(pcm)
        if (f.isEmpty()) return f
        val mean = FloatArray(NUM_FILTERS)
        for (frame in f) for (i in 0 until NUM_FILTERS) mean[i] += frame[i]
        for (i in 0 until NUM_FILTERS) mean[i] = mean[i] / f.size
        for (frame in f) for (i in 0 until NUM_FILTERS) frame[i] -= mean[i]
        return f
    }

    /** Log-mel без CMN — ровно то, что возвращает torchaudio kaldi.fbank. */
    fun extractRaw(pcm: ShortArray): Array<FloatArray> {
        if (pcm.size < FRAME_LEN) return emptyArray()
        val numFrames = 1 + (pcm.size - FRAME_LEN) / FRAME_SHIFT
        val out = Array(numFrames) { FloatArray(NUM_FILTERS) }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val power = FloatArray(NUM_BINS)

        for (f in 0 until numFrames) {
            val start = f * FRAME_SHIFT
            // Kaldi работает со шкалой int16 (WeSpeaker умножает waveform на 1<<15).
            var mean = 0f
            for (i in 0 until FRAME_LEN) { re[i] = pcm[start + i].toFloat(); mean += re[i] }
            mean /= FRAME_LEN
            for (i in 0 until FRAME_LEN) re[i] -= mean                       // remove_dc_offset
            for (i in FRAME_LEN - 1 downTo 1) re[i] -= PRE_EMPH * re[i - 1]  // preemphasis
            re[0] -= PRE_EMPH * re[0]
            for (i in 0 until FRAME_LEN) re[i] *= window[i]
            for (i in FRAME_LEN until FFT_SIZE) re[i] = 0f
            im.fill(0f)

            fft(re, im)
            for (k in 0 until NUM_BINS) power[k] = re[k] * re[k] + im[k] * im[k]

            for (m in 0 until NUM_FILTERS) {
                val b = banks[m]
                var e = 0f
                for (k in 0 until NUM_BINS) e += b[k] * power[k]
                out[f][m] = ln(max(e, EPS))
            }
        }
        return out
    }

    // In-place Cooley-Tukey radix-2 FFT (size must be power of 2)
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angle = -2.0 * PI / len
            for (k in 0 until half) {
                val wr = cos(angle * k).toFloat()
                val wi = sin(angle * k).toFloat()
                var i = 0
                while (i < n) {
                    val a = i + k; val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr;        im[a] += ti
                    i += len
                }
            }
            len *= 2
        }
    }
}
