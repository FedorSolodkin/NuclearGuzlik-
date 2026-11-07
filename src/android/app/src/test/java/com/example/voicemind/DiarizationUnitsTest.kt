package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class DiarizationUnitsTest {
    private fun timed() = WhisperHelper.Segment("раз два", 0, 1000,
        listOf(WhisperHelper.Word("раз",0,400,1f),WhisperHelper.Word("два",500,1000,1f)))
    @Test fun oneLegacyPhraseDoesNotDisableAllOtherWordTimings() {
        val legacy = WhisperHelper.Segment("старая фраза",1100,2000)
        assertTrue(DiarizationUnits.canReuse(listOf(timed(),legacy)))
        val got = DiarizationUnits.expand(listOf(timed(),legacy))!!
        assertEquals(listOf("раз","два","старая фраза"),got.map { it.text })
        assertEquals(listOf(0L,500L,1100L),got.map { it.startMs })
    }
    @Test fun preservesLegacyOnlyInputWithoutInventingWordTimes() {
        assertNull(DiarizationUnits.expand(listOf(WhisperHelper.Segment("фраза",0,1000))))
        assertNull(DiarizationUnits.expand(emptyList()))
        assertFalse(DiarizationUnits.canReuse(emptyList()))
        assertFalse(DiarizationUnits.canReuse(listOf(WhisperHelper.Segment("фраза",0,1000))))
    }
    @Test fun corruptWordTimelineFallsBackOnlyForThatPhrase() {
        val broken = WhisperHelper.Segment("не терять",1100,2000,
            listOf(WhisperHelper.Word("не терять",2000,1100,1f)))
        val got = DiarizationUnits.expand(listOf(timed(),broken))!!
        assertEquals(3,got.size)
        assertEquals("не терять",got.last().text)
        assertEquals(1100L,got.last().startMs)
    }
}
