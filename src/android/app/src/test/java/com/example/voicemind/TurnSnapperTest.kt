package com.example.voicemind

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TurnSnapperTest {

    /** Слова подряд по 300 мс с паузой [gap] мс после индексов из [pausesAfter]. */
    private fun words(texts: List<String>, pausesAfter: Map<Int, Long> = emptyMap()): List<WhisperHelper.Segment> {
        var t = 0L
        return texts.mapIndexed { i, w ->
            val s = WhisperHelper.Segment(w, t, t + 300)
            t += 300 + (pausesAfter[i] ?: 50L)
            s
        }
    }

    @Test fun boundaryMovesToPauseBetweenPhrases() {
        // «Запусти фильм в стиле научной лекции. | Можешь включить кино» — граница съехала на 2 слова влево.
        val w = words(listOf("Запусти", "фильм", "в", "стиле", "научной", "лекции.", "Можешь", "включить", "кино"),
            pausesAfter = mapOf(5 to 900L))
        val got = TurnSnapper.snap(w, intArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 1))
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 1), got)
    }

    @Test fun boundaryAlreadyAtPauseStays() {
        val w = words(listOf("раз", "два", "три.", "четыре", "пять"), pausesAfter = mapOf(2 to 800L))
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 1), TurnSnapper.snap(w, intArrayOf(0, 0, 0, 1, 1)))
    }

    @Test fun shortFlipInsidePhraseIsAbsorbed() {
        val w = words(listOf("я", "думаю", "что", "это", "очень", "важно", "сейчас"))
        assertArrayEquals(IntArray(7), TurnSnapper.snap(w, intArrayOf(0, 0, 0, 1, 0, 0, 0)))
    }

    @Test fun realShortReplyWithPausesIsKept() {
        // «…придёшь завтра? | Да. | Хорошо, тогда в десять» — короткая реплика, но она отделена паузами
        // и окружена ДРУГИМ говорящим только с одной стороны — не трогаем.
        val w = words(listOf("придёшь", "завтра?", "Да.", "Хорошо,", "тогда", "в", "десять"),
            pausesAfter = mapOf(1 to 700L, 2 to 700L))
        assertArrayEquals(intArrayOf(0, 0, 1, 2, 2, 2, 2), TurnSnapper.snap(w, intArrayOf(0, 0, 1, 2, 2, 2, 2)))
    }

    @Test fun boundaryMovesOnlyWithinNeighbouringTurns() {
        // Реплики 0 | 1 | 2; пауза и точка после «в.» — внутри реплики 1: граница 0→1 уезжает
        // туда, но не дальше начала реплики 2.
        val w = words(listOf("а", "б", "в.", "г", "д", "е"), pausesAfter = mapOf(2 to 1000L))
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 2, 2), TurnSnapper.snap(w, intArrayOf(0, 1, 1, 1, 2, 2)))
    }

    @Test fun shortReplyBetweenSameSpeakerTurnsIsKept() {
        // A: «придёшь завтра?» — B: «Да.» — A: «хорошо, тогда в десять»
        val w = words(listOf("придёшь", "завтра?", "Да.", "Хорошо,", "тогда", "в", "десять"),
            pausesAfter = mapOf(1 to 700L, 2 to 700L))
        assertArrayEquals(intArrayOf(0, 0, 1, 0, 0, 0, 0), TurnSnapper.snap(w, intArrayOf(0, 0, 1, 0, 0, 0, 0)))
    }
}
