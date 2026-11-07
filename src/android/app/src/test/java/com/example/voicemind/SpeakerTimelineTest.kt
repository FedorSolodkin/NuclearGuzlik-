package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class SpeakerTimelineTest {
    private fun r(a: Long, b: Long, k: Int) = SpeakerTimeline.Region(a, b, k)
    @Test fun duplicateAnalysisWindowsDoNotMultiplyVotes() {
        val timeline = SpeakerTimeline(List(10) { r(0, 400, 0) } + r(400, 1000, 1))
        assertEquals(1, timeline.assign(0, 1000))
    }
    @Test fun durationOutweighsWindowCount() {
        val timeline = SpeakerTimeline(listOf(r(0, 50, 0), r(50, 100, 0), r(100, 900, 1)))
        assertEquals(1, timeline.assign(0, 900))
    }
    @Test fun zeroDurationWordUsesContainingRegion() {
        val timeline = SpeakerTimeline(listOf(r(0, 1000, 1), r(1000, 2000, 0)))
        assertEquals(1, timeline.assign(999, 999))
        assertEquals(0, timeline.assign(1000, 1000))
    }
    @Test fun findsNearestNeighbourAcrossSilence() {
        val timeline = SpeakerTimeline(listOf(r(0, 200, 0), r(1000, 1200, 1)))
        assertEquals(0, timeline.assign(300, 400))
        assertEquals(1, timeline.assign(800, 900))
        assertEquals(0, timeline.assign(-100, -20))
        assertEquals(1, timeline.assign(1500, 1600))
    }
    @Test fun prefixIndexDoesNotMissLongOverlappingRegion() {
        val timeline = SpeakerTimeline(listOf(r(0, 10000, 1), r(20, 30, 0), r(40, 50, 0)))
        assertEquals(1, timeline.assign(9000, 9100))
    }
    @Test fun inputOrderAndTouchingRunsDoNotChangeResult() {
        val runs = listOf(r(0, 200, 0), r(200, 400, 0), r(400, 700, 1))
        assertEquals(0, SpeakerTimeline(runs.reversed()).assign(0, 700))
    }
    @Test fun hourLongTimelineMatchesFullScan() {
        val regions = List(2400) { i -> r(i * 1500L, (i + 1) * 1500L, i % 3) }
        val timeline = SpeakerTimeline(regions)
        val queries = List(20000) { i -> i * 180L to i * 180L + 160 }
        repeat(2) { queries.forEach { timeline.assign(it.first, it.second) } }
        val t0 = System.nanoTime()
        val expected = queries.map { (a, b) ->
            val votes = LongArray(3)
            for (r in regions) {
                val overlap = minOf(b, r.endMs) - maxOf(a, r.startMs)
                if (overlap > 0) votes[r.speaker] += overlap
            }
            votes.indices.maxByOrNull { votes[it] }!!
        }
        val t1 = System.nanoTime()
        val actual = queries.map { timeline.assign(it.first, it.second) }
        val t2 = System.nanoTime()
        assertEquals(expected, actual)
        println("HOST synthetic 1h / 20000 words: full scan %.2f ms, indexed %.2f ms (not Android inference)".format((t1-t0)/1e6, (t2-t1)/1e6))
    }

}
