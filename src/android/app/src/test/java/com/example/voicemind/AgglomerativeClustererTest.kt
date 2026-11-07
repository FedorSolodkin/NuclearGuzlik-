package com.example.voicemind

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class AgglomerativeClustererTest {

    private fun norm(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    /** Прежняя реализация (все пары на каждом шаге) — эталон для сравнения. */
    private fun reference(e: List<FloatArray>, threshold: Float, minC: Int, maxC: Int): IntArray {
        fun dist(a: FloatArray, b: FloatArray): Float {
            var d = 0f; for (i in a.indices) d += a[i] * b[i]; return 1f - d.coerceIn(-1f, 1f)
        }
        val members = MutableList(e.size) { mutableListOf(it) }
        val cents = MutableList(e.size) { e[it].copyOf() }
        while (members.size > minC) {
            var ba = -1; var bb = -1; var bd = Float.MAX_VALUE
            for (i in members.indices) for (j in i + 1 until members.size) {
                val d = dist(cents[i], cents[j]); if (d < bd) { bd = d; ba = i; bb = j }
            }
            if (members.size <= maxC && bd > threshold) break
            val combined = members[ba] + members[bb]
            cents[ba] = norm(FloatArray(cents[ba].size) { k -> combined.sumOf { e[it][k].toDouble() }.toFloat() })
            members[ba].addAll(members[bb]); members.removeAt(bb); cents.removeAt(bb)
        }
        val l = IntArray(e.size); members.forEachIndexed { c, m -> m.forEach { l[it] = c } }; return l
    }

    /** Разбиения совпадают с точностью до перенумерации кластеров. */
    private fun samePartition(a: IntArray, b: IntArray) {
        val map = HashMap<Int, Int>()
        for (i in a.indices) assertEquals("item $i", map.getOrPut(a[i]) { b[i] }, b[i])
        assertEquals(a.toSet().size, b.toSet().size)
    }

    private fun speakers(rnd: Random, k: Int, perSpeaker: Int, dim: Int = 64, noise: Float = 0.35f): List<FloatArray> {
        val centers = List(k) { norm(FloatArray(dim) { rnd.nextFloat() * 2 - 1 }) }
        return List(k * perSpeaker) { i ->
            val c = centers[i % k]
            norm(FloatArray(dim) { c[it] + (rnd.nextFloat() * 2 - 1) * noise })
        }
    }

    @Test fun matchesReferenceAuto() {
        val rnd = Random(1)
        repeat(5) {
            val e = speakers(rnd, k = 2 + it % 3, perSpeaker = 30)
            samePartition(reference(e, 0.72f, 1, 8), AgglomerativeClusterer.cluster(e))
        }
    }

    @Test fun fixedNumberOfSpeakers() {
        val rnd = Random(7)
        val e = speakers(rnd, k = 3, perSpeaker = 25)
        val labels = AgglomerativeClusterer.cluster(e, minClusters = 2, maxClusters = 2)
        assertEquals(2, labels.toSet().size)
        samePartition(reference(e, 0.72f, 2, 2), labels)
    }

    @Test fun separatesDistinctSpeakers() {
        val e = speakers(Random(3), k = 3, perSpeaker = 20, noise = 0.2f)
        val labels = AgglomerativeClusterer.cluster(e)
        assertEquals(3, labels.toSet().size)
        for (i in e.indices) assertEquals(labels[i % 3], labels[i])
    }

    @Test fun respectsRequestedCountEvenWithBrokenEmbeddings() {
        // Раньше NaN-эмбеддинги давали «61 говорящий» даже при выборе «2 человека».
        val e = List(61) { FloatArray(16) { Float.NaN } }
        assertEquals(2, AgglomerativeClusterer.cluster(e, minClusters = 2, maxClusters = 2).toSet().size)
        val mixed = speakers(Random(5), k = 2, perSpeaker = 10) + List(3) { FloatArray(64) { Float.NaN } }
        assertEquals(2, AgglomerativeClusterer.cluster(mixed, minClusters = 2, maxClusters = 2).toSet().size)
    }
}
