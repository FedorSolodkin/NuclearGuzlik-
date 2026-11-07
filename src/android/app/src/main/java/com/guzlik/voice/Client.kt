package com.guzlik.voice

import java.util.UUID

/**
 * Клиент/пациент/собеседник — карточка для группировки записей.
 * Все поля кроме id и name — опциональные, пользователь заполняет столько, сколько нужно.
 */
data class Client(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,        // свободный текст: важные факты, контекст
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
