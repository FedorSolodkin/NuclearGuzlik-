package com.guzlik.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Быстрый распознаватель: GigaAM v3 (NeMo CTC, с пунктуацией и регистром) через sherpa-onnx.
 * Только русский. Режется по речи на куски до [MAX_CHUNK_MS], каждый кусок распознаётся
 * целиком; слова собираются из токенов с временем (время куска + время токена).
 *
 * Файлы модели: filesDir/gigaam/model.int8.onnx и tokens.txt (в APK не входят).
 */
class GigaAmEngine(private val context: Context) : AsrEngine {

    companion object {
        private const val TAG = "GigaAmEngine"
        const val DIR = "gigaam"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val MIN_MODEL_BYTES = 100_000_000L
        private const val SR = 16_000
        private const val MAX_CHUNK_MS = 22_000L
        private const val MIN_CHUNK_MS = 8_000L        // короче — склеиваем соседние речевые куски, если пауза небольшая
        private const val JOIN_GAP_MS = 700L
        private const val PAD_MS = 300L

        // Встроенные в APK файлы (BundledModels копирует их в filesDir под плоскими именами).
        const val BUNDLED_MODEL_FILE = "gigaam_v3_ctc_punct.int8.onnx"
        const val BUNDLED_TOKENS_FILE = "gigaam_v3_tokens.txt"
        const val BUNDLED_MODEL_BYTES = 224_893_661L
        const val BUNDLED_TOKENS_BYTES = 2_007L

        /** Плоские имена (APK) приоритетнее папки filesDir/gigaam/ (ручная раскладка для замеров). */
        private fun pair(ctx: Context): Pair<File, File> {
            val flat = File(ctx.filesDir, BUNDLED_MODEL_FILE) to File(ctx.filesDir, BUNDLED_TOKENS_FILE)
            if (flat.first.isFile && flat.second.isFile) return flat
            val dir = File(ctx.filesDir, DIR)
            return File(dir, MODEL_FILE) to File(dir, TOKENS_FILE)
        }
        fun modelFile(ctx: Context) = pair(ctx).first
        fun tokensFile(ctx: Context) = pair(ctx).second
        fun isInstalled(ctx: Context) = modelFile(ctx).let { it.isFile && it.length() > MIN_MODEL_BYTES } &&
            tokensFile(ctx).isFile
    }

