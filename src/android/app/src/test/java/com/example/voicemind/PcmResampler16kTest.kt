package com.example.voicemind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmResampler16kTest {

    /** Синус freq Гц длительностью sec, порезанный на буферы разного размера. */
    private fun resample(srcRate: Int, freq: Double, sec: Double, chunk: Int): ShortArray {
        val n = (srcRate * sec).toInt()
        val src = FloatArray(n) { (10_000 * sin(2 * PI * freq * it / srcRate)).toFloat() }
        val r = PcmResampler16k()
        val out = ShortArrayBuilder(16)
        var i = 0
        while (i < n) {
            val len = minOf(chunk, n - i)
            r.process(src.copyOfRange(i, i + len), srcRate, out)
            i += len
        }
        return out.toArray()
    }

    private fun zeroCrossings(x: ShortArray): Int =
        (1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

    @Test fun downsample48kKeepsDurationAndPitch() {
        val out = resample(48_000, 440.0, 2.0, chunk = 1_021)
        assertEquals(32_000.0, out.size.toDouble(), 5.0)
        // 440 Гц за 2 с → ~1760 пересечений нуля
        assertEquals(1760.0, zeroCrossings(out).toDouble(), 20.0)
    }

    @Test fun downsample44k1() {
        val out = resample(44_100, 300.0, 3.0, chunk = 4_096)
        assertEquals(48_000.0, out.size.toDouble(), 5.0)
        assertEquals(1800.0, zeroCrossings(out).toDouble(), 20.0)
    }

    @Test fun upsample8k() {
        val out = resample(8_000, 200.0, 1.0, chunk = 333)
        assertEquals(16_000.0, out.size.toDouble(), 5.0)
        assertEquals(400.0, zeroCrossings(out).toDouble(), 10.0)
    }

    @Test fun passthrough16k() {
        val out = resample(16_000, 500.0, 1.0, chunk = 1_600)
        assertEquals(16_000, out.size)
        assertTrue(abs(out.maxOf { it.toInt() } - 10_000) < 50)
    }

    /** Потоковый режим (импорт файла) отдаёт ровно те же сэмплы, что накопительный, кусками фиксированного размера. */
    @Test fun `streaming builder yields same samples in fixed chunks`() {
        val src = FloatArray(48_000 * 3) { (8_000 * sin(2 * PI * 440.0 * it / 48_000)).toFloat() }
        val whole = ShortArrayBuilder()
        PcmResampler16k().process(src, 48_000, whole)

        val chunks = mutableListOf<ShortArray>()
        val stream = ShortArrayBuilder(sink = { chunks += it }, sinkChunk = 1_600)
        PcmResampler16k().process(src, 48_000, stream)
        stream.flush()

        assertEquals(whole.size.toLong(), stream.total)
        assertTrue(chunks.dropLast(1).all { it.size == 1_600 })
        val joined = ShortArray(chunks.sumOf { it.size }).also { out ->
            var p = 0
            for (c in chunks) { c.copyInto(out, p); p += c.size }
        }
        assertTrue(whole.toArray().contentEquals(joined))
    }
}
