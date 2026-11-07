package com.example.voicemind

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** PCM WAV, 32 KB/s. Periodic header checkpoints keep recordings recoverable after process death. */
class WearableWavWriter(val file: File) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var bytes = 0L
    private var checkpoint = 0L
    private var closed = false
    init {
        try { output.setLength(0); header(output, 0); output.seek(44) }
        catch (e: Exception) { output.close(); throw e }
    }
    fun write(pcm: ByteArray) {
        check(!closed) { "WAV закрыт" }
        require(pcm.size % 2 == 0)
        check(bytes + pcm.size <= 16000L * 2 * 60 * 60 * 8) { "Достигнут предел WAV: 8 часов" }
        output.write(pcm); bytes += pcm.size
        if (bytes - checkpoint >= 32000) {
            header(output, bytes); output.seek(44 + bytes); output.fd.sync(); checkpoint = bytes
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        try { header(output, bytes); output.fd.sync() } finally { output.close() }
    }
    companion object {
        fun recover(file: File) {
            require(file.length() >= 44)
            RandomAccessFile(file, "rw").use {
                val bytes = (it.length() - 44) / 2 * 2
                it.setLength(bytes + 44); header(it, bytes); it.fd.sync()
            }
        }
        private fun header(out: RandomAccessFile, bytes: Long) {
            fun le(value: Long, count: Int) { repeat(count) { out.write((value ushr (8 * it)).toInt() and 255) } }
            out.seek(0); out.writeBytes("RIFF"); le(bytes + 36, 4); out.writeBytes("WAVEfmt ")
            le(16, 4); le(1, 2); le(1, 2); le(16000, 4); le(32000, 4); le(2, 2); le(16, 2)
            out.writeBytes("data"); le(bytes, 4)
        }
    }
}
