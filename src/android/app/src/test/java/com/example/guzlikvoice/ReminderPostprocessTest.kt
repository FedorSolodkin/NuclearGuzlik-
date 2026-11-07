package com.example.guzlikvoice

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPostprocessTest {

    @Test fun splitsGluedTasks() {
        val glued = "Созвониться с Ильёй в десять тридцать, обсудить договор поставки. " +
            "Отправить Марине счёт на сорок две тысячи пятьсот рублей. Покупать подарок маме. Записаться к стоматологу."
        assertEquals(listOf(
            "Созвониться с Ильёй в десять тридцать, обсудить договор поставки",
            "Отправить Марине счёт на сорок две тысячи пятьсот рублей",
            "Покупать подарок маме",
            "Записаться к стоматологу",
        ), ReminderPostprocess.splitMerged(glued))
    }

    @Test fun keepsSingleSentenceAndShortTails() {
        assertEquals(listOf("Купить подарок маме."), ReminderPostprocess.splitMerged("Купить подарок маме."))
        // «Срочно.» — одно слово, это не отдельная задача.
        assertEquals(listOf("Передать программистам форму. Срочно."),
            ReminderPostprocess.splitMerged("Передать программистам форму. Срочно."))
        // Сокращения без заглавной после точки не режем.
        assertEquals(listOf("Купить 2 кг. яблок до пятницы"),
            ReminderPostprocess.splitMerged("Купить 2 кг. яблок до пятницы"))
    }

    @Test fun dropsItemsWithCjk() {
        assertEquals(listOf("В десять тридцать созвониться с Ильёй"),
            ReminderPostprocess.clean(listOf("В десять тридцать созвониться с Ильёй", "До пятницы отправить счёт на奖金")))
    }
}
