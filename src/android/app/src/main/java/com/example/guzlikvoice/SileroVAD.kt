package com.example.guzlikvoice

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

class SileroVAD(private val context: Context) {

    companion object {
        const val MODEL_NAME = "silero_vad.onnx"
        private const val TAG = "SileroVAD"
        private const val CHUNK  = 512          // 32ms at 16kHz
        private const val SR     = 16_000L
        private const val SPEECH_THRESH  = 0.5f
        private const val MIN_SPEECH_MS  = 250
        private const val MIN_SILENCE_MS = 400
    }

    private val env = OrtEnvironment.getEnvironment()
    @Volatile private var session: OrtSession? = null

    // v5 model uses separate h/c tensors (2,1,64) each; older uses single state (2,1,128)
    private var useHC = false

    val isLoaded get() = session != null

    fun load(): Boolean {
        val f = File(context.filesDir, MODEL_NAME)
        if (!f.exists() || f.length() < 100_000L) return false
        return try {
            session = env.createSession(f.absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
            val inputNames = session!!.inputInfo.keys
            useHC = "h" in inputNames && "c" in inputNames
            Log.i(TAG, "Loaded (${f.length() / 1024} KB) inputs=${inputNames} useHC=$useHC")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}"); false
        }
    }

    /** Returns list of (startMs, endMs) speech segments. Falls back to energy if not loaded. */
    fun detectSpeech(pcm: ShortArray): List<Pair<Long, Long>> = detectSpeech(ArrayPcm(pcm))

    fun detectSpeech(pcm: PcmAudio, checkCancelled: () -> Unit = {}): List<Pair<Long, Long>> {
        val sess = session ?: return energyFallback(pcm, checkCancelled)

        var h = FloatArray(2 * 1 * 64)   // v5
        var c = FloatArray(2 * 1 * 64)   // v5
        var state = FloatArray(2 * 1 * 128) // v1/v2

        val probs = mutableListOf<Float>()
        var pos = 0
        while (pos + CHUNK <= pcm.size) {
            checkCancelled()
            val slice = pcm.copyOfRange(pos, pos + CHUNK)
            val chunk = FloatArray(CHUNK) { slice[it] / 32768f }
            val result = if (useHC) runChunkHC(sess, chunk, h, c)
                         else       runChunkState(sess, chunk, state)
            if (result == null) break
            probs += result.first
            if (useHC) { h = result.second; c = result.third }
            else        state = result.second
            pos += CHUNK
        }
        Log.i(TAG, "Processed ${probs.size} chunks, speech>0.5: ${probs.count { it >= SPEECH_THRESH }}, max=${probs.maxOrNull()?.let { "%.2f".format(it) } ?: "n/a"}")
        return probsToSegments(probs)
    }

