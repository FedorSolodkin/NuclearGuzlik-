package com.example.voicemind

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Потокобезопасный буфер PCM16. capacity в сэмплах.
class AudioBuffer(private val capacity: Int) {

    private val buf = ShortArray(capacity)
    private var writePos = 0
    private var count = 0
    private val lock = ReentrantLock()

    fun push(samples: ShortArray) = lock.withLock {
        for (s in samples) {
            buf[writePos % capacity] = s
            writePos++
            if (count < capacity) count++
        }
    }

    val size: Int get() = lock.withLock { count }

    // Извлечь все накопленные сэмплы, буфер очищается
    fun drainAll(): ShortArray = lock.withLock {
        val result = toArray()
        writePos = 0
        count = 0
        result
    }

    // Извлечь все, кроме последних keepSamples (они остаются как перекрытие)
    fun drainKeepOverlap(keepSamples: Int): ShortArray = lock.withLock {
        val keep = minOf(keepSamples, count)
        val drainCount = count - keep
        if (drainCount <= 0) return@withLock ShortArray(0)

        val all = toArray()
        val drained = all.copyOf(drainCount)
        val remaining = all.copyOfRange(drainCount, all.size)

        // Перезаписываем буфер остатком
        writePos = 0
        count = 0
        for (s in remaining) {
            buf[writePos % capacity] = s
            writePos++
            count++
        }
        drained
    }

    fun clear() = lock.withLock { writePos = 0; count = 0 }

    private fun toArray(): ShortArray {
        val result = ShortArray(count)
        val start = if (count < capacity) 0 else writePos % capacity
        for (i in 0 until count) {
            result[i] = buf[(start + i) % capacity]
        }
        return result
    }
}
