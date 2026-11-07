package com.example.voicemind

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Записывает аудио с микрофона телефона и отдаёт чанки PCM16 LE.
 *
 * Параметры совпадают с форматом, который ожидает Whisper:
 *   - sample rate: 16 000 Гц
 *   - моно
 *   - PCM 16-bit signed LE
 *
 * Использование:
 *   val mic = PhoneMicRecorder(context,
 *       onAudioChunk = { pcm -> session.pushAudio(pcm) },
 *       onError      = { msg -> showError(msg) })
 *   mic.start()
 *   ...
 *   mic.stop()
 */
class PhoneMicRecorder(
    private val sampleRate: Int = 16_000,
    private val onAudioChunk: (ShortArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "PhoneMicRecorder"
        private const val CHUNK_MS = 100   // размер чанка ~100 мс
    }

    private val chunkSize = sampleRate * CHUNK_MS / 1_000   // = 1600 сэмплов
    private val minBuf     = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    // Буфер AudioRecord — не менее 4× чанка, чтобы не было underrun
    private val bufBytes = maxOf(minBuf, chunkSize * Short.SIZE_BYTES * 4)

    @Volatile private var running = false
    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    /** Запустить запись. Разрешение RECORD_AUDIO должно быть выдано до вызова. */
    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "Starting — sampleRate=$sampleRate chunkSize=$chunkSize bufBytes=$bufBytes")

        try {
            @Suppress("MissingPermission")
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                running = false
                onError("AudioRecord не инициализирован")
                ar.release()
                return
            }
            audioRecord = ar
            ar.startRecording()
            Log.i(TAG, "AudioRecord started")

            workerThread = thread(name = "phone-mic-worker", isDaemon = true) {
                readLoop(ar)
            }
        } catch (e: SecurityException) {
            running = false
            onError("Нет разрешения на микрофон (RECORD_AUDIO)")
        } catch (e: Exception) {
            running = false
            onError("Ошибка запуска микрофона: ${e.message}")
        }
    }

    /** Остановить запись и освободить ресурсы. Безопасно вызывать несколько раз. */
    fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "Stopping")
        workerThread?.interrupt()
        workerThread = null
        releaseAudioRecord()
    }

    // ─── private ──────────────────────────────────────────────────────────────

    private fun readLoop(ar: AudioRecord) {
        val buf = ShortArray(chunkSize)
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val read = ar.read(buf, 0, chunkSize)
                when {
                    read > 0 -> onAudioChunk(buf.copyOf(read))
                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        onError("AudioRecord: ERROR_INVALID_OPERATION"); break
                    }
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        onError("AudioRecord: ERROR_BAD_VALUE"); break
                    }
                    read < 0 -> {
                        onError("AudioRecord.read вернул $read"); break
                    }
                    // read == 0 — маловероятно, просто продолжаем
                }
            }
        } catch (e: Exception) {
            if (running) onError("Ошибка чтения микрофона: ${e.message}")
        } finally {
            releaseAudioRecord()
            Log.i(TAG, "readLoop exited")
        }
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() }   catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}
