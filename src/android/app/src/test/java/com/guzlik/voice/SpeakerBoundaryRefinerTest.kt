package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class SpeakerBoundaryRefinerTest {
    private val centers=listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f))
    private fun obs(a:Long,b:Long,voice:Int)=SpeakerBoundaryRefiner.Observation(a,b,centers[voice])
    private fun voiceAt(turns:List<SpeakerTimeline.Region>,ms:Long)=turns.filter{it.startMs<=ms && it.endMs>ms}.map{it.speaker}.toSet()

    @Test fun recoversAnEmbeddedReplyWithoutChangingTheVoiceInventory() {
        val original=listOf(SpeakerTimeline.Region(0,10_000,0))
        val observations=(0L..8_000L step 500L).map {a->obs(a,a+2000,if(a in 2500..5000)1 else 0)}
        val result=SpeakerBoundaryRefiner.refine(original,observations,centers)
        assertEquals(setOf(0),voiceAt(result,500))
        assertEquals(setOf(1),voiceAt(result,4000))
        assertEquals(setOf(0),voiceAt(result,9500))
        assertEquals(setOf(0,1),result.map{it.speaker}.toSet())
    }
    @Test fun oneContradictoryObservationCannotCreateAReply() {
        val original=listOf(SpeakerTimeline.Region(0,6000,0))
        val observations=(0L..4000L step 500L).map {a->obs(a,a+2000,if(a==2000L)1 else 0)}
        assertEquals(original,SpeakerBoundaryRefiner.refine(original,observations,centers))
    }
    @Test fun neverFillsSilenceOrReplacesRealOverlap() {
        val original=listOf(SpeakerTimeline.Region(0,3000,0),SpeakerTimeline.Region(1000,3000,1),SpeakerTimeline.Region(5000,8000,0))
        val observations=(0L..6000L step 500L).map{obs(it,it+2000,1)}
        val result=SpeakerBoundaryRefiner.refine(original,observations,centers)
        assertEquals(setOf(0,1),voiceAt(result,2000))
        assertTrue(voiceAt(result,4000).isEmpty())
        assertEquals(setOf(1),voiceAt(result,6500))
    }
    @Test fun similarCentersDoNotProduceConfidentChanges() {
        val original=listOf(SpeakerTimeline.Region(0,6000,0))
        val close=listOf(floatArrayOf(1f,0f),floatArrayOf(.999f,.0447f))
        val observations=(0L..4000L step 500L).map{SpeakerBoundaryRefiner.Observation(it,it+2000,close[1])}
        assertEquals(original,SpeakerBoundaryRefiner.refine(original,observations,close))
    }
    @Test fun plansOnlyLongAmbiguousEvidenceAndClampsToAudio() {
        val evidence=SpeakerDiarizer.EvidenceSnapshot(listOf(floatArrayOf(.7071f,.7071f),centers[0]),
            listOf(SpeakerActivityFusion.Window(0,10_000)),listOf(
                SpeakerDiarizer.EmbeddingRegion(100,4100,0,0),
                SpeakerDiarizer.EmbeddingRegion(6000,6500,0,0),
                SpeakerDiarizer.EmbeddingRegion(7000,10_000,1,0)))
        assertEquals(listOf(SpeakerBoundaryRefiner.Span(0,5000)),SpeakerBoundaryRefiner.plan(evidence,centers))
        assertTrue(SpeakerBoundaryRefiner.windows(SpeakerBoundaryRefiner.plan(evidence,centers)).all{it.startMs>=0 && it.endMs<=10_000})
    }
    @Test fun interruptedSecondPassResumesItsOrderedPrefix() {
        val base=SpeakerDiarizer.EvidenceSnapshot(listOf(floatArrayOf(.7071f,.7071f)),
            listOf(SpeakerActivityFusion.Window(0,10000)),listOf(SpeakerDiarizer.EmbeddingRegion(0,10000,0,0)))
        val audio=ArrayPcm(ShortArray(160000))
        var saved:SpeakerBoundaryRefiner.Evidence?=null
        assertThrows(java.util.concurrent.CancellationException::class.java){
            SpeakerBoundaryRefiner.capture(base,centers,audio,{centers[0]},checkpointEveryObservations=2,checkpoint={e->
                saved=e
                if(e.observations.size==2)throw java.util.concurrent.CancellationException("Simulated second-pass pause")
            })
        }
        assertEquals(2,saved!!.observations.size);assertFalse(saved!!.completed)
        val resumed=SpeakerBoundaryRefiner.capture(base.copy(boundaryRefinement=saved),centers,audio,{centers[0]})
        assertTrue(resumed.completed)
        val planned=SpeakerBoundaryRefiner.windows(SpeakerBoundaryRefiner.plan(base,centers))
        assertEquals(planned.map{it.startMs},resumed.observations.map{it.startMs})
        assertSame(saved!!.observations[0],resumed.observations[0])
    }

    @Test fun existingShortBackchannelSurvivesLongerWindowsFromItsNeighbors() {
        val original=listOf(SpeakerTimeline.Region(0,4000,0),SpeakerTimeline.Region(4000,4600,1),SpeakerTimeline.Region(4600,10000,0))
        val observations=(0L..8000L step 500L).map{obs(it,it+2000,0)}
        val result=SpeakerBoundaryRefiner.refine(original,observations,centers)
        assertEquals(setOf(1),voiceAt(result,4300))
        assertEquals(setOf(0),voiceAt(result,3500))
        assertEquals(setOf(0),voiceAt(result,5000))
    }

}
