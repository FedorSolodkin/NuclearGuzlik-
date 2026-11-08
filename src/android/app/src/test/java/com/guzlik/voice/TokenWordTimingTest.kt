package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class TokenWordTimingTest {
    @Test fun literalSpaceTokensProduceSeparateWordsWithTheirOwnClocks() {
        val segment = TokenWordTiming.build("Привет мир!", arrayOf("При", "вет", " ", "мир", "!"),
            floatArrayOf(.1f, .2f, .3f, .5f, .6f), 1000, 3000)!!
        assertEquals(listOf("Привет", "мир!"), segment.words!!.map { it.text })
        assertEquals(listOf(1100L, 1500L), segment.words!!.map { it.startMs })
        assertEquals("Привет мир!", segment.text)
    }
    @Test fun sentencePieceAndLeadingSpaceTokensAlsoWork() {
        for (tokens in listOf(arrayOf("▁Привет", "▁мир", "!"), arrayOf(" Привет", " мир", "!"))) {
            val segment = TokenWordTiming.build("Привет мир!", tokens, floatArrayOf(.1f, .5f, .6f), 0, 1000)!!
            assertEquals(listOf("Привет", "мир!"), segment.words!!.map { it.text })
        }
    }
    @Test fun quotedWordsAndDialogueDashesKeepCanonicalPunctuation() {
        val text = "— «Да!» — Нет."
        val segment = TokenWordTiming.build(text, arrayOf("— ", "«Да!»", " — ", "Нет."),
            floatArrayOf(0f, .2f, .5f, .8f), 0, 1000)!!
        assertEquals(text, segment.text)
        assertEquals(text, segment.words!!.joinToString(" ") { it.text })
        assertEquals(200L, segment.words!!.first().startMs)
    }
    @Test fun invalidOrPostprocessedTokenStreamsRemainExplicitlyCoarse() {
        assertNull(TokenWordTiming.build("243", arrayOf("двести", "сорок", "три"),
            floatArrayOf(.1f, .2f, .3f), 0, 1000)!!.words)
        assertNull(TokenWordTiming.build("Я", arrayOf("Я"), floatArrayOf(Float.NaN), 0, 1000)!!.words)
    }
    @Test fun lastWordNeverExtendsBeyondAudioEvenForLateTokenTimes() {
        val result = TokenWordTiming.build("Да", arrayOf("Да"), floatArrayOf(2f), 0, 1000)!!
        assertEquals(999L, result.words!!.single().startMs)
        assertEquals(1000L, result.words!!.single().endMs)
    }
}
