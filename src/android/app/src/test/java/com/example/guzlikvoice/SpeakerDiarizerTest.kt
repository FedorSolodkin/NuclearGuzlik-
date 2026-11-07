package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerDiarizerTest {
    @Test fun cachedEvidenceNeedsNeitherAudioNorLoadedModels() {
        val evidence = SpeakerDiarizer.EvidenceSnapshot(
            listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f)),
            listOf(SpeakerActivityFusion.Window(0,2000)),
            listOf(SpeakerDiarizer.EmbeddingRegion(0,1000,0,0),SpeakerDiarizer.EmbeddingRegion(1000,2000,1,0)))
        val segments = listOf(WhisperHelper.Segment("Раз.",0,900),WhisperHelper.Segment("Два.",1100,1900))
        val result = SpeakerDiarizer.diarize(segments,emptyMap(),null,cachedEvidence=evidence)
        assertArrayEquals(intArrayOf(0,1),result.speakers)
        assertEquals(2,result.centroids.size)
        assertTrue(result.text.contains("Раз."));assertTrue(result.text.contains("Два."))
    }
    @Test fun pausesWithoutVoiceEvidenceDoNotInventPeople() {
        val segments = List(12) { i -> WhisperHelper.Segment("Фраза $i.", i * 5000L, i * 5000L + 500) }
        val result = SpeakerDiarizer.diarize(segments, emptyMap(), null, numSpeakers = 3)
        assertArrayEquals(IntArray(12), result.speakers)
        assertTrue(result.centroids.isEmpty())
        assertTrue(result.previewOffsetsMs.isEmpty())
        assertFalse(result.text.contains("[Говорящий"))
        assertEquals(segments.joinToString(" ") { it.text }, result.text)
    }

    @Test fun emptyInputDoesNotLeaveSpeakerMetadata() {
        val result = SpeakerDiarizer.diarize(emptyList(), emptyMap(), null)
        assertEquals("", result.text)
        assertTrue(result.displayNames.isEmpty())
        assertTrue(result.speakers.isEmpty())
    }
    @Test fun legacyEvidenceWithoutPreviewFieldsKeepsIndependentVoices() {
        val vectors=List(30){i->FloatArray(3){if(it==i%3)1f else 0f}}
        val regions=List(30){i->SpeakerDiarizer.EmbeddingRegion(i*1000L,i*1000L+900,i,0,0)}
        val evidence=SpeakerDiarizer.EvidenceSnapshot(vectors,listOf(SpeakerActivityFusion.Window(0,30000)),regions)
        val segments=regions.map{WhisperHelper.Segment("Да.",it.startMs,it.endMs)}
        val result=SpeakerDiarizer.diarize(segments,emptyMap(),null,cachedEvidence=evidence)
        assertEquals(3,result.centroids.size)
        assertEquals(setOf(0,1,2),result.speakers.toSet())
    }

    @Test fun incoherentNoiseCannotBeReportedAsAConfidentVoice() {
        val vectors=List(12){i->FloatArray(12){if(it==i)1f else 0f}}
        val regions=List(12){i->SpeakerDiarizer.EmbeddingRegion(i*100L,i*100L+100,i,0)}
        val evidence=SpeakerDiarizer.EvidenceSnapshot(vectors,listOf(SpeakerActivityFusion.Window(0,1200)),regions)
        val segments=listOf(WhisperHelper.Segment("Исходный текст.",0,1200))
        val result=SpeakerDiarizer.diarize(segments,emptyMap(),null,cachedEvidence=evidence)
        assertTrue(result.centroids.isEmpty());assertTrue(result.turns.isEmpty())
        assertEquals("Исходный текст.",result.text)
    }

}
