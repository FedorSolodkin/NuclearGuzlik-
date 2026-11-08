package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class GigaAmChunkPlannerTest {
    private fun plan(speech: List<Pair<Long, Long>>, duration: Long = 40000) =
        GigaAmChunkPlanner.padAndMerge(speech, duration, 300, 700, 8000, 22000)

    @Test fun longAdjacentSpeechIsNotDecodedTwiceAtThePaddedBoundary() {
        val chunks = plan(listOf(0L to 20000L, 20000L to 40000L))
        assertEquals(listOf(0L to 20000L, 20000L to 40000L), chunks)
    }
    @Test fun shortGapPaddingCannotOverlapRequestsThatWouldExceedTheLimit() {
        val chunks = plan(listOf(0L to 20000L, 20200L to 40000L))
        assertEquals(20100L, chunks[0].second)
        assertEquals(chunks[0].second, chunks[1].first)
        assertTrue(chunks[0].second >= 20000L)
        assertTrue(chunks[1].first <= 20200L)
    }
    @Test fun paddingClippedAtFileEndDoesNotCutTheLastActualSpeech() {
        val chunks = plan(listOf(0L to 39900L, 39950L to 40000L))
        assertEquals(39925L, chunks[0].second)
        assertEquals(39925L, chunks[1].first)
    }
    @Test fun paddingClippedAtFileStartDoesNotCutTheNextActualSpeech() {
        val chunks = plan(listOf(0L to 100L, 150L to 40000L))
        assertEquals(125L, chunks[0].second)
        assertEquals(125L, chunks[1].first)
    }
    @Test fun nearbyShortTurnsStillShareOneRecognitionRequest() {
        assertEquals(listOf(0L to 10300L), plan(listOf(100L to 4000L, 4500L to 10000L)))
    }
    @Test fun actualLongSilencesStayOutsideRecognition() {
        assertEquals(listOf(700L to 3300L, 19700L to 23300L), plan(listOf(1000L to 3000L, 20000L to 23000L)))
    }
}
