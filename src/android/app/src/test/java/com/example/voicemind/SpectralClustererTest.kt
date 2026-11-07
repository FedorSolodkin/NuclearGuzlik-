package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class SpectralClustererTest {
    @Test fun eigenvectorsSatisfyOriginalMatrixAtRealisticSize() {
        val n = 64
        val rng = Random(73)
        val a = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i until n) {
            val v = rng.nextFloat() * 2 - 1
            a[i][j] = v; a[j][i] = v
        }
        val (values, vectors) = SpectralClusterer.jacobiEigen(a, n)
        for (k in 0 until n) for (i in 0 until n) {
            val av = (0 until n).sumOf { j -> a[i][j].toDouble() * vectors[j][k] }
            assertEquals("residual ($i,$k)", av, values[k].toDouble() * vectors[i][k], 1e-5)
        }
        for (i in 0 until n) for (j in 0 until n) {
            val dot = (0 until n).sumOf { r -> vectors[r][i].toDouble() * vectors[r][j] }
            assertEquals(if (i == j) 1.0 else 0.0, dot, 1e-5)
        }
    }

    @Test fun separatesThreeSpeakersAtSamplingLimit() {
        val e = List(255) { i -> FloatArray(3) { if (it == i % 3) 1f else 0f } }
        val got = SpectralClusterer.cluster(e, fixedK = 3)
        assertEquals(3, got.toSet().size)
        got.indices.forEach { assertEquals(got[it % 3], got[it]) }
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun decompositionCanBeCancelled() {
        SpectralClusterer.jacobiEigen(Array(20) { FloatArray(20) }, 20) {
            throw java.util.concurrent.CancellationException()
        }
    }
}
