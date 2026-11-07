package com.example.voicemind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationFilterTest {

    private fun seg(text: String) = WhisperHelper.Segment(text, 0, 1000)

    @Test
    fun `short repetition in normal speech is kept`() {
        val text = "Нет, нет, нет, нет, я так не думаю. Давайте перенесём встречу на пятницу."
        val out = HallucinationFilter.clean(listOf(seg(text)))
        assertEquals(1, out.size)
        assertEquals(text, out[0].text)
    }

    @Test
    fun `long word loop is collapsed but surrounding text survives`() {
        val text = "Итак, договорились. " + "да ".repeat(12) + "и созвонимся завтра."
        val out = HallucinationFilter.clean(listOf(seg(text)))
        assertEquals(1, out.size)
        assertEquals("Итак, договорились. да да да и созвонимся завтра.", out[0].text)
    }

    @Test
    fun `only one garbage segment is dropped, neighbours stay`() {
        val good1 = seg("Первый пункт повестки — бюджет.")
        val junk = seg("[музыка] [музыка] [музыка]")
        val good2 = seg("Второй пункт — сроки.")
        val out = HallucinationFilter.clean(listOf(good1, junk, good2))
        assertEquals(listOf(good1, good2), out)
    }

    @Test
    fun `looped segment with few unique words is garbage`() {
        assertTrue(HallucinationFilter.isGarbage("спасибо за внимание ".repeat(10)))
        assertFalse(HallucinationFilter.isGarbage("Спасибо за внимание, коллеги, до встречи в понедельник."))
    }

    @Test
    fun `collapsed segment drops stale word timings`() {
        val words = listOf(WhisperHelper.Word("да", 0, 100, 0.9f))
        val s = WhisperHelper.Segment("да ".repeat(8).trim(), 0, 1000, words)
        val out = HallucinationFilter.clean(listOf(s)).single()
        assertEquals("да да да", out.text)
        assertEquals(null, out.words)
    }

    @Test
    fun `prompt with a loop is not carried to the next chunk`() {
        assertFalse(HallucinationFilter.isSafePrompt("ну ну ну ну ну ну ну ну"))
        assertTrue(HallucinationFilter.isSafePrompt("Ну, в общем, я думаю, что это важно."))
    }

    @Test
    fun `subtitle credits are removed, real speech stays`() {
        assertTrue(HallucinationFilter.isGarbage("Субтитры сделал DimaTorzok"))
        assertTrue(HallucinationFilter.isGarbage(" Добавил субтитры DimaTorzok."))
        assertTrue(HallucinationFilter.isGarbage("Редактор субтитров А.Синецкая Корректор А.Егорова"))
        val out = HallucinationFilter.clean(listOf(seg("Встречаемся в пятницу. Субтитры сделал DimaTorzok")))
        assertEquals("Встречаемся в пятницу.", out.single().text)
    }

    @Test
    fun `outro phrase is dropped only when it is the whole segment`() {
        assertTrue(HallucinationFilter.isGarbage("Продолжение следует..."))
        assertFalse(HallucinationFilter.isGarbage("Продолжение следует в следующий вторник, обсудим бюджет."))
        assertFalse(HallucinationFilter.isGarbage("Я сделал субтитры к видео для клиента."))
    }
}