    @Volatile override var isReady = false
        private set
    override var loadedModelName = ""
        private set

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val decodeLock = Any()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        if (isReady) { onReady(); return }
        Thread {
            try {
                if (!isInstalled(context)) {
                    onError("Модель GigaAM не найдена (filesDir/$DIR/$MODEL_FILE)")
                    return@Thread
                }
                val cfg = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SR, featureDim = 64),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile(context).absolutePath),
                        tokens = tokensFile(context).absolutePath,
                        numThreads = 4,
                        provider = "cpu",
                        modelType = "nemo_ctc",
                    ),
                    decodingMethod = "greedy_search",
                )
                recognizer = OfflineRecognizer(config = cfg)
                loadedModelName = "gigaam-v3-ctc-punct-int8"
                isReady = true
                onReady()
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                onError("Не удалось запустить GigaAM: ${e.message}")
            }
        }.start()
    }

    override fun transcribe(pcm: ShortArray, initialPrompt: String): String =
        transcribeSegments(pcm, initialPrompt).joinToString(" ") { it.text }.trim()

    override fun transcribeSegments(
        pcm: ShortArray, initialPrompt: String, carryPrompt: Boolean, useVad: Boolean, language: String,
        cancelToken: Long,
    ): List<WhisperHelper.Segment> {
        val rec = recognizer
        check(isReady && rec != null) { "GigaAM is not ready" }
        if (pcm.isEmpty()) return emptyList()
        val chunks = planChunks(pcm, useVad, cancelToken)
        val out = ArrayList<WhisperHelper.Segment>(chunks.size)
        val t0 = System.currentTimeMillis()
        for ((startMs, endMs) in chunks) {
            if (isCancelled(cancelToken)) break
            val from = (startMs * SR / 1000).toInt().coerceIn(0, pcm.size)
            val to = (endMs * SR / 1000).toInt().coerceIn(from, pcm.size)
            if (to - from < SR / 5) continue
            val samples = FloatArray(to - from) { pcm[from + it] / 32768f }
            val seg = synchronized(decodeLock) {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, SR)
                    rec.decode(stream)
                    rec.getResult(stream)
                } finally { stream.release() }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "chunk $startMs-$endMs: ${seg.text.take(160)} | t0=${seg.timestamps.firstOrNull()}")
            TokenWordTiming.build(seg.text, seg.tokens, seg.timestamps, startMs, endMs)?.let { out += it }
        }
        Log.i(TAG, "decoded ${pcm.size / SR} s audio in ${System.currentTimeMillis() - t0} ms, chunks=${chunks.size}")
        return out
    }

    /** Речевые куски [startMs, endMs) в шкале исходного pcm, каждый ≤ [MAX_CHUNK_MS]. */
    private fun planChunks(pcm: ShortArray, useVad: Boolean, cancelToken: Long): List<Pair<Long, Long>> {
        val totalMs = pcm.size * 1000L / SR
        val speech: List<Pair<Long, Long>> = if (useVad) {
            runVad(pcm, cancelToken) ?: return emptyList()
        } else listOf(0L to totalMs)
        if (speech.isEmpty()) return emptyList()

        // 1) расширяем на PAD_MS, 2) склеиваем через короткие паузы, пока кусок ≤ MAX_CHUNK_MS
        val merged = ArrayList<Pair<Long, Long>>()
        for ((s0, e0) in speech) {
            val s = (s0 - PAD_MS).coerceAtLeast(0L)
            val e = (e0 + PAD_MS).coerceAtMost(totalMs)
            val last = merged.lastOrNull()
            if (last != null && s - last.second <= JOIN_GAP_MS && e - last.first <= MAX_CHUNK_MS) {
                merged[merged.size - 1] = last.first to e
            } else if (last != null && last.second - last.first < MIN_CHUNK_MS && s - last.second <= JOIN_GAP_MS * 2 && e - last.first <= MAX_CHUNK_MS) {
                merged[merged.size - 1] = last.first to e
            } else merged += s to e
        }
        // 3) слишком длинные куски (речь без пауз) режем по месту самой тихой 100-мс клетки у границы
        val out = ArrayList<Pair<Long, Long>>()
        for ((s, e) in merged) splitLong(pcm, s, e, out)
        return out
    }

    /** Речевые участки через Silero VAD из sherpa-onnx (корректно работает с моделью v5). null — отмена. */
    private fun runVad(pcm: ShortArray, cancelToken: Long): List<Pair<Long, Long>>? {
        val vadFile = File(context.filesDir, SileroVAD.MODEL_NAME)
        if (!vadFile.isFile) return listOf(0L to pcm.size * 1000L / SR)   // без VAD — режем по энергии в splitLong
        val vad = Vad(config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadFile.absolutePath, threshold = 0.35f,
                minSilenceDuration = 0.4f, minSpeechDuration = 0.25f, windowSize = 512,
                maxSpeechDuration = MAX_CHUNK_MS / 1000f - 2f),
            sampleRate = SR, numThreads = 1))
        val out = ArrayList<Pair<Long, Long>>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                val s = seg.start * 1000L / SR
                out += s to (s + seg.samples.size * 1000L / SR)
                vad.pop()
            }
        }
        try {
            // Строго окнами по 512 отсчётов: при подаче большими блоками sherpa-VAD сдвигает начало речи
            // (r004: 1566 мс вместо 126 мс) и не режет длинную речь — первые слова реплики терялись.
            val win = 512
            val buf = FloatArray(win)
            var pos = 0
            while (pos + win <= pcm.size) {
                if (pos % (win * 100) == 0 && isCancelled(cancelToken)) return null
                for (i in 0 until win) buf[i] = pcm[pos + i] / 32768f
                vad.acceptWaveform(buf)
                if (!vad.empty()) drain()
                pos += win
            }
            vad.flush(); drain()
        } finally { vad.release() }
        return out
    }

    private fun splitLong(pcm: ShortArray, s: Long, e: Long, out: MutableList<Pair<Long, Long>>) {
        var start = s
        while (e - start > MAX_CHUNK_MS) {
            // ищем минимум энергии в окне [start+MAX-4000, start+MAX]
            val winFrom = start + MAX_CHUNK_MS - 4_000L
            var bestMs = start + MAX_CHUNK_MS
            var bestE = Double.MAX_VALUE
            var t = winFrom
            while (t < start + MAX_CHUNK_MS) {
                val a = (t * SR / 1000).toInt().coerceIn(0, pcm.size)
                val b = ((t + 100) * SR / 1000).toInt().coerceIn(a, pcm.size)
                var sum = 0.0
                for (i in a until b) sum += pcm[i].toDouble() * pcm[i]
                val en = if (b > a) sum / (b - a) else 0.0
                if (en < bestE) { bestE = en; bestMs = t + 50 }
                t += 100
            }
            out += start to bestMs
            start = bestMs
        }
        if (e > start) out += start to e
    }

    private class CancellationException : RuntimeException()

    private fun isCancelled(token: Long) = token != 0L && token in cancelled

    override fun detectLanguage(pcm: ShortArray): String? = AppPrefs.LANG_RU

    override fun abort(cancelToken: Long) { if (cancelToken != 0L) cancelled += cancelToken }

    override fun release() {
        if (isReady) {
            isReady = false
            recognizer?.release()
            recognizer = null
        }
    }
}
