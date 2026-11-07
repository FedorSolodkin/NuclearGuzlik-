package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class BoundaryWordTimingTest {
    private fun w(text: String, a: Long, b: Long, p: Float = .9f) = WhisperHelper.Word(text, a, b, p)
    private fun segment(words: List<WhisperHelper.Word>) = listOf(WhisperHelper.Segment(
        words.joinToString(" ") { it.text }, words.first().startMs, words.last().endMs, words))
    private val turns = listOf(SpeakerTimeline.Region(0, 3000, 0), SpeakerTimeline.Region(4000, 8000, 1))
    @Test fun plansBoundedMergedClipsAndSkipsCompletedPasses() {
        val input = segment(List(20) { w("слово$it", it * 500L, it * 500L + 400) })
        val clips = BoundaryWordTiming.plan(input, turns + SpeakerTimeline.Region(8200, 10000, 0), 10000)
        assertEquals(1, clips.size)
        assertTrue(clips.single().endMs - clips.single().startMs <= 16000)
        val done = input.map { s -> s.copy(words = s.words!!.map { it.copy(boundaryPassVersion = BoundaryWordTiming.VERSION, boundaryPassText = it.text) }) }
        assertTrue(BoundaryWordTiming.plan(done, turns, 10000).isEmpty())
        assertTrue(BoundaryWordTiming.plan(input, listOf(turns.first()), 10000).isEmpty())
    }
    @Test fun matchingBoundaryWordsReceiveTimingButKeepEveryOriginalCharacter() {
        val input = segment(listOf(w("Окей,", 3100, 3500), w("Имя,", 3500, 3900), w("напомни?", 3900, 4300)))
        val fresh = listOf(w("Окей,", 4200, 4500), w("Имя,", 4500, 4800), w("напомни?", 4800, 5100))
        val output = BoundaryWordTiming.apply(input, BoundaryWordTiming.Clip(0, 8000), fresh, turns)
        assertEquals(input.single().text, output.single().text)
        assertEquals(input.single().words!!.map { it.text }, output.single().words!!.map { it.text })
        assertEquals(4500L, output.single().words!![1].startMs)
        assertEquals(1, SpeakerTimeline(turns).decide(output.single().words!![1].startMs, output.single().words!![1].endMs).speaker)
    }
    @Test fun repeatedAcknowledgementsAreNotForcedOntoAnotherOccurrence() {
        val input = segment(listOf(w("Да", 1000, 1200), w("Да", 1300, 1500)))
        val output = BoundaryWordTiming.apply(input, BoundaryWordTiming.Clip(0, 8000),
            listOf(w("Да", 4500, 4700)), turns)
        assertEquals(input.single().words!!.map { it.startMs }, output.single().words!!.map { it.startMs })
    }
    @Test fun aNewRecognitionCannotRewriteTheTranscript() {
        val input = segment(listOf(w("мой", 4100, 4300), w("исправленный", 4300, 4700), w("текст", 4700, 5000)))
        val output = BoundaryWordTiming.apply(input, BoundaryWordTiming.Clip(0, 8000),
            listOf(w("мой", 4200, 4400), w("неверный", 4400, 4800), w("текст", 4800, 5100)), turns)
        assertEquals("мой исправленный текст", output.single().text)
        assertEquals("исправленный", output.single().words!![1].text)
        assertEquals(4300L, output.single().words!![1].startMs)
    }
    @Test fun lowConfidenceOrSimultaneousSpeechCannotOverrideTiming() {
        val input = segment(listOf(w("один", 4100, 4300), w("два", 4300, 4500), w("три", 4500, 4700)))
        val fresh = listOf(w("один", 5100, 5300, .1f), w("два", 5300, 5500), w("три", 5500, 5700))
        val overlap = turns + SpeakerTimeline.Region(5000, 6000, 0)
        val output = BoundaryWordTiming.apply(input, BoundaryWordTiming.Clip(0, 8000), fresh, overlap)
        assertEquals(input.single().words!!.map { it.startMs }, output.single().words!!.map { it.startMs })
    }
    @Test fun movingOneWordCannotInvertUnchangedNeighbours() {
        val input = segment(listOf(w("один", 4100, 4300), w("два", 4300, 4500), w("три", 4500, 4700)))
        val fresh = listOf(w("один", 5100, 5300), w("два", 5300, 5500, .1f), w("три", 5500, 5700, .1f))
        val output = BoundaryWordTiming.apply(input, BoundaryWordTiming.Clip(0, 8000), fresh, turns)
        assertEquals(input.single().words!!.map { it.startMs }, output.single().words!!.map { it.startMs })
    }
    @Test fun aWordAcrossSilenceDoesNotDiscardTheWholeAlignedPhrase() {
        val voices = listOf(SpeakerTimeline.Region(0, 3000, 0), SpeakerTimeline.Region(4000, 5100, 1),
            SpeakerTimeline.Region(6500, 8000, 0))
        val input = segment(listOf(w("имя", 3100, 3500), w("вопрос", 3500, 3900),
            w("конец", 3900, 4300), w("ответ", 6200, 7000)))
        val fresh = listOf(w("имя", 4200, 4500), w("вопрос", 4500, 4900), w("конец", 4900, 6100))
        val output = BoundaryWordTiming.apply(input, BoundaryWordTiming.Clip(0, 8000), fresh, voices)
        assertEquals(4200L, output.single().words!![0].startMs)
        assertEquals(4900L, output.single().words!![2].startMs)
        assertEquals(SpeakerTimeline.UNKNOWN, SpeakerTimeline(voices).decide(4900, 6100).speaker)
        assertEquals(input.single().text, output.single().text)
    }

    @Test fun aHyphenatedLocalDecodeCanAlignSeparateSavedWords() {
        val input = segment(listOf(w("какого", 3100, 3300), w("года", 3300, 3500),
            w("«Альфа", 3500, 3700), w("Бета»?", 3700, 3900), w("ответ", 6000, 6300)))
        val fresh = listOf(w("какого", 4200, 4400), w("года", 4400, 4600),
            w("Альфа-Бета?", 4600, 5100), w("ответ", 6000, 6300))
        val output = BoundaryWordTiming.apply(input, BoundaryWordTiming.Clip(0, 8000), fresh, turns)
        assertEquals(4200L, output.single().words!![0].startMs)
        assertEquals(4600L, output.single().words!![2].startMs)
        assertEquals(5100L, output.single().words!![3].endMs)
        assertEquals(input.single().words!!.map { it.text }, output.single().words!!.map { it.text })
    }

    @Test fun editingOnlyOneBoundaryKeepsOtherCompletedClipsCached() {
        val voices = listOf(SpeakerTimeline.Region(0, 4000, 0), SpeakerTimeline.Region(4100, 20000, 1),
            SpeakerTimeline.Region(20100, 30000, 0))
        val input = segment(List(60) { w("слово$it", it * 500L, it * 500L + 400).let {
            it.copy(boundaryPassVersion = BoundaryWordTiming.VERSION, boundaryPassText = it.text)
        } })
        assertTrue(BoundaryWordTiming.plan(input, voices, 30000).isEmpty())
        val edited = input.map { s -> s.copy(words = s.words!!.mapIndexed { i, word ->
            if (i == 9) word.copy(text = "исправлено") else word
        }) }
        val clips = BoundaryWordTiming.plan(edited, voices, 30000)
        assertEquals(1, clips.size)
        assertTrue(clips.single().endMs < 10000)
    }

}
