package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test

class TranscriptTimingTest {
    @Test fun speakerHeadersAndManualEditsAreNotReplacedByRawSegments() {
        val text="[Говорящий Анна]\nИсправленный ответ. Продолжение разговора."
        val segments=listOf(WhisperHelper.Segment("Старый ответ. Продолжение разговора.",1000,4000))
        val marks=TranscriptTiming.align(text,segments)
        assertTrue(marks.isNotEmpty())
        assertEquals(listOf("ответ","Продолжение","разговора"),marks.map{text.substring(it.start,it.end)})
        assertTrue(marks.all{it.start>text.indexOf(']') && it.startMs==1000L})
    }
    @Test fun repeatedWordsKeepTheirOwnTimes() {
        val segments=listOf(WhisperHelper.Segment("Да. Да.",0,5000,listOf(
            WhisperHelper.Word("Да.",100,500,.9f),WhisperHelper.Word("Да.",4000,4500,.9f))))
        assertEquals(listOf(100L,4000L),TranscriptTiming.align("[Говорящий Да]\nДа. Да.",segments).map{it.startMs})
    }
    @Test fun deletedWordsDoNotCreateLinksInSpeakerNames() {
        assertTrue(TranscriptTiming.align("[Говорящий Анна]\nИсправлено.",listOf(WhisperHelper.Segment("Анна сказала.",0,1000))).isEmpty())
    }
}
