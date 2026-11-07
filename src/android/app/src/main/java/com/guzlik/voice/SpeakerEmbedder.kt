package com.guzlik.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Speaker embedding model wrapper — supports two ONNX model types:
 *
 *   1. WeSpeaker / 3D-Speaker  (preferred)
 *      Input  "feats": (1, T, 80)  — 80-dim log-mel FBANK features
 *      Output          (1, 256)    — L2-normalized speaker embedding
 *
 *   2. ECAPA-TDNN legacy
 *      Input  "wav":   (1, T)      — raw float32 PCM normalized to [-1, 1]
 *      Output          (1, 1, 192) — L2-normalized speaker embedding
 *
 * Model type is auto-detected from the ONNX graph at load time.
 * Place speaker_model.onnx in filesDir (auto-downloaded in SetupActivity).
 */
class SpeakerEmbedder(private val context: Context) {

    companion object {
        private const val TAG        = "SpeakerEmbedder"
        const  val MODEL_NAME        = "speaker_model.onnx"
        private const val MIN_SAMPLES = 8_000  // 0.5 s
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    // @Volatile: release() из main может занулить session пока embed() из IO-потока
    // держит native handle. Локальный snapshot в embed() гарантирует use-before-close.
    @Volatile private var session: OrtSession? = null

    private var inputName: String  = "feats"
    private var useFbank:  Boolean = true   // WeSpeaker format by default

    val isLoaded get() = session != null

    fun load(allowSpinning: Boolean = DiarizationRuntimePolicy.spinning(context), threads: Int = DiarizationRuntimePolicy.threads(context)): Boolean {
        require(threads in 1..4)
        val model = findModel()
        if (model == null) {
            Log.i(TAG, "No speaker model — diarization uses gap heuristic")
            return false
        }
        return try {
            session = OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(threads)
                opts.addConfigEntry("session.intra_op.allow_spinning", if (allowSpinning) "1" else "0")
                env.createSession(model.absolutePath, opts)
            }

            // Auto-detect model type from input tensor rank
            val inputInfo = session!!.inputInfo
            inputName = inputInfo.keys.first()
            val shape = (inputInfo[inputName]?.info as? TensorInfo)?.shape
            // WeSpeaker: 3 dims (batch, frames, 80) — use FBANK
            // ECAPA-TDNN: 2 dims (batch, samples) — use raw PCM
            useFbank = shape != null && shape.size == 3

            Log.i(TAG, "Speaker model loaded (${model.length() / 1_024} KB) " +
                       "type=${if (useFbank) "WeSpeaker/FBANK" else "PCM"} " +
                       "input=$inputName shape=${shape?.toList()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}")
            false
        }
    }

    fun embed(pcm: ShortArray): FloatArray? {
        val sess = session ?: run { Log.w(TAG, "embed: session null"); return null }
        if (pcm.size < MIN_SAMPLES) { Log.w(TAG, "embed: too short ${pcm.size} < $MIN_SAMPLES"); return null }
        return try {
            val result = if (useFbank) embedWithFbank(sess, pcm) else embedRawPcm(sess, pcm)
            if (result == null) Log.w(TAG, "embed: returned null (useFbank=$useFbank, size=${pcm.size})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            null
        }
    }

    // ── WeSpeaker path ────────────────────────────────────────────────────────

    private fun embedWithFbank(sess: OrtSession, pcm: ShortArray): FloatArray? {
        val features = FbankExtractor.extract(pcm)
        if (features.isEmpty()) return null

        val nFrames = features.size
        val buf = FloatBuffer.allocate(nFrames * FbankExtractor.NUM_FILTERS)
        for (frame in features) for (v in frame) buf.put(v)
        buf.rewind()

        val tensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1L, nFrames.toLong(), FbankExtractor.NUM_FILTERS.toLong())
        )
        return tensor.use {
            sess.run(mapOf(inputName to tensor)).use { out ->
                extractEmbedding(out[0].value)
            }
        }
    }

    // ── Legacy ECAPA-TDNN path ────────────────────────────────────────────────

    private fun embedRawPcm(sess: OrtSession, pcm: ShortArray): FloatArray? {
        val buf = FloatBuffer.allocate(pcm.size)
        for (s in pcm) buf.put(s / 32_768.0f)
        buf.rewind()

        val tensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1L, pcm.size.toLong())
        )
        return tensor.use {
            sess.run(mapOf(inputName to tensor)).use { out ->
                extractEmbedding(out[0].value)
            }
        }
    }

    // ── Output parsing ────────────────────────────────────────────────────────

    private fun extractEmbedding(value: Any?): FloatArray? {
        val raw: FloatArray = when (value) {
            is FloatArray -> value
            is Array<*>  -> {
                // Unwrap (1, 1, D) or (1, D)
                val first = value[0]
                val inner = if (first is Array<*>) first[0] else first
                inner as? FloatArray ?: run {
                    Log.e(TAG, "Unexpected embedding type: ${inner?.javaClass}")
                    return null
                }
            }
            else -> { Log.e(TAG, "Unexpected output type: ${value?.javaClass}"); return null }
        }
        // Битый вход/модель не должны отравлять кластеризацию: один NaN-вектор
        // делает все расстояния NaN, и голоса перестают сливаться.
        if (raw.isEmpty() || raw.any { it.isNaN() || it.isInfinite() }) {
            Log.w(TAG, "embedding rejected: NaN/Inf in model output")
            return null
        }
        val squaredNorm = raw.sumOf { it.toDouble()*it }
        if (squaredNorm <= 1e-16 || squaredNorm > Float.MAX_VALUE.toDouble()) {
            Log.w(TAG, "embedding rejected: invalid norm")
            return null
        }
        val emb = raw.copyOf()
        normalizeL2(emb)
        return emb
    }

    private fun normalizeL2(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
    }

    fun release() {
        session?.close()
        session = null
    }

    // ── Model search ──────────────────────────────────────────────────────────

    private fun findModel(): File? {
        val dest = File(context.filesDir, MODEL_NAME)
        if (dest.exists() && dest.length() > 10_000) return dest

        val extDir = context.getExternalFilesDir(null)
        if (extDir != null) {
            val f = File(extDir, MODEL_NAME)
            if (f.exists() && f.length() > 10_000) return f
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val fromDl = File(downloads, MODEL_NAME)
        if (fromDl.exists() && fromDl.length() > 10_000) {
            Log.i(TAG, "Copying model from Downloads...")
            if (atomicCopy({ fromDl.inputStream() }, dest)) return dest
        }

        return try {
            context.assets.open(MODEL_NAME).use {}
            if (!dest.exists()) {
                if (!atomicCopy({ context.assets.open(MODEL_NAME) }, dest)) return null
            }
            dest
        } catch (_: Exception) { null }
    }

    /** Copy to `dest.tmp`, then atomically rename. Prevents partial-file left after crash. */
    private fun atomicCopy(openSource: () -> java.io.InputStream, dest: File): Boolean {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        return try {
            openSource().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (tmp.renameTo(dest)) true
            else { tmp.delete(); false }
        } catch (e: Exception) {
            Log.e(TAG, "atomicCopy failed: ${e.message}")
            tmp.delete()
            false
        }
    }
}
