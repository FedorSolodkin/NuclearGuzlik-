package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test

class SpeakerQualityTest {
    private fun r(a: Long,b: Long,s: Int) = SpeakerTimeline.Region(a,b,s)
    @Test fun simultaneousVoicesAreNotConfusedWithSequentialBoundary() {
        val overlap = SpeakerTimeline(listOf(r(0,1000,0),r(400,1000,1))).decide(500,800)
        assertEquals(SpeakerTimeline.OVERLAP,overlap.speaker)
        assertEquals(listOf(0,1),overlap.candidates)
        val boundary = SpeakerTimeline(listOf(r(0,500,0),r(500,1000,1))).decide(400,600)
        assertEquals(SpeakerTimeline.UNKNOWN,boundary.speaker)
    }
    @Test fun shortReplySurvivesAndSilenceDoesNotAcquireIdentity() {
        val t = SpeakerTimeline(listOf(r(0,1000,0),r(1000,1150,1),r(1150,2000,0)))
        assertEquals(1,t.decide(1010,1130).speaker)
        assertEquals(SpeakerTimeline.UNKNOWN,t.decide(4000,4200).speaker)
        assertEquals(0,t.decide(200,200).speaker)
    }
    @Test fun collapsedWordAtTurnBoundaryIsNotForcedIntoNextVoice() {
        val t=SpeakerTimeline(listOf(r(0,1000,0),r(1000,2000,1)))
        assertEquals(SpeakerTimeline.UNKNOWN,t.decide(1000,1000).speaker)
        assertEquals(0,t.decide(200,200).speaker)
    }
    @Test fun incompleteWordsPreserveTheEntirePhrase() {
        val s=WhisperHelper.Segment("Да, это важно.",0,1000, words=listOf(WhisperHelper.Word("Да,",0,200,1f)))
        val valid=WhisperHelper.Segment("Верно.",1100,1500,words=listOf(WhisperHelper.Word("Верно.",1100,1500,1f)))
        val units=DiarizationUnits.expand(listOf(s,valid))!!
        assertEquals(listOf("Да, это важно.","Верно."),units.map{it.text})
    }
    @Test fun overlapOnlyTranscriptRetainsItsMarkerAfterFormatting() {
        assertEquals("[Говорят одновременно]\nДа.",TextFormatter.format("[Говорят одновременно]\nДа."))
    }
    @Test fun poorCoverageAndBoundaryTiesAreUncertain() {
        assertEquals(SpeakerTimeline.UNKNOWN,SpeakerTimeline(listOf(r(0,20,0))).decide(0,100).speaker)
        assertEquals(SpeakerTimeline.UNKNOWN,SpeakerTimeline(listOf(r(0,49,0),r(49,100,1))).decide(0,100).speaker)
    }
    @Test fun rareParticipantSurvivesLongMeetingWithOutliers() {
        val e = List(1000) { floatArrayOf(1f,0f,0f,0f) } + List(3) { floatArrayOf(0f,1f,0f,0f) } + floatArrayOf(0f,0f,1f,0f)
        val labels = RobustSpeakerClustering.clusterSupported(e, e.indices.map { longArrayOf(it * 10000L) })
        assertEquals(2, labels.toSet().size)
        assertNotEquals(labels[0], labels[1000])
    }
    @Test fun overlappingViewsOfOneNoiseBurstDoNotBecomeAnotherPerson() {
        val e=List(1000){floatArrayOf(1f,0f)}+List(3){floatArrayOf(0f,1f)}
        val times=e.indices.map{longArrayOf(if(it<1000)it*10000L else 100L+it)}
        assertEquals(1,RobustSpeakerClustering.clusterSupported(e,times).toSet().size)
    }
    @Test fun samplingFindsBriefVoiceBetweenUniformSamplePoints() {
        val e = MutableList(5000) { floatArrayOf(1f,0f,0f) }
        e[123]=floatArrayOf(0f,1f,0f);e[124]=floatArrayOf(0f,1f,0f)
        val indices = DiverseSpeakerSampling.indices(e,64)
        assertEquals(64,indices.size);assertEquals(indices.size,indices.toSet().size)
        assertTrue(indices.contains(123));assertTrue(indices.contains(124))
        val labels = BoundedSpeakerClustering.cluster(e,64) { RobustSpeakerClustering.cluster(it) }
        assertNotEquals(labels[0],labels[123]);assertEquals(labels[123],labels[124])
    }
    @Test fun relabelMiddleAndRestoreFollowingSpeakerWithoutLosingWords() {
        val text="[Говорящий А]\nОдин два три.\n\n[Говорящий Б]\nЧетыре."
        val start=text.indexOf("два")
        val changed=SpeakerTextEdits.relabel(text,start,start+3,"[Говорящий Б]")
        assertTrue(changed.contains("[Говорящий Б]\nдва"))
        assertTrue(changed.contains("[Говорящий А]\nтри."))
        assertTrue(changed.endsWith("[Говорящий Б]\nЧетыре."))
        assertEquals(listOf("Один","два","три.","Четыре."),Regex("\\[[^]]+]").replace(changed,"").trim().split(Regex("\\s+")))
    }
    @Test fun relabelWholeBlockAndOverlappingSelectionKeepsNoEmptyMarkers() {
        val text="[Говорящий А]\nПривет мир."
        val changed=SpeakerTextEdits.relabel(text,0,text.length,"[Говорят одновременно]")
        assertEquals("[Говорят одновременно]\nПривет мир.",changed)
        val first=SpeakerTextEdits.relabel(text,text.indexOf("Привет"),text.indexOf(" мир"),"[Говорящий не определён]")
        assertFalse(first.contains("[Говорящий А]\n\n["))
        assertTrue(first.endsWith("[Говорящий А]\nмир."))
    }
}
