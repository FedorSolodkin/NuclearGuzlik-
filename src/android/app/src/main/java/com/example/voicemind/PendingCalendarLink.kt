package com.example.voicemind

/**
 * Одноразовая «заявка» на привязку следующей записи к конкретному событию календаря.
 *
 * Ставится TodayFragment, когда пользователь явно нажимает «Записать голос»
 * на карточке события. Забирается RecordStorage.addToToday при сохранении
 * первой же записи. TTL защищает от «залипания» — если пользователь передумал
 * и записал что-то другое через час.
 *
 * Приоритет выше, чем у CalendarLinker (тот угадывает по времени и остаётся
 * fallback'ом для случая, когда пользователь начал запись без явного выбора).
 */
object PendingCalendarLink {
    private const val TTL_MS = 30L * 60 * 1000  // 30 минут

    @Volatile private var eventId: String? = null
    @Volatile private var setAtMs: Long = 0L

    fun set(id: String) {
        eventId = id
        setAtMs = System.currentTimeMillis()
    }

    /** Возвращает ID и сбрасывает состояние. null если пусто или протухло. */
    fun consume(): String? {
        val e = eventId ?: return null
        val fresh = System.currentTimeMillis() - setAtMs <= TTL_MS
        eventId = null
        setAtMs = 0L
        return if (fresh) e else null
    }
}
