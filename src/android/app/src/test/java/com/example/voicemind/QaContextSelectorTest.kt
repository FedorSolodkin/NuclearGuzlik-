package com.example.voicemind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaContextSelectorTest {

    private fun filler(n: Int, topic: String) =
        (1..n).joinToString(" ") { "Обсуждали $topic, пункт номер $it, ничего конкретного не решили." }

    @Test fun `short text is passed whole`() {
        val sel = QaContextSelector.select("Короткая запись.", "что решили?", 1000)
        assertTrue(sel.complete)
        assertEquals("Короткая запись.", sel.text)
    }

    @Test fun `fact from the middle of a long record is kept`() {
        val middle = "Бюджет проекта утвердили в размере двух миллионов рублей."
        val text = filler(80, "погоду") + " " + middle + " " + filler(80, "отпуск")
        val sel = QaContextSelector.select(text, "Какой бюджет утвердили?", 3000)
        assertFalse(sel.complete)
        assertTrue("middle fact must survive", sel.text.contains("двух миллионов"))
        assertTrue(sel.text.length <= 3000)
    }

    @Test fun `selected pieces stay in original order`() {
        val text = "Сначала говорили про бюджет проекта. " + filler(60, "погоду") +
            " Потом снова вернулись к бюджету и сократили его."
        val sel = QaContextSelector.select(text, "бюджет", 1800)
        assertFalse(sel.complete)
        val first = sel.text.indexOf("Сначала")
        val last = sel.text.indexOf("сократили")
        assertTrue(first in 0 until last)
    }

    @Test fun `pieces cover all text without loss`() {
        val text = filler(30, "сроки")
        val joined = QaContextSelector.pieces(text).joinToString(" ")
        assertEquals(text.replace(Regex("\\s+"), " "), joined)
    }
}
