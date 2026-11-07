package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class RobustSpeakerClusteringTest {
    @Test fun outliersDoNotForceThreeRealVoicesIntoOne() {
        val e = List(90) { i -> FloatArray(12) { if (it == i % 3) 1f else 0f } } +
            List(9) { i -> FloatArray(12) { if (it == 3+i) 1f else 0f } }
        val labels = RobustSpeakerClustering.cluster(e)
        assertEquals(3, labels.toSet().size)
        for (i in 0 until 90) assertEquals(labels[i%3], labels[i])
    }
    @Test fun smallSampleDoesNotRequireRepeatedEvidenceForEveryPerson() {
        val labels = RobustSpeakerClustering.cluster(listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f)))
        assertEquals(2, labels.toSet().size)
    }
    @Test fun explicitCountChoosesSupportedGroupsBeforeOutliers() {
        val e = List(60) { i -> FloatArray(4) { if (it == i%3) 1f else 0f } } + floatArrayOf(0f,0f,0f,1f)
        val labels = RobustSpeakerClustering.cluster(e, fixedK = 3)
        assertEquals(3,labels.toSet().size)
        for (i in 0 until 60) assertEquals(labels[i%3],labels[i])
    }
    @Test fun explicitCountDoesNotInventVoicesFromIdenticalObservations() {
        assertEquals(1,RobustSpeakerClustering.cluster(List(20){floatArrayOf(1f,0f)},fixedK=3).toSet().size)
    }
    @Test fun knownCountCanSeparateCloseButConsistentVoices() {
        val e=List(40){if(it%2==0)floatArrayOf(1f,0f)else floatArrayOf(0.85f,0.5267827f)}
        val labels=RobustSpeakerClustering.cluster(e,fixedK=2)
        assertEquals(2,labels.toSet().size)
        assertNotEquals(labels[0],labels[1])
    }
    @Test fun oneSpeakerAndEmptyInputAreStable() {
        assertEquals(0,RobustSpeakerClustering.cluster(emptyList()).size)
        assertEquals(setOf(0),RobustSpeakerClustering.cluster(List(20) { floatArrayOf(1f,0f) }).toSet())
    }
    @Test fun overlappingViewsDoNotAddPeopleInAShortDialogue() {
        val e=List(30){floatArrayOf(1f,0f,0f,0f)}+List(30){floatArrayOf(0f,1f,0f,0f)}+
            List(2){floatArrayOf(0f,0f,1f,0f)}+List(3){floatArrayOf(0f,0f,0f,1f)}
        val times=e.indices.map{longArrayOf(if(it<60)it*5000L else 1000L+(it-60)*50)}
        assertEquals(2,RobustSpeakerClustering.clusterSupported(e,times).toSet().size)
    }
    @Test fun aRepeatedBriefParticipantStillSurvives() {
        val e=List(30){floatArrayOf(1f,0f,0f)}+List(30){floatArrayOf(0f,1f,0f)}+List(6){floatArrayOf(0f,0f,1f)}
        val times=e.indices.map{longArrayOf(it*5000L)}
        val labels=RobustSpeakerClustering.clusterSupported(e,times)
        assertEquals(3,labels.toSet().size)
        assertNotEquals(labels[0],labels.last())
    }
    @Test fun unsupportedNoiseDoesNotBecomeEightPeople() {
        val e=List(12){i->FloatArray(12){if(it==i)1f else 0f}}
        val times=e.indices.map{longArrayOf(it*20L)}
        assertEquals(1,RobustSpeakerClustering.clusterSupported(e,times).toSet().size)
    }

    @Test fun aDistinctPersonCanSpeakOnlyOnceForThreeSeconds() {
        val e=List(20){floatArrayOf(1f,0f,0f)}+List(2){floatArrayOf(0f,1f,0f)}
        val times=e.indices.map{longArrayOf(if(it<20)it*5000L else 1000L+(it-20)*50)}
        val spans=e.indices.map{listOf(RobustSpeakerClustering.SpeechSpan(if(it<20)it*5000L else 1000L,if(it<20)it*5000L+3000 else 4000L))}
        assertEquals(2,RobustSpeakerClustering.clusterSupported(e,times,anchorSpans=spans).toSet().size)
    }

    @Test fun overlappingViewsOfOneShortBurstDoNotMultiplyItsSupport() {
        val e=List(20){floatArrayOf(1f,0f)}+List(4){floatArrayOf(0f,1f)}
        val times=e.indices.map{longArrayOf(if(it<20)it*5000L else 1000L+(it-20)*50)}
        val spans=e.indices.map{listOf(RobustSpeakerClustering.SpeechSpan(if(it<20)it*5000L else 1000L,if(it<20)it*5000L+3000 else 2000L))}
        assertEquals(1,RobustSpeakerClustering.clusterSupported(e,times,anchorSpans=spans).toSet().size)
    }

    @Test fun aCoherentNearbyVoiceVariantStillNeedsRepeatedIndependentTurns() {
        val e=List(20){floatArrayOf(1f,0f)}+List(2){floatArrayOf(.6f,.8f)}
        val times=e.indices.map{longArrayOf(if(it<20)it*5000L else 1000L+(it-20)*50)}
        val spans=e.indices.map{listOf(RobustSpeakerClustering.SpeechSpan(if(it<20)it*5000L else 1000L,if(it<20)it*5000L+3000 else 4000L))}
        assertEquals(1,RobustSpeakerClustering.clusterSupported(e,times,anchorSpans=spans).toSet().size)
    }
    @Test fun aLongRecordingKeepsItsStrongerOutlierSupportRequirement() {
        val e=List(1000){floatArrayOf(1f,0f)}+List(3){floatArrayOf(0f,1f)}
        val times=e.indices.map{longArrayOf(if(it<1000)it*5000L else 1000L+(it-1000)*50)}
        val spans=e.indices.map{listOf(RobustSpeakerClustering.SpeechSpan(if(it<1000)it*5000L else 1000L,if(it<1000)it*5000L+3000 else 4000L))}
        assertEquals(1,RobustSpeakerClustering.clusterSupported(e,times,anchorSpans=spans).toSet().size)
    }
    @Test fun twoMatchingViewsOfASingleTurnDoNotSplitALargerDialogue() {
        val e=List(100){i->if(i%2==0)floatArrayOf(1f,0f,0f)else floatArrayOf(0f,1f,0f)}+
            List(2){floatArrayOf(0f,0f,1f)}
        val times=e.indices.map{longArrayOf(if(it<100)it*5000L else 1000L+(it-100)*50)}
        val spans=e.indices.map{listOf(RobustSpeakerClustering.SpeechSpan(if(it<100)it*5000L else 1000L,if(it<100)it*5000L+3000 else 4000L))}
        assertEquals(2,RobustSpeakerClustering.clusterSupported(e,times,anchorSpans=spans).toSet().size)
    }

}
