package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PcmNormalizerTest {
    private fun tone(amp: Double, n: Int = 16000 * 3) = ShortArray(n) { (amp * sin(2 * PI * 220 * it / 16000)).toInt().toShort() }
    @Test fun quietChunkIsAmplified() {
        val q = tone(60.0)
        val out = PcmNormalizer.normalize(q)
        assertTrue(out.maxOf { it.toInt() } > q.maxOf { it.toInt() } * 10)
    }
    @Test fun loudChunkUntouched() {
        val l = tone(12000.0)
        assertSame(l, PcmNormalizer.normalize(l))
    }
    @Test fun silenceUntouched() {
        val z = ShortArray(48000)
        assertSame(z, PcmNormalizer.normalize(z))
    }
    @Test fun neverClips() {
        val q = tone(200.0)
        assertTrue(PcmNormalizer.normalize(q).maxOf { it.toInt() } <= 30001)
    }
}
