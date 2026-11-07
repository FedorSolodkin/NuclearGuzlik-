package com.example.guzlikvoice

/**
 * Абстракция над движком распознавания речи (сейчас — только [WhisperCppEngine]).
 *
 * Segment сознательно живёт в [WhisperHelper] — он сериализуется Gson-ом в
 * DailyRecord, тип завязан на fully-qualified имя, переименование сломает
 * загрузку старых записей.
 */
interface AsrEngine {
    val isReady: Boolean
    val loadedModelName: String

    fun init(onReady: () -> Unit, onError: (String) -> Unit)
    fun transcribe(pcm: ShortArray, initialPrompt: String = ""): String

    /**
     * @param carryPrompt повторять [initialPrompt] в каждом 30-сек окне (глоссарий
     *                    действует на всю запись, а не только на первые 30 с)
     * @param useVad      пропускать тишину встроенным VAD; таймкоды остаются в
     *                    шкале исходного [pcm]
     * @param language    "ru" или "en"
     * @param cancelToken отменяется через [abort] с тем же токеном; 0 — не отменяемый
     */
    fun transcribeSegments(
        pcm: ShortArray,
        initialPrompt: String = "",
        carryPrompt: Boolean = false,
        useVad: Boolean = true,
        language: String = AppPrefs.LANG_RU,
        cancelToken: Long = 0L,
    ): List<WhisperHelper.Segment>

    /**
     * Язык речи в [pcm]: "ru" или "en" (другие не рассматриваются), null — речи
     * слишком мало, чтобы решить. Проход энкодера по ~5 с речи — несколько секунд.
     */
    fun detectLanguage(pcm: ShortArray): String?

    /**
     * Отменяет расшифровки с [cancelToken]: идущую прерывает, ещё не начатые
     * сразу вернут пустой результат. Чужие вызовы (другой токен) не трогает.
     */
    fun abort(cancelToken: Long)
    fun release()
}
