package com.example.guzlikvoice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Декодирует аудиофайл (m4a/AAC, mp3, ogg/opus, wav, …) в 16 кГц моно PCM16 —
 * ровно то, что нужно Whisper. Раньше отдавался звук в исходной частоте и с
 * исходным числом каналов: для записей самого приложения (16 кГц моно) это
 * совпадало, а файлы извне (голосовые Telegram 48 кГц, стерео mp3) распознавались
 * бы как ускоренная/искажённая речь. Преобразование — потоковое, файл целиком в
 * исходном формате в памяти не держим.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TIMEOUT_US = 1_000L

    /** Весь файл в один массив (разделение по голосам, экспорт). Буфер сразу под длительность файла. */
    fun decode(file: File): ShortArray {
        var result = ShortArray(0)
        decodeInto(file) { format ->
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            ShortArrayBuilder(initial = (durUs / 1_000_000L * 16_000L + 16_000L).coerceIn(16_000L, Int.MAX_VALUE.toLong()).toInt())
        }?.let { result = it.toArray() }
        return result
    }

    /**
     * Потоково: кадры по [frameSamples] сэмплов 16 кГц моно уходят в [onFrame] по мере
     * декодирования, весь файл в памяти не собирается. Возвращает число сэмплов (0 — не вышло).
     */
    fun decodeStreaming(file: File, frameSamples: Int = 1_600, onFrame: (ShortArray) -> Unit): Long {
        val builder = decodeInto(file) { ShortArrayBuilder(sink = onFrame, sinkChunk = frameSamples) } ?: return 0L
        builder.flush()
        return builder.total
    }

    /** Decode once to disk; the diarizer only loads small windows, not the entire recording. */
    fun decodeToFile(file: File, cacheDir: File, isCancelled: () -> Boolean): FilePcm {
        val tmp = File.createTempFile("diarization-", ".pcm", cacheDir)
        try {
            val samples = tmp.outputStream().buffered().use { out ->
                decodeStreaming(file) { frame ->
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    val bytes = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(frame)
                    out.write(bytes.array())
                }
            }
            check(samples > 0) { "Не удалось декодировать аудио" }
            return FilePcm(tmp)
        } catch (e: Throwable) { tmp.delete(); throw e }
    }

    private fun decodeInto(file: File, makeBuilder: (MediaFormat) -> ShortArrayBuilder): ShortArrayBuilder? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val (trackIdx, format) = findAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIdx)
            val out = makeBuilder(format)
            decodeTrack(extractor, format, out)
            out
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed: ${e.message}", e)
            null
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return Pair(i, fmt)
        }
        return null
    }

    private fun decodeTrack(extractor: MediaExtractor, format: MediaFormat, out: ShortArrayBuilder) {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        var started = false

        var srcRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 16_000)
        var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        val resampler = PcmResampler16k()
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            while (!outputDone) {
                // Fill available input slots without waiting while decoded output
                // may be blocking the codec. Avoid waiting for an input slot
                // on every AAC packet before draining decoded output.
                var queuedInput = false
                var queued = 0
                while (!inputDone && queued < 8) {
                    val inIdx = codec.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val inBuf = checkNotNull(codec.getInputBuffer(inIdx))
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    queuedInput = true
                    queued++
                }

                // Wait only when there was no immediately available work. This
                // still yields to the decoder instead of busy-spinning.
                val outIdx = codec.dequeueOutputBuffer(bufInfo, if (queuedInput) 0 else TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Реальные параметры PCM знает только декодер (у AAC/Opus
                    // они могут отличаться от заявленных контейнером).
                    val f = codec.outputFormat
                    srcRate = f.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                    channels = f.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                } else if (outIdx >= 0) {
                    val outBuf: ByteBuffer? = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufInfo.size > 0) {
                        outBuf.position(bufInfo.offset)
                        outBuf.limit(bufInfo.offset + bufInfo.size)
                        val pcm = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = pcm.remaining() / channels
                        // Bulk copy crosses the direct/native buffer boundary once,
                        // instead of once per sample/channel (hundreds of millions
                        // of accesses for a long stereo interview).
                        val interleaved = ShortArray(frames * channels)
                        pcm.get(interleaved)
                        val mono = FloatArray(frames)
                        for (i in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += interleaved[i * channels + c]
                            mono[i] = sum / channels
                        }
                        resampler.process(mono, srcRate, out)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }

        Log.i(TAG, "Decoded ${out.total} samples (${out.total / 16000}s at 16kHz, src ${srcRate}Hz × $channels ch)")
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, def: Int): Int =
        if (containsKey(key)) getInteger(key) else def
}
