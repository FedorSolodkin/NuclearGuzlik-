package com.guzlik.voice

/**
 * Событие календаря (нормализованный вид: Google, CalDAV — всё сюда).
 * Хранится в кэше 24 часа. Никогда не отправляется наружу.
 */
data class CalendarEvent(
    val id:          String,          // provider-native ID (для дедупликации)
    val title:       String,
    val startMillis: Long,
    val endMillis:   Long,
    val location:    String? = null,
    val description: String? = null,
    val organizer:   String? = null,
    val attendees:   List<String> = emptyList(),
    val provider:    String  = "google", // "google" | "yandex"
    val calendarId:  String  = "primary"
)
