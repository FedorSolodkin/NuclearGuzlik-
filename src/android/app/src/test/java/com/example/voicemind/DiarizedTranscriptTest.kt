package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class DiarizedTranscriptTest {
    private fun result()=SpeakerDiarizer.Result(
        "[Говорящий А]\nПервый ответ.\n\n[Говорящий Б]\nВторой ответ.",listOf("А","Б"),
        listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f)),listOf(null,null),
        units=listOf(WhisperHelper.Segment("Первый ответ.",0,1000),WhisperHelper.Segment("Второй ответ.",1000,2000)),
        speakers=intArrayOf(0,1))
    @Test fun manualAdditionSurvivesAndDoesNotAcquireInventedAudioIdentity() {
        val output=DiarizedTranscript.apply("Первый ответ. Второй ответ. Добавленная заметка!",result())
        assertTrue(output.contains("[Говорящий не определён]\nДобавленная заметка!"))
        assertTrue(output.contains("[Говорящий Б]\nВторой ответ."))
    }
    @Test fun replacementAndPunctuationArePreserved() {
        val output=DiarizedTranscript.apply("Первый ОТРЕДАКТИРОВАННЫЙ ответ?! Второй ответ!",result())
        assertTrue(output.contains("ОТРЕДАКТИРОВАННЫЙ"))
        assertTrue(output.contains("ответ?!"))
        assertTrue(output.endsWith("Второй ответ!"))
    }
    @Test fun deletedWordsAreNotResurrected() {
        val output=DiarizedTranscript.apply("Первый. Второй ответ.",result())
        assertFalse(output.contains("Первый ответ"))
        assertEquals(1,Regex("ответ").findAll(output).count())
    }
    @Test fun unchangedTextUsesTheSameFormattedDiarization() {
        assertEquals(TextFormatter.format(result().text),DiarizedTranscript.apply("Первый ответ. Второй ответ.",result()))
    }
    @Test fun oldSpeakerHeadersAreReplacedWithoutDroppingTheEditedBody() {
        val output=DiarizedTranscript.apply("[Говорящий Анна]\nПервый ответ.\n[Говорящий Борис]\nВторой ответ!",result())
        assertFalse(output.contains("Анна"))
        assertTrue(output.endsWith("Второй ответ!"))
        assertTrue(output.contains("[Говорящий А]"))
    }
    @Test fun anAmbiguousRepeatedWordAfterDeletionIsNotAssignedToTheFirstPerson() {
        val result=result().copy(text="[Говорящий А]\nДа.\n[Говорящий Б]\nДа.",
            units=listOf(WhisperHelper.Segment("Да.",0,500),WhisperHelper.Segment("Да.",1000,1500)))
        assertEquals("[Говорящий не определён]\nДа!",DiarizedTranscript.apply("Да!",result))
    }
}
