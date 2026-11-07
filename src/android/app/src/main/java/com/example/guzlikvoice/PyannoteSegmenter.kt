package com.example.guzlikvoice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * pyannote/segmentation-3.0 wrapper — ONNX модель, скачанная как ~5.7 МБ.
 * Выдаёт per-frame powerset-классификацию до 3 спикеров одновременно (7 классов):
 *   0=silence, 1=spk1, 2=spk2, 3=spk3, 4=spk1+spk2, 5=spk1+spk3, 6=spk2+spk3.
 *
 * Ключевая разница с нашим Silero+sliding-window: сеть сама решает, где границы
 * реплик и сколько одновременных спикеров — не нужно фиксированное окно 1.5с.
 *
 * Модель тренирована на 10-секундных чанках 16 кГц. Внутри неё SincNet-фронтенд +
 * PyanNet, 589 выходных фреймов на чанк (~17 мс/фрейм). Кросс-чанк идентичность
 * спикеров ЭТА модель не даёт — эмбеддинги нужно кластеризовать отдельно
 * (см. SpeakerDiarizer Level 4).
 */
class PyannoteSegmenter(private val context: Context) {

    companion object {
        const val MODEL_NAME = "pyannote_segmentation.onnx"
        private const val TAG = "PyannoteSegmenter"

        const val SAMPLE_RATE  = 16_000
        const val CHUNK_SEC    = 10
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SEC   // 160_000
        const val HOP_SEC      = 5
        const val HOP_SAMPLES  = SAMPLE_RATE * HOP_SEC       // 80_000
        const val NUM_CLASSES  = 7                            // powerset(3 speakers, max 2 overlap)

        // Frame duration зависит от модели; посчитаем из выходной формы при первом прогоне.
        private const val DEFAULT_FRAMES_PER_CHUNK = 589
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    @Volatile private var session: OrtSession? = null

    private var inputName: String = "input_values"
    private var inputRank: Int    = 3      // (1, 1, samples) vs (1, samples)
    private var framesPerChunk: Int = DEFAULT_FRAMES_PER_CHUNK

    val isLoaded get() = session != null
    val msPerFrame: Float
        get() = (CHUNK_SEC * 1000f) / framesPerChunk    // ~16.98 мс при 589 фреймах

    fun load(allowSpinning: Boolean = DiarizationRuntimePolicy.spinning(context), threads: Int = DiarizationRuntimePolicy.threads(context)): Boolean {
        require(threads in 1..4)
        val model = findModel() ?: run {
            Log.i(TAG, "No pyannote segmentation model — will use Silero+sliding-window fallback")
            return false
        }
        return try {
            session = OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(threads)
                opts.addConfigEntry("session.intra_op.allow_spinning", if (allowSpinning) "1" else "0")
                env.createSession(model.absolutePath, opts)
            }
            val info = session!!.inputInfo
            inputName = info.keys.first()
            val shape = (info[inputName]?.info as? TensorInfo)?.shape
            inputRank = shape?.size ?: 3
            Log.i(TAG, "Loaded (${model.length() / 1024} KB) input=$inputName rank=$inputRank shape=${shape?.toList()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            false
        }
    }

    fun release() {
        session?.close()
        session = null
    }

    /**
     * Прогоняет ОДИН 10-секундный чанк через модель.
     * Возвращает Array[framesPerChunk][7] с raw логитами (softmax не нужен для argmax).
     * chunk должен содержать ровно CHUNK_SAMPLES семплов (при необходимости — паддинг тишиной).
     */
    fun segmentChunk(chunk: FloatArray): Array<FloatArray>? {
        val sess = session ?: return null
        require(chunk.size == CHUNK_SAMPLES) { "Chunk must be $CHUNK_SAMPLES samples, got ${chunk.size}" }

        return try {
            val buf = FloatBuffer.wrap(chunk)
            val shape = when (inputRank) {
                2 -> longArrayOf(1L, CHUNK_SAMPLES.toLong())
                else -> longArrayOf(1L, 1L, CHUNK_SAMPLES.toLong())
            }
            OnnxTensor.createTensor(env, buf, shape).use { input ->
                sess.run(mapOf(inputName to input)).use { out ->
                    parseLogits(out[0].value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "segmentChunk failed: ${e.message}", e)
            null
        }
    }

    /** Ожидаем (1, T, 7). Первый прогон обновит framesPerChunk если T ≠ default. */
    private fun parseLogits(value: Any?): Array<FloatArray>? {
        val outer = value as? Array<*> ?: return null
        val batch0 = outer[0] as? Array<*> ?: return null
        @Suppress("UNCHECKED_CAST")
        val frames = batch0 as Array<FloatArray>
        if (frames.isNotEmpty() && frames.size != framesPerChunk) {
            Log.i(TAG, "framesPerChunk adjusted: $framesPerChunk → ${frames.size}")
            framesPerChunk = frames.size
        }
        return frames
    }

    // ── Model search ──────────────────────────────────────────────────────────

    private fun findModel(): File? {
        val dest = File(context.filesDir, MODEL_NAME)
        if (dest.exists() && dest.length() > 1_000_000L) return dest
        val ext = context.getExternalFilesDir(null)
        if (ext != null) {
            val f = File(ext, MODEL_NAME)
            if (f.exists() && f.length() > 1_000_000L) return f
        }
        return null
    }
}
