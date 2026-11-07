package com.example.guzlikvoice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Кодирует PCM-чанки (16-bit, 16kHz, mono) в AAC/M4A на лету.
 * Формат: AAC-LC 32kbps ~240KB/мин.
 */
class AudioRecordingSaver(
    private val outputFile: File,
    private val sampleRate: Int = 16_000
) {
    companion object {
        private const val TAG      = "AudioSaver"
        private const val MIME     = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val BIT_RATE = 32_000   // 32 kbps — достаточно для голоса
    }

    // @Volatile: capture thread reads codec/muxer without holding the object
    // monitor in a hot path; makes null-writes from release() visible immediately.
    @Volatile private var codec:       MediaCodec? = null
    @Volatile private var muxer:       MediaMuxer? = null
    private var trackIndex   = -1
    private var muxerStarted = false
    private var presentationUs = 0L
    private val bufInfo      = MediaCodec.BufferInfo()
    // Guard against feed() called after stop() (or double-stop). Once flipped,
    // feed becomes a no-op — protects against IllegalStateException from the
    // codec, which is already released.
    private val stopped = AtomicBoolean(false)

    fun start() {
        try {
            val fmt = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MIME).also {
                it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            muxer = MediaMuxer(outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Log.i(TAG, "Started → ${outputFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            release()
        }
    }

    /** Принять чанк PCM16-LE. Вызывать из потока записи. */
    @Synchronized fun feed(samples: ShortArray) {
        if (stopped.get()) return
        val c = codec ?: return
        // Конвертируем ShortArray → ByteBuffer LE
        val bytes = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        bytes.flip()

        // Отдаём кодировщику
        val idx = c.dequeueInputBuffer(5_000)
        if (idx >= 0) {
            val buf = c.getInputBuffer(idx) ?: run {
                c.queueInputBuffer(idx, 0, 0, presentationUs, 0)
                return
            }
            buf.clear()
            buf.put(bytes)
            c.queueInputBuffer(idx, 0, bytes.limit(), presentationUs, 0)
            presentationUs += samples.size * 1_000_000L / sampleRate
        }
        drainEncoder(endOfStream = false)
    }

    /** Завершить запись, сохранить файл. Возвращает файл или null при ошибке. */
    @Synchronized fun stop(): File? {
        if (!stopped.compareAndSet(false, true)) return null
        val c = codec ?: return null
        return try {
            // Сигнал конца потока
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0)
                c.queueInputBuffer(idx, 0, 0, presentationUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(endOfStream = true)
            if (muxerStarted) muxer?.stop()
            Log.i(TAG, "Saved ${outputFile.length() / 1024} KB → ${outputFile.name}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "stop failed: ${e.message}")
            null
        } finally {
            release()
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufInfo, if (endOfStream) 10_000L else 0L)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex   = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (muxerStarted && !isConfig && bufInfo.size > 0) {
                            outBuf.position(bufInfo.offset)
                            outBuf.limit(bufInfo.offset + bufInfo.size)
                            m.writeSampleData(trackIndex, outBuf, bufInfo)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return
            }
        }
    }

    private fun release() {
        try { codec?.stop()    } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        codec = null
        muxer = null
    }
}
