package com.guzlik.voice

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class JsonTranscriptExporterTest {
    private fun segment(text: String, start: Long, end: Long) = WhisperHelper.Segment(text, start, end)
    private fun turns(text: String, segments: List<WhisperHelper.Segment>, labels: List<String>) =
        JsonParser.parseString(JsonTranscriptExporter.export(text, segments, labels)).asJsonObject.getAsJsonArray("transcript")

    @Test fun exactContractAndStableSpeakerIdsEvenWhenSecondPersonSpeaksFirst() {
        val result = turns("[Говорящий Б]\nДа.\n[Говорящий А]\nПривет!",
            listOf(segment("Да.", 1250, 1500), segment("Привет!", 2000, 4380)), listOf("А", "Б"))
        val first = result[0].asJsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), first.keySet())
        assertEquals("speaker_2", first["speaker_id"].asString)
        assertEquals(1250L, first["start_ms"].asLong)
        assertEquals(1500L, first["end_ms"].asLong)
        assertEquals("Да.", first["text"].asString)
        assertEquals("speaker_1", result[1].asJsonObject["speaker_id"].asString)
    }

    @Test fun repeatedShortRepliesKeepDistinctTimes() {
        val result = turns("[Говорящий А]\nДа.\n[Говорящий Б]\nДа.",
            listOf(segment("Да.",100,300), segment("Да.",900,1100)), listOf("А","Б"))
        assertEquals(100L,result[0].asJsonObject["start_ms"].asLong)
        assertEquals(900L,result[1].asJsonObject["start_ms"].asLong)
    }

    @Test fun preservesCurrentEditsAndEscapesJson() {
        val body = "Да, ответ «исправлен».\nСказал: \"готово\"."
        val result = turns("[Говорящий Анна]\n$body",
            listOf(segment("Да ответ старый сказал готово",100,1000)),listOf("Анна"))
        assertEquals(body,result[0].asJsonObject["text"].asString)
    }

    @Test fun overlapAndUncertaintyAreNotInventedPeople() {
        val result = turns("[Говорят одновременно]\nДа.\n[Говорящий не определён]\nНет.",
            listOf(segment("Да",0,500),segment("Нет",500,800)),listOf("А","Б"))
        assertEquals("overlap",result[0].asJsonObject["speaker_id"].asString)
        assertEquals("unknown",result[1].asJsonObject["speaker_id"].asString)
    }

    @Test fun oneDetectedSpeakerNeedsNoVisibleHeaders() {
        assertEquals("speaker_1",turns("Привет!",listOf(segment("Привет",0,200)),listOf("А"))[0].asJsonObject["speaker_id"].asString)
    }

    @Test(expected = IllegalArgumentException::class) fun noFakeTimingsWithoutAcousticAnchors() {
        turns("[Говорящий А]\nПолностью переписано.",listOf(segment("Старый текст",0,100)),listOf("А"))
    }
    @Test(expected = IllegalArgumentException::class) fun requiresCompletedDiarization() {
        turns("Да",listOf(segment("Да",0,100)),emptyList())
    }
    @Test(expected = IllegalArgumentException::class) fun missingSpeakerHeadersCannotCollapseSeveralPeople() {
        turns("Да",listOf(segment("Да",0,100)),listOf("А","Б"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTimesCannotLeakToSubmission() {
        turns("Да",listOf(segment("Да",-10,100)),listOf("А"))
    }

    @Test fun zeroLengthWordKeepsTextAndGetsMinimalPositiveDuration() {
        val result = turns("[Говорящий А]\nДа.\n[Говорящий Б]\nНу, привет.",
            listOf(segment("Да.", 500, 500), segment("Ну, привет.", 900, 1300)), listOf("А", "Б"))
        val first = result[0].asJsonObject
        assertEquals(500L, first["start_ms"].asLong)
        assertTrue(first["end_ms"].asLong > first["start_ms"].asLong)
        assertEquals("Да.", first["text"].asString)
        assertEquals(900L, result[1].asJsonObject["start_ms"].asLong)
    }

    @Test fun singleZeroLengthReplyDoesNotCancelTheWholeExport() {
        val result = turns("Привет", listOf(segment("Привет", 0, 0)), listOf("А"))
        assertEquals(1, result.size())
        assertTrue(result[0].asJsonObject["end_ms"].asLong > 0L)
    }

    private fun best(text: String, segs: List<WhisperHelper.Segment>?, labels: List<String>) =
        JsonTranscriptExporter.exportBestEffort(text, segs, labels)
    private fun jsonTurns(o: JsonTranscriptExporter.Outcome) =
        JsonParser.parseString(o.json).asJsonObject.getAsJsonArray("transcript")

    @Test fun bestEffortUsesStrictModeWhenPossible() {
        val o = best("Привет!", listOf(segment("Привет", 0, 200)), listOf("А"))
        assertEquals(JsonTranscriptExporter.Mode.STRICT, o.mode)
        assertNull(o.reason)
    }

    @Test fun noSpeakerLabelsStillGiveOneSpeakerWithRealTimes() {
        val o = best("Привет мир", listOf(segment("Привет мир", 300, 900)), emptyList())
        assertEquals(JsonTranscriptExporter.Mode.LENIENT, o.mode)
        val first = jsonTurns(o)[0].asJsonObject
        assertEquals("speaker_1", first["speaker_id"].asString)
        assertEquals(300L, first["start_ms"].asLong)
        assertTrue(first["end_ms"].asLong > first["start_ms"].asLong)
    }

    @Test fun rewrittenTextWithoutAcousticAnchorsStillExports() {
        val o = best("[Говорящий А]\nСовсем другие слова.", listOf(segment("Старый текст", 0, 100)), listOf("А"))
        assertEquals(JsonTranscriptExporter.Mode.LENIENT, o.mode)
        val first = jsonTurns(o)[0].asJsonObject
        assertEquals("Совсем другие слова.", first["text"].asString)
        assertTrue(first["start_ms"].asLong >= 0 && first["end_ms"].asLong > first["start_ms"].asLong)
    }

    @Test fun negativeWordTimeIsCoercedInsteadOfCancellingTheExport() {
        val o = best("Да", listOf(segment("Да", -10, 100)), listOf("А"))
        val first = jsonTurns(o)[0].asJsonObject
        assertEquals(0L, first["start_ms"].asLong)
        assertEquals("Да", first["text"].asString)
    }

    @Test fun duplicateSpeakerNamesDoNotBlockTheExport() {
        val o = best("[Говорящий А]\nДа.\n[Говорящий Б]\nНет.", listOf(segment("Да.", 0, 300), segment("Нет.", 400, 700)), listOf("А", "А"))
        assertEquals(2, jsonTurns(o).size())
    }

    @Test fun missingSegmentsStillGiveATurnWithPositiveDurationAndNeverThrow() {
        val o = best("[Говорящий А]\nПривет, мир.", null, listOf("А"))
        assertNotEquals(JsonTranscriptExporter.Mode.STRICT, o.mode)
        val first = jsonTurns(o)[0].asJsonObject
        assertEquals("Привет, мир.", first["text"].asString)
        assertTrue(first["end_ms"].asLong > first["start_ms"].asLong)
    }

    @Test fun emptyEverythingGivesAnEmptyTranscriptNotAnException() {
        val o = best("", null, emptyList())
        assertEquals(0, jsonTurns(o).size())
    }
}
