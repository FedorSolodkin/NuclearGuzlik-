package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class SpeakerPreviewsTest {
    private val centers=listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f))
    private fun snapshot(vectors:List<FloatArray>,regions:List<SpeakerDiarizer.EmbeddingRegion>)=
        SpeakerDiarizer.EvidenceSnapshot(vectors,listOf(SpeakerActivityFusion.Window(0,10000)),regions)

    @Test fun previewFitsInsideOneVoiceAndCanStartNearTheBeginning() {
        val e=snapshot(listOf(centers[0]),listOf(SpeakerDiarizer.EmbeddingRegion(0,10000,0,0)))
        val turns=listOf(SpeakerTimeline.Region(0,4000,0),SpeakerTimeline.Region(4000,10000,1))
        val offsets=SpeakerPreviews.select(e,intArrayOf(0),centers,turns)
        assertTrue(offsets[0]>0)
        assertTrue(offsets[0]+2500<=4000)
        assertEquals(0L,offsets[1])
    }
    @Test fun mixedEvidenceIsNotUsedAsAnIdentityPreview() {
        val e=snapshot(listOf(floatArrayOf(.7071f,.7071f)),listOf(SpeakerDiarizer.EmbeddingRegion(0,10000,0,0)))
        assertArrayEquals(longArrayOf(0,0),SpeakerPreviews.select(e,intArrayOf(0),centers,listOf(SpeakerTimeline.Region(0,10000,0))))
    }
    @Test fun cannotConcatenateShortCleanPartsAcrossOverlap() {
        val e=snapshot(listOf(centers[0]),listOf(SpeakerDiarizer.EmbeddingRegion(0,8000,0,0)))
        val turns=listOf(SpeakerTimeline.Region(0,8000,0),SpeakerTimeline.Region(2000,6000,1))
        assertEquals(0L,SpeakerPreviews.select(e,intArrayOf(0),centers,turns)[0])
    }
}
