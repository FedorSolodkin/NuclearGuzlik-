package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class SpeakerRefinementTest {

    private fun spanAt(ms: Long) = listOf(RobustSpeakerClustering.SpeechSpan(ms, ms + 3000))

    /** Два голоса с косинусом ~0.71: первый порог 0.35 сливает их, split обязан разделить. */
    @Test fun mergedSimilarVoicesAreSplitBySecondPass() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0.70710678f, 0.70710678f, 0f)
        val e = List(30) { a } + List(30) { b }
        val first = IntArray(e.size)
        val spans = e.indices.map { spanAt(it * 6000L) }
        val labels = SpeakerRefinement.refine(e, first, spans)
        assertEquals(2, labels.toSet().size)
        assertNotEquals(labels[0], labels[31])
        for (i in 0 until 29) assertEquals(labels[i], labels[i + 1])
        for (i in 30 until 59) assertEquals(labels[i], labels[i + 1])
    }

    /** Вариации одного голоса внутри порога split не создают второго человека. */
    @Test fun oneVoiceVariationsStayMerged() {
        val e = List(60) { i -> floatArrayOf(1f, 0.02f * i, 0f).let { v ->
            val n = kotlin.math.sqrt(v.sumOf { it.toDouble() * it }).toFloat(); floatArrayOf(v[0]/n, v[1]/n, v[2]/n) } }
        val first = IntArray(e.size)
        val spans = e.indices.map { spanAt(it * 6000L) }
        assertEquals(1, SpeakerRefinement.refine(e, first, spans).toSet().size)
    }

    /** Короткий всплеск внутри большого кластера не раскалывает его на два голоса. */
    @Test fun tinyBurstDoesNotSplitCluster() {
        val a = floatArrayOf(1f, 0f, 0f)
        val e = List(30) { a } + List(2) { floatArrayOf(0f, 1f, 0f) }
        val first = IntArray(e.size)
        val spans = e.indices.map { spanAt(if (it < 30) it * 6000L else 1000L) }
        val labels = SpeakerRefinement.refine(e, first, spans)
        assertEquals(1, labels.toSet().size)
    }

    /** Перекрывающиеся окна одной реплики не дают независимой временно́й поддержки для split. */
    @Test fun overlappingWindowsDoNotSupportSplit() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0.70710678f, 0.70710678f, 0f)
        val e = List(30) { a } + List(30) { b }
        val first = IntArray(e.size)
        // Все наблюдения внутри одного 3-секундного интервала речи.
        val spans = e.indices.map { listOf(RobustSpeakerClustering.SpeechSpan(0L, 3000L)) }
        assertEquals(1, SpeakerRefinement.refine(e, first, spans).toSet().size)
    }

    /** Явный монолог отключает split целиком. */
    @Test fun explicitMonologueDisablesSplit() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0.70710678f, 0.70710678f, 0f)
        val e = List(30) { a } + List(30) { b }
        val first = IntArray(e.size)
        val spans = e.indices.map { spanAt(it * 6000L) }
        assertEquals(1, SpeakerRefinement.refine(e, first, spans, fixedK = 1).toSet().size)
    }

    /** fixedK ограничивает сверху: третий голос не появляется при фиксированных двух. */
    @Test fun splitRespectsFixedCount() {
        val base = floatArrayOf(1f, 0f, 0f, 0f)
        val e = List(20) { base } +
            List(20) { floatArrayOf(0.70710678f, 0.70710678f, 0f, 0f) } +
            List(20) { floatArrayOf(0f, 0f, 1f, 0f) }
        val first = IntArray(e.size) { if (it >= 40) 1 else 0 }
        val spans = e.indices.map { spanAt(it * 6000L) }
        val labels = SpeakerRefinement.refine(e, first, spans, fixedK = 2)
        assertTrue("expected ≤2 voices, got ${labels.toSet()}", labels.toSet().size <= 2)
    }

    /** Purify: выброс-наблюдение между двумя кластерами уходит к ближайшему центру. */
    @Test fun purifyReassignsStrayObservation() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val stray = floatArrayOf(0.08f, 0.99f, 0f) // почти b, но ошибочно в кластере a
        val e = List(20) { a } + listOf(stray) + List(20) { b }
        val first = IntArray(e.size) { if (it in 0..20) 0 else 1 }
        val spans = e.indices.map { spanAt(it * 6000L) }
        val labels = SpeakerRefinement.refine(e, first, spans)
        assertEquals("stray must join the b voice", labels[40], labels[20])
    }

    /** Слабый регион приписывается достаточно близкому центру. */
    @Test fun unreliableRegionAttachesToCloseCentroid() {
        val centers = listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        assertEquals(1, SpeakerRefinement.attachUnreliable(floatArrayOf(0.05f, 0.95f, 0f), centers))
        assertEquals(0, SpeakerRefinement.attachUnreliable(floatArrayOf(0.95f, 0.05f, 0f), centers))
    }

    /** Слабый регион без достаточно близкого центра остаётся без владельца. */
    @Test fun distantUnreliableRegionStaysUnassigned() {
        val centers = listOf(floatArrayOf(1f, 0f, 0f))
        assertNull(SpeakerRefinement.attachUnreliable(floatArrayOf(0f, 1f, 0f), centers))
    }
}
