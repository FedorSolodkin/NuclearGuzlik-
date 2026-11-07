package com.example.guzlikvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouGileTest {

    @Test fun tasksAreTakenOnlyFromTasksSection() {
        val protocol = """
            ## Участники
            - Говорящий А
            ## Принятые решения
            - Подписать договор до конца месяца
            ## Задачи
            - [ ] Анна: отправить договор (до среды)
            - [ ] Пётр: подготовить счёт
            ## Открытые вопросы
            - нет
        """.trimIndent()
        assertEquals(listOf("Анна: отправить договор (до среды)", "Пётр: подготовить счёт"),
            YouGile.tasksFromProtocol(protocol))
    }

    @Test fun placeholderInTasksSectionIsSkipped() {
        assertEquals(emptyList<String>(), YouGile.tasksFromProtocol("## Задачи\n- не обсуждалось\n"))
    }

    @Test fun serverAddressIsNormalized() {
        assertEquals("https://yougile.com", YouGile.normalizeServer(""))
        assertEquals("https://yougile.company.ru", YouGile.normalizeServer("yougile.company.ru/api-v2/"))
        assertEquals("https://ru.yougile.com", YouGile.normalizeServer(" https://ru.yougile.com/ "))
        assertThrows(YouGile.ApiException::class.java) { YouGile.normalizeServer("http://intranet") }
    }
}
