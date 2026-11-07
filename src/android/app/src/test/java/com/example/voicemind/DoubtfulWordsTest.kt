package com.example.voicemind

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubtfulWordsTest {

    private fun w(text: String, ms: Long, p: Float = 0.9f) = WhisperHelper.Word(text, ms, ms + 300, p)

    private fun starts(marks: List<DoubtfulWords.Mark>) = marks.map { it.start }
    private fun times(marks: List<DoubtfulWords.Mark>) = marks.map { it.startMs }

    @Test fun shortWordIsNotFoundInsideOtherWords() {
        // «то» встречается внутри «что» раньше, чем как отдельное слово
        val text = "Я думаю, что это нужно сделать завтра. Потом мы встретимся, и то, что он сказал, было важно."
        val words = listOf(
            w("Я", 0), w("думаю", 1000), w("что", 2000), w("это", 3000), w("нужно", 4000), w("сделать", 5000), w("завтра", 6000),
            w("Потом", 7000), w("мы", 8000), w("встретимся", 9000), w("и", 10000), w("то", 11000, p = 0.2f), w("что", 12000),
            w("он", 13000), w("сказал", 14000), w("было", 15000), w("важно", 16000),
        )
        val marks = DoubtfulWords.align(text, words, 0.4f)
        assertEquals(listOf(text.indexOf(" то,") + 1), starts(marks))
        assertEquals(listOf(11000L), times(marks))
    }

    @Test fun repeatedWordsAreMatchedInOrder() {
        val text = "Да да, да конечно."
        val words = listOf(w("Да", 0), w("да", 500), w("да", 1000, p = 0.1f), w("конечно", 1500))
        val marks = DoubtfulWords.align(text, words, 0.4f)
        assertEquals(listOf(7), starts(marks))
        assertEquals(listOf(1000L), times(marks))
    }

    @Test fun speakerLabelsAndPunctuationDoNotBreakAlignment() {
        val text = "[Говорящий А]\nНу, я думаю, это не так.\n\n[Говорящий Б]\nДа, не так."
        val words = listOf(
            w("Ну", 0), w("я", 400), w("думаю", 800), w("это", 1200), w("не", 1600), w("так", 2000),
            w("Да", 3000), w("не", 3400, p = 0.2f), w("так", 3800),
        )
        val marks = DoubtfulWords.align(text, words, 0.4f)
        assertEquals(listOf(text.lastIndexOf("не")), starts(marks))
        assertEquals(listOf(3400L), times(marks))
    }

    @Test fun wordMissingFromTextDoesNotShiftLaterMarks() {
        // «Продолжение следует» убрал фильтр галлюцинаций, в тексте их нет
        val text = "Мы встретились в офисе. Потом он ушёл, не простившись."
        val words = listOf(
            w("Мы", 0), w("встретились", 500), w("в", 1000), w("офисе", 1500),
            w("Продолжение", 2000), w("следует", 2500),
            w("Потом", 3000), w("он", 3500), w("ушёл", 4000), w("не", 4500, p = 0.1f), w("простившись", 5000),
        )
        val marks = DoubtfulWords.align(text, words, 0.4f)
        assertEquals(listOf(text.indexOf("не простившись")), starts(marks))
        assertEquals(listOf(4500L), times(marks))
    }

    @Test fun doubtfulWordAbsentFromTextIsSkipped() {
        val text = "Мы встретились в офисе."
        val words = listOf(w("Мы", 0), w("встретились", 500), w("вчера", 1000, p = 0.1f), w("в", 1500), w("офисе", 2000))
        assertEquals(emptyList<DoubtfulWords.Mark>(), DoubtfulWords.align(text, words, 0.4f))
    }

    @Test fun alignmentRecoversAfterLongMissingStretch() {
        // из текста выкинут длинный кусок (больше окна поиска); после него метки снова должны попадать в цель
        val missing = (1..12).map { w("пропущенноеслово$it", 1000L * it) }
        val text = "Начало записи. Конец, не вернулся."
        val words = listOf(w("Начало", 0), w("записи", 300)) + missing +
            listOf(w("Конец", 20000), w("не", 20500, p = 0.1f), w("вернулся", 21000))
        val marks = DoubtfulWords.align(text, words, 0.4f)
        assertEquals(listOf(text.indexOf("не вернулся")), starts(marks))
        assertEquals(listOf(20500L), times(marks))
    }

    @Test fun confidentWordsAreNeverMarked() {
        val text = "Это очень хорошо."
        val words = listOf(w("Это", 0), w("очень", 400), w("хорошо", 800))
        assertEquals(emptyList<DoubtfulWords.Mark>(), DoubtfulWords.align(text, words, 0.4f))
    }
}
