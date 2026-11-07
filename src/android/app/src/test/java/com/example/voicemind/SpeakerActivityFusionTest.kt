package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class SpeakerActivityFusionTest {
    private fun w(a: Long, b: Long) = SpeakerActivityFusion.Window(a,b)
    private fun e(a: Long,b: Long,s: Int,w: Int) = SpeakerActivityFusion.Evidence(a,b,s,w)
    @Test fun disagreementBetweenWindowsIsNotSimultaneousSpeech() {
        val out = SpeakerActivityFusion.fuse(listOf(w(0,1000),w(0,1000)), listOf(e(0,1000,0,0),e(0,1000,1,1)))
        assertEquals(1, out.size)
        assertEquals(1000L, out.sumOf { it.endMs-it.startMs })
    }
    @Test fun genuineOverlapWithinWindowsIsPreserved() {
        val out = SpeakerActivityFusion.fuse(listOf(w(0,1000),w(0,1000)),
            listOf(e(0,1000,0,0),e(200,800,1,0),e(0,1000,0,1),e(200,800,1,1)))
        assertEquals(1600L, out.sumOf { it.endMs-it.startMs })
        assertTrue(out.contains(SpeakerTimeline.Region(200,800,1)))
    }
    @Test fun repeatedEvidenceInSameWindowDoesNotRaiseSpeakerCount() {
        val out = SpeakerActivityFusion.fuse(listOf(w(0,1000)), List(10) { e(100,900,0,0) })
        assertEquals(listOf(SpeakerTimeline.Region(100,900,0)),out)
    }
    @Test fun silenceAndWindowEdgesDoNotProduceExtraTurns() {
        val out = SpeakerActivityFusion.fuse(listOf(w(0,1000)),listOf(e(-500,1500,0,0)))
        assertEquals(listOf(SpeakerTimeline.Region(0,1000,0)),out)
        assertTrue(SpeakerActivityFusion.fuse(listOf(w(0,1000)),emptyList()).isEmpty())
    }
}
