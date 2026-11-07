package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test

class SegmentationSpeechTest {
    private fun logits(vararg classes: Int) = Array(classes.size) { i ->
        FloatArray(7) { if (it == classes[i]) 1f else -1f }
    }

    @Test fun keepsShortRepliesSeparatedByOtherSpeaker() {
        // 100 ms frames: B has three 200 ms replies, none individually embeddable.
        val runs = SegmentationSpeech.runs(logits(2, 2, 1, 2, 2, 1, 2, 2), 800, 800)
        val pcm = ShortArray(800) { (it / 100).toShort() }
        val pooled = SegmentationSpeech.samples(pcm, runs, 2)
        assertEquals(600, pooled.size)
        assertEquals(listOf<Short>(0, 1, 3, 4, 6, 7), pooled.toList().chunked(100).map { it.first() })
        assertEquals(3, runs.count { it.speaker == 2 })
    }

    @Test fun neverPoolsSilenceOverlapOrOtherSpeaker() {
        val runs = SegmentationSpeech.runs(logits(0, 1, 4, 2, 5, 1, 6, 3), 800, 800)
        assertEquals(listOf(1, 2, 1, 3), runs.map { it.speaker })
        val pcm = ShortArray(800) { (it / 100).toShort() }
        assertArrayEquals(ShortArray(200) { if (it < 100) 1 else 5 }, SegmentationSpeech.samples(pcm, runs, 1))
    }

    @Test fun clipsPaddedTailBeforePooling() {
        val runs = SegmentationSpeech.runs(logits(1, 1, 1, 2), 800, 350)
        assertEquals(listOf(SegmentationSpeech.Run(1, 0, 350)), runs)
        assertEquals(350, SegmentationSpeech.samples(ShortArray(350), runs, 1).size)
    }

    @Test fun usesActualFrameCountInsteadOfCachedModelShape() {
        val runs = SegmentationSpeech.runs(logits(1, 2, 3), 160_000, 160_000)
        assertEquals(53_333, runs[0].endSample)
        assertEquals(106_666, runs[1].endSample)
        assertEquals(160_000, runs[2].endSample)
    }

    @Test fun rejectsBrokenFramesAsVoiceEvidence() {
        val frames = logits(1, 2, 3)
        frames[1][0] = Float.NaN
        frames[2] = floatArrayOf(1f)
        assertEquals(listOf(SegmentationSpeech.Run(1, 0, 100)), SegmentationSpeech.runs(frames, 300, 300))
    }

    @Test fun handlesEmptyOrEntirelyPaddedChunk() {
        assertTrue(SegmentationSpeech.runs(emptyArray(), 160_000, 1).isEmpty())
        assertTrue(SegmentationSpeech.runs(logits(1), 160_000, 0).isEmpty())
        assertTrue(SegmentationSpeech.runs(logits(0, 4, 5, 6), 160_000, 160_000).isEmpty())
    }
    @Test fun overlapRetainsBothActivitiesButNeitherContaminatesEmbedding() {
        val frames = logits(1, 4, 2, 5, 3, 6)
        val activity = SegmentationSpeech.activityRuns(frames, 600, 600)
        assertEquals(listOf(SegmentationSpeech.Run(1, 0, 200), SegmentationSpeech.Run(1, 300, 400),
            SegmentationSpeech.Run(2, 100, 300), SegmentationSpeech.Run(2, 500, 600),
            SegmentationSpeech.Run(3, 300, 600)), activity)
        val clean = SegmentationSpeech.runs(frames, 600, 600)
        val pcm = ShortArray(600) { (it / 100).toShort() }
        assertArrayEquals(ShortArray(100) { 0 }, SegmentationSpeech.samples(pcm, clean, 1))
        assertArrayEquals(ShortArray(100) { 2 }, SegmentationSpeech.samples(pcm, clean, 2))
    }

    @Test fun reusedLocalIdentityDoesNotPoolDifferentLongTurns() {
        val anchors = listOf(SegmentationSpeech.Run(1, 100, 300), SegmentationSpeech.Run(1, 500, 700))
        val got = SegmentationSpeech.anchorActivity(listOf(SegmentationSpeech.Run(1, 100, 700)), anchors)
        assertEquals(listOf(0, 0, 1, 1), got.map { it.second })
        assertEquals(listOf(100, 300, 400, 500), got.map { it.first.startSample })
    }
    @Test fun anchorsNeverCrossLocalSpeakerIdentities() {
        val got = SegmentationSpeech.anchorActivity(listOf(SegmentationSpeech.Run(2, 0, 100)),
            listOf(SegmentationSpeech.Run(1, 0, 100)))
        assertTrue(got.isEmpty())
    }

}
