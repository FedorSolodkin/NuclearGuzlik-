package com.example.voicemind

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderGroundingTest {

    private val dialog = """
        Слушай, Тань, я так подумал а забей ты на этот китайский. Давай лучше ляжем спать пораньше.
        У меня есть много другой домашней работы, которую мне нужно сейчас сделать. Сегодня мы
        пойдём спать в 12 ночи. Массаж я тебе сделаю через 5 минут.
    """.trimIndent()

    @Test fun dropsItemsCopiedFromPromptExamples() {
        val items = listOf("Позвонить маме до пятницы", "Скинуть Сергею презентацию для клиента")
        assertEquals(emptyList<String>(), ReminderGrounding.filter(items, dialog))
    }

    @Test fun keepsItemsFromTheText() {
        val items = listOf("Сделать массаж через 5 минут", "Лечь спать в 12 ночи", "Сделать домашнюю работу")
        assertEquals(items, ReminderGrounding.filter(items, dialog))
    }

    @Test fun keepsInflectedForms() {
        val text = "Надо не забыть купить молока и записаться к стоматологу, зубы ноют."
        val items = listOf("Купить молоко", "Записаться к стоматологу")
        assertEquals(items, ReminderGrounding.filter(items, text))
    }
}