    // v5: inputs h(2,1,64) and c(2,1,64), outputs hn and cn
    private fun runChunkHC(sess: OrtSession, chunk: FloatArray, h: FloatArray, c: FloatArray): Triple<Float, FloatArray, FloatArray>? {
        var inputT: OnnxTensor? = null
        var hT: OnnxTensor? = null
        var cT: OnnxTensor? = null
        var srT: OnnxTensor? = null
        return try {
            val srBuf = LongBuffer.wrap(longArrayOf(SR))
            inputT = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1L, CHUNK.toLong()))
            hT     = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2L, 1L, 64L))
            cT     = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2L, 1L, 64L))
            srT    = OnnxTensor.createTensor(env, srBuf, longArrayOf(1L))

            sess.run(mapOf("input" to inputT, "sr" to srT, "h" to hT, "c" to cT)).use { out ->
                val prob = (out[0].value as Array<*>)[0].let { (it as FloatArray)[0] }
                val hn   = flattenF3(out[1].value)
                val cn   = flattenF3(out[2].value)
                Triple(prob, hn, cn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "runChunkHC: ${e.message}"); null
        } finally {
            try { inputT?.close() } catch (_: Exception) {}
            try { hT?.close() }     catch (_: Exception) {}
            try { cT?.close() }     catch (_: Exception) {}
            try { srT?.close() }    catch (_: Exception) {}
        }
    }

    // v1/v2: single state(2,1,128), output stateN(2,1,128)
    private fun runChunkState(sess: OrtSession, chunk: FloatArray, state: FloatArray): Triple<Float, FloatArray, FloatArray>? {
        var inputT: OnnxTensor? = null
        var stateT: OnnxTensor? = null
        var srT: OnnxTensor? = null
        return try {
            val srBuf = LongBuffer.wrap(longArrayOf(SR))
            inputT = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1L, CHUNK.toLong()))
            stateT = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2L, 1L, 128L))
            srT    = OnnxTensor.createTensor(env, srBuf, longArrayOf(1L))

            sess.run(mapOf("input" to inputT, "sr" to srT, "state" to stateT)).use { out ->
                val prob     = (out[0].value as Array<*>)[0].let { (it as FloatArray)[0] }
                val newState = flattenF3(out[1].value)
                Triple(prob, newState, FloatArray(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "runChunkState: ${e.message}"); null
        } finally {
            try { inputT?.close() } catch (_: Exception) {}
            try { stateT?.close() } catch (_: Exception) {}
            try { srT?.close() }    catch (_: Exception) {}
        }
    }

    private fun flattenF3(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> value.flatMap { row ->
                when (row) {
                    is FloatArray -> row.toList()
                    is Array<*>   -> row.flatMap { (it as FloatArray).toList() }
                    else -> emptyList()
                }
            }.toFloatArray()
            else -> FloatArray(0)
        }
    }

    private fun probsToSegments(probs: List<Float>): List<Pair<Long, Long>> {
        val msPerChunk  = CHUNK * 1000L / SR
        val minSpeechChunks  = (MIN_SPEECH_MS  / msPerChunk).toInt().coerceAtLeast(1)
        val minSilenceChunks = (MIN_SILENCE_MS / msPerChunk).toInt().coerceAtLeast(1)

        val segments = mutableListOf<Pair<Long, Long>>()
        var inSpeech = false
        var speechStart = 0
        var silenceCount = 0

        for ((i, prob) in probs.withIndex()) {
            if (prob >= SPEECH_THRESH) {
                silenceCount = 0
                if (!inSpeech) { inSpeech = true; speechStart = i }
            } else {
                silenceCount++
                if (inSpeech && silenceCount >= minSilenceChunks) {
                    val dur = (i - speechStart - silenceCount + 1)
                    if (dur >= minSpeechChunks) {
                        segments += Pair(speechStart * msPerChunk, i * msPerChunk)
                    }
                    inSpeech = false; silenceCount = 0
                }
            }
        }
        if (inSpeech) {
            val dur = probs.size - speechStart
            if (dur >= minSpeechChunks)
                segments += Pair(speechStart * msPerChunk, probs.size * msPerChunk)
        }
        return segments
    }

    private fun energyFallback(pcm: PcmAudio, checkCancelled: () -> Unit): List<Pair<Long, Long>> {
        // Simple energy-based VAD: 150 RMS threshold
        val segments = mutableListOf<Pair<Long, Long>>()
        val chunkMs = CHUNK * 1000L / SR
        var inSpeech = false; var start = 0L
        var pos = 0
        while (pos + CHUNK <= pcm.size) {
            checkCancelled()
            var sum = 0.0
            for (i in pos until pos + CHUNK) sum += pcm[i].toDouble() * pcm[i]
            val rms = kotlin.math.sqrt(sum / CHUNK).toFloat()
            val ms  = pos * 1000L / SR
            if (rms > 150f) {
                if (!inSpeech) { inSpeech = true; start = ms }
            } else {
                if (inSpeech && ms - start > MIN_SPEECH_MS) segments += Pair(start, ms)
                inSpeech = false
            }
            pos += CHUNK
        }
        if (inSpeech) segments += Pair(start, pcm.size * 1000L / SR)
        return segments
    }

    fun release() { session?.close(); session = null }
}
