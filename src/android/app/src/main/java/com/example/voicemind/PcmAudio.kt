package com.example.voicemind

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface PcmAudio {
    val size: Int
    operator fun get(index: Int): Short
    fun isEmpty() = size == 0
    fun copyOfRange(from: Int, to: Int): ShortArray
}

class ArrayPcm(private val samples: ShortArray) : PcmAudio {
    override val size get() = samples.size
    override fun get(index: Int) = samples[index]
    override fun copyOfRange(from: Int, to: Int) = samples.copyOfRange(from, to)
}

class FilePcm(private val file: File) : PcmAudio, Closeable {
    private val input = RandomAccessFile(file, "r")
    private val channel = input.channel
    override val size: Int = (channel.size() / 2).also { require(it <= Int.MAX_VALUE) }.toInt()
    private class Cache { var start = -1; var samples = ShortArray(0) }
    private val cache = object : ThreadLocal<Cache>() { override fun initialValue() = Cache() }
    override fun get(index: Int): Short {
        require(index in 0 until size)
        val c = cache.get()!!
        if (index < c.start || index >= c.start + c.samples.size) {
            c.start = index / 8192 * 8192
            c.samples = copyOfRange(c.start, minOf(size, c.start + 8192))
        }
        return c.samples[index - c.start]
    }
    override fun copyOfRange(from: Int, to: Int): ShortArray {
        require(from >= 0 && to >= from && to <= size)
        val bytes = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
        var pos = from * 2L
        while (bytes.hasRemaining()) {
            val n = channel.read(bytes, pos)
            check(n > 0) { "PCM file is truncated" }
            pos += n
        }
        bytes.flip()
        return ShortArray(to - from).also { bytes.asShortBuffer().get(it) }
    }
    override fun close() { input.close(); cache.remove(); file.delete() }
}
