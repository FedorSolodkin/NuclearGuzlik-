package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class SpeakerTimelineTest {
    private fun r(a: Long, b: Long, k: Int) = SpeakerTimeline.Region(a, b, k)
    @Test fun shortInitialUnvoicedWordUsesTheImmediatelyFollowingVoice() {
        val timeline = SpeakerTimeline(listOf(r(543, 6358, 0)))
        assertArrayEquals(intArrayOf(0, 0), timeline.decideWords(listOf(280L to 520L, 520L to 950L)))
    }
    @Test fun shortFinalUnvoicedTailUsesTheImmediatelyPrecedingVoice() {
        val timeline = SpeakerTimeline(listOf(r(0, 540, 1)))
        assertArrayEquals(intArrayOf(1, 1), timeline.decideWords(listOf(0L to 500L, 500L to 800L)))
        assertArrayEquals(intArrayOf(1, 1), timeline.decideWords(listOf(0L to 500L, 580L to 800L)))
    }
    @Test fun finalTailRepairNeverBridgesAnotherVoiceOrLongSilence() {
        val changed = SpeakerTimeline(listOf(r(0, 500, 0), r(540, 580, 1)))
        assertArrayEquals(intArrayOf(0, -1), changed.decideWords(listOf(0L to 500L, 500L to 850L)))
        val silence = SpeakerTimeline(listOf(r(0, 500, 0)))
        assertArrayEquals(intArrayOf(0, -1), silence.decideWords(listOf(0L to 500L, 700L to 950L)))
        assertArrayEquals(intArrayOf(0, -1), silence.decideWords(listOf(0L to 500L, 500L to 1000L)))
    }
    @Test fun shortWordAtANewTurnUsesItsUniqueAcousticOnsetAndFollowingWord() {
        // Captured RNNT + pyannote boundary: 35 / 160 ms acoustic coverage.
        // The preceding person has stopped; the new voice continues into the next word.
        val timeline = SpeakerTimeline(listOf(r(23616, 29032, 1), r(29516, 31426, 0)))
        assertArrayEquals(intArrayOf(1, 0, 0), timeline.decideWords(
            listOf(28431L to 29071L, 29391L to 29551L, 29551L to 30071L)))
    }
    @Test fun onsetRepairRequiresUniqueContinuousEvidenceAndABoundedExtension() {
        val words = listOf(0L to 500L, 600L to 900L, 900L to 1200L)
        // Two sequential voices inside the short word must not be collapsed to its successor.
        val mixed = SpeakerTimeline(listOf(r(0, 680, 1), r(820, 1200, 0)))
        assertEquals(-1, mixed.decideWords(words)[1])
        // A tiny onset at the very end of a long word is not sufficient evidence.
        val distant = SpeakerTimeline(listOf(r(0, 500, 1), r(870, 1200, 0)))
        assertEquals(-1, distant.decideWords(words)[1])
        // Equal labels on two disconnected intervals must not bridge intervening silence.
        val disconnected = SpeakerTimeline(listOf(r(0, 500, 1), r(700, 720, 0), r(900, 1200, 0)))
        assertEquals(-1, disconnected.decideWords(words)[1])
    }
    @Test fun shortLowCoverageWordWithinOneVoiceDoesNotCreateASeparateUnknownTurn() {
        val timeline = SpeakerTimeline(listOf(r(25398, 31460, 1), r(31816, 46528, 1)))
        assertArrayEquals(intArrayOf(1, 1, 1), timeline.decideWords(
            listOf(30300L to 31540L, 31540L to 31850L, 31880L to 32350L)))
    }
    @Test fun aRealSpeakerChangeOrSilenceDoesNotAcquireAnInventedIdentity() {
        val timeline = SpeakerTimeline(listOf(r(0, 1000, 0), r(1300, 2000, 1)))
        assertArrayEquals(intArrayOf(0, -1, 1), timeline.decideWords(
            listOf(700L to 1000L, 1000L to 1300L, 1300L to 1600L)))
        assertEquals(-1, timeline.decideWords(listOf(100L to 300L, 500L to 700L,
            2000L to 2300L, 2400L to 2600L))[2])
    }
    @Test fun simultaneousSpeechAndLongUnknownWordsRemainUnresolved() {
        val timeline = SpeakerTimeline(listOf(r(0, 1000, 0), r(300, 700, 1)))
        assertEquals(-2, timeline.decideWords(listOf(0L to 200L, 300L to 700L, 800L to 1000L))[1])
        val missing = SpeakerTimeline(listOf(r(1100, 2000, 0)))
        assertEquals(-1, missing.decideWords(listOf(0L to 1000L, 1100L to 1500L))[0])
    }
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
