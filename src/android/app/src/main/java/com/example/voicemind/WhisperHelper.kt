package com.example.voicemind

import android.content.Context

/**
 * Фасад над ASR-движком. Делегирует в [AsrEngine] (пока только [WhisperCppEngine]).
 *
 * Публичный API сохранён — его типы использует 10+ файлов (RecordFragment,
 * TranscriptionSession, TranscriptionDetailSheet, SpeakerDiarizer и т.д.), а
 * [Segment] ещё сериализуется Gson-ом в DailyRecord.entries — переименование
 * сломает загрузку старых записей.
 */
class WhisperHelper(context: Context) {

    /**
     * Фраза Whisper. Совместимость: fully-qualified имя используется в Gson-сериализации.
     * [words] — слова фразы со временем и уверенностью (null у записей до v3.8).
     */
    data class Segment(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val words: List<Word>? = null,
    ) {
        fun shifted(offsetMs: Long) = copy(
            startMs = startMs + offsetMs,
            endMs = endMs + offsetMs,
            words = words?.map { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) },
        )
    }

    /** Слово с таймкодами и уверенностью модели (минимальная вероятность его токенов, 0..1). */
    data class Word(val text: String, val startMs: Long, val endMs: Long, val p: Float,
        val boundaryPassVersion: Int = 0, val boundaryPassText: String? = null)

    companion object {
        /**
         * Готовый к работе движок процесса (его поднимает экран записи). Нативная
         * модель одна на процесс, поэтому фоновые задачи (импорт файлов) берут
         * этот экземпляр, а не создают свой: release() второго экземпляра
         * освободил бы модель и у первого.
         */
        @Volatile var active: WhisperHelper? = null
            private set

        private val nextCancelToken = java.util.concurrent.atomic.AtomicLong(0)

        // Импорты, принятые к работе, но ещё не взявшие движок (он может загружаться: смахнули приложение сразу после «Поделиться»).
        // Пока они есть, release() экрана движок не освобождает: иначе импорт дождался бы движка, который уже «освобождён».
        private val importHolds = java.util.concurrent.atomic.AtomicInteger(0)
        fun holdForImport() { importHolds.incrementAndGet() }
        fun unholdForImport() { importHolds.decrementAndGet(); active?.maybeRelease() }

        /** Новый токен отмены для [transcribeSegments]/[abort] (никогда не 0). */
        fun newCancelToken(): Long = nextCancelToken.incrementAndGet()
    }

    private val appContext = context.applicationContext
    private val engine: AsrEngine = WhisperCppEngine(context)

    val isReady: Boolean get() = engine.isReady
    val loadedModelName: String get() = engine.loadedModelName

    fun init(onReady: () -> Unit, onError: (String) -> Unit) =
        engine.init({ active = this; onReady() }, onError)

    fun transcribe(pcm: ShortArray, initialPrompt: String = ""): String =
        transcribeSegments(pcm, initialPrompt).joinToString(" ") { it.text }.trim()

    /** [language] null — как в настройках; «Авто» определяет язык по самому [pcm]. */
    fun transcribeSegments(
        pcm: ShortArray,
        initialPrompt: String = "",
        carryPrompt: Boolean = false,
        useVad: Boolean = true,
        language: String? = null,
        cancelToken: Long = 0L,
    ): List<Segment> = HallucinationFilter.clean(
        engine.transcribeSegments(pcm, initialPrompt, carryPrompt, useVad, language ?: resolveLanguage(pcm), cancelToken))

    /** "ru"/"en" из настроек; при «Авто» — определяет по [pcm] (null — речи мало). */
    fun detectLanguage(pcm: ShortArray): String? = when (AppPrefs.getAsrLanguage(appContext)) {
        AppPrefs.LANG_RU -> AppPrefs.LANG_RU
        AppPrefs.LANG_EN -> AppPrefs.LANG_EN
        else             -> engine.detectLanguage(pcm)
    }

    private fun resolveLanguage(pcm: ShortArray): String = detectLanguage(pcm) ?: AppPrefs.LANG_RU

    /** Отменяет только вызовы с этим токеном (см. [newCancelToken]). */
    fun abort(cancelToken: Long) = engine.abort(cancelToken)

    /** Local DTW pass. One temporary context for all clips; no transcript replacement. */
    fun <T> withWordTimingSession(cancelToken: Long, block: ((ShortArray) -> List<Word>) -> T): T =
        (engine as WhisperCppEngine).openWordTimingSession().use { session ->
            block { pcm -> session.refine(pcm, resolveLanguage(pcm), cancelToken) }
        }

    fun refineWordTiming(pcm: ShortArray, cancelToken: Long): List<Word> =
        withWordTimingSession(cancelToken) { refine -> refine(pcm) }

    // ── Время жизни движка ─────────────────────────────────────────────────────
    // Модель в нативном коде одна на процесс. Раньше экран записи освобождал её при
    // закрытии (смахнули приложение) — и фоновый импорт файла посреди работы терял
    // движок: остаток файла молча не распознавался. Теперь фоновые задачи «держат»
    // движок (retain/releaseUse), а release() экрана ждёт, пока они закончат.
    private val lifeLock = Any()
    private var users = 0
    private var releaseRequested = false
    private var released = false

    /** Фоновая задача начинает пользоваться движком. false — движок уже освобождён. */
    fun retain(): Boolean = synchronized(lifeLock) {
        if (released || !engine.isReady) false else { users++; true }
    }

    /** Фоновая задача закончила; если экран уже просил освободить движок — освобождаем. */
    fun releaseUse() = synchronized(lifeLock) {
        if (users > 0) users--
        if (users == 0 && releaseRequested && importHolds.get() == 0) doRelease()
    }

    /** Экран открыли заново, а движок ещё жив (ждал фоновую задачу) — берём его, а не грузим второй. */
    fun reuse(): Boolean = synchronized(lifeLock) {
        if (released || !engine.isReady) false else { releaseRequested = false; true }
    }

    fun release() = synchronized(lifeLock) {
        releaseRequested = true
        if (users > 0 || importHolds.get() > 0) {
            android.util.Log.i("WhisperHelper", "release отложен: движком пользуются фоновые задачи (${users}) или ждёт импорт (${importHolds.get()})")
            return@synchronized
        }
        doRelease()
    }

    /** Отложенный release, если ждать больше некого. */
    fun maybeRelease() = synchronized(lifeLock) {
        if (users == 0 && releaseRequested && importHolds.get() == 0) doRelease()
    }

    private fun doRelease() {
        if (released) return
        released = true
        // Нативная модель общая: если её уже перехватил новый экземпляр (active ≠ this),
        // освобождать её нельзя — освободили бы чужую.
        if (active === this) {
            active = null
            engine.release()
        }
    }
}
