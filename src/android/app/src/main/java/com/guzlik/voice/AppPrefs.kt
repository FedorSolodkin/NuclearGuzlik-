package com.guzlik.voice

import android.app.ActivityManager
import android.content.Context

object AppPrefs {
    private const val FILE = "guzlikvoice_prefs"
    private const val KEY_WHISPER_MODEL     = "whisper_model"
    private const val KEY_SELECTED_TEMPLATE = "selected_template"
    private const val KEY_DICTIONARY        = "custom_dictionary"
    private const val KEY_MIC_SENSITIVITY   = "mic_sensitivity_threshold"
    private const val KEY_MIC_AUTO          = "mic_mode_auto"
    private const val KEY_WHISPER_GPU       = "whisper_use_gpu"
    private const val KEY_WHISPER_BEAM      = "whisper_beam_search"
    private const val KEY_ASR_LANGUAGE      = "asr_language"
    private const val KEY_WHISPER_BENCH     = "whisper_bench_winner_"   // + имя файла модели
    private const val KEY_LLM_GPU           = "llm_use_gpu"
    private const val KEY_LLM_BENCH         = "llm_bench_winner"     // + ":" + id модели (кроме Gemma)
    private const val KEY_LLM_MODEL         = "llm_model_id"

    const val BACKEND_GPU = "gpu"
    const val BACKEND_CPU = "cpu"

    const val MIC_SENSITIVITY_DEFAULT = 150f
    // Три режима вместо слайдера; значения ложатся на ту же шкалу, что и старый
    // слайдер (30..400), поэтому ранее сохранённые настройки продолжают работать.
    const val MIC_MODE_QUIET  = 60f    // → порог VAD ~0.35: ловит тихую и дальнюю речь
    const val MIC_MODE_NORMAL = 150f   // → 0.5, стандарт Silero
    const val MIC_MODE_NOISY  = 330f   // → ~0.64: меньше ложной «речи» из шума
    const val MIC_SENSITIVITY_MIN     = 30f
    const val MIC_SENSITIVITY_MAX     = 400f

    // Язык речи для Whisper. AUTO — определять по каждому куску записи (только ru/en).
    const val LANG_AUTO = "auto"
    const val LANG_RU   = "ru"
    const val LANG_EN   = "en"

    const val MODEL_TINY     = "tiny"
    /** Точная модель — сейчас podlodka-turbo (Whisper-large-v3-turbo fine-tuned на русском).
     *  Legacy prefs со значением "small" (v3.4 whisper-small.bin) мигрируют сюда автоматически
     *  в [getWhisperModel]. */
    const val MODEL_ACCURATE = "accurate"

    /** Модель по умолчанию, пока пользователь не выбрал сам: tiny на <4 ГБ ОЗУ, иначе — точная. */
    private fun defaultModel(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return MODEL_ACCURATE
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
        return if (totalGb < 4.0) MODEL_TINY else MODEL_ACCURATE
    }

    fun getWhisperModel(ctx: Context): String {
        if (BuildConfig.CONTEST_BUILD) return MODEL_ACCURATE
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WHISPER_MODEL, null)
        // Legacy: до v3.5 "точная" модель хранилась как "small" (whisper-small.bin).
        // Сейчас та же семантика — но конкретная модель podlodka-turbo.
        return when (stored) {
            null    -> defaultModel(ctx)
            "small" -> MODEL_ACCURATE
            else    -> stored
        }
    }

    fun setWhisperModel(ctx: Context, model: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_WHISPER_MODEL, if (BuildConfig.CONTEST_BUILD) MODEL_ACCURATE else model).apply()

    /** Возвращает id выбранного шаблона обработки или null для свободной записи. */
    fun getSelectedTemplateId(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_TEMPLATE, null)

    fun setSelectedTemplateId(ctx: Context, templateId: String?) {
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (templateId == null) e.remove(KEY_SELECTED_TEMPLATE) else e.putString(KEY_SELECTED_TEMPLATE, templateId)
        e.apply()
    }

    /**
     * «Мой словарь» — имена и редкие слова пользователя через запятую. Подставляются
     * в подсказку Whisper. Заменил тематические наборы терминов: замер на 100 фразах
     * Golos показал, что общий медицинский словарь на обычной речи ни помогает, ни
     * мешает (13.0% vs 13.4% WER), а ошибается Whisper в основном на именах.
     */
    fun getCustomDictionary(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_DICTIONARY, "") ?: ""

    fun setCustomDictionary(ctx: Context, value: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_DICTIONARY, value).apply()

    /**
     * Чувствительность микрофона (шкала слайдера 30..400, меньше — чувствительнее).
     * Раньше это был порог энергетического гейта перед Whisper; теперь тишину
     * отсекает Silero VAD внутри whisper.cpp, и значение переводится в его порог
     * вероятности речи через [getVadThreshold].
     */
    fun getMicSensitivity(ctx: Context): Float =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_MIC_SENSITIVITY, MIC_SENSITIVITY_DEFAULT)

    fun setMicSensitivity(ctx: Context, value: Float) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_MIC_SENSITIVITY, value).apply()

    /**
     * Разрешать whisper.cpp использовать GPU (Vulkan) при инициализации модели.
     * На флагманах даёт 2-5× ускорение расшифровки, но на слабых Mali/старых
     * Adreno может быть либо медленнее CPU, либо крашить shader-компилятор.
     * По умолчанию — включено; native слой сам делает fallback на CPU, если
     * GPU init провалился (см. whisper_jni.cpp).
     */
    fun isWhisperGpuEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_WHISPER_GPU, true)

    fun setWhisperGpuEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WHISPER_GPU, enabled).apply()

    /**
     * «Авто»: порог VAD подбирается для каждого куска записи по его фону (native,
     * auto_vad.h; исследование — tools/vad_bench). По умолчанию включён. Тем, кто
     * раньше сам выбрал «Тихо» или «Шумно», оставляем их ручной режим.
     */
    fun isMicAuto(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.contains(KEY_MIC_AUTO)) return p.getBoolean(KEY_MIC_AUTO, true)
        return p.getFloat(KEY_MIC_SENSITIVITY, MIC_SENSITIVITY_DEFAULT) == MIC_SENSITIVITY_DEFAULT
    }

    fun setMicAuto(ctx: Context, auto: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_MIC_AUTO, auto).apply()

    /** Значение порога «подобрать автоматически» для native (vadThreshold < 0). */
    const val VAD_THRESHOLD_AUTO = -1f

    /**
     * Порог Silero VAD (0.3..0.7) из ручного режима чувствительности; 150 (дефолт) → 0.5.
     * В режиме «Авто» — [VAD_THRESHOLD_AUTO].
     */
    fun getVadThreshold(ctx: Context): Float {
        if (isMicAuto(ctx)) return VAD_THRESHOLD_AUTO
        val v = getMicSensitivity(ctx).coerceIn(MIC_SENSITIVITY_MIN, MIC_SENSITIVITY_MAX)
        return if (v <= MIC_SENSITIVITY_DEFAULT)
            0.3f + (v - MIC_SENSITIVITY_MIN) / (MIC_SENSITIVITY_DEFAULT - MIC_SENSITIVITY_MIN) * 0.2f
        else
            0.5f + (v - MIC_SENSITIVITY_DEFAULT) / (MIC_SENSITIVITY_MAX - MIC_SENSITIVITY_DEFAULT) * 0.2f
    }

    /**
     * Быстрый ASR (GigaAM v3 через sherpa-onnx) вместо Whisper. По умолчанию включён;
     * для сравнительных замеров Whisper: -PguzlikvoiceFastAsr=false.
     * Для замеров: наличие файла filesDir/use_gigaam включает его без пересборки (не в конкурсной сборке).
     */
    fun isFastAsr(ctx: Context): Boolean {
        if (BuildConfig.FAST_ASR) return true
        if (BuildConfig.CONTEST_BUILD) return false
        if (java.io.File(ctx.filesDir, "use_gigaam").exists()) return true
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("fast_asr", false)
    }

    /** Beam search в Whisper: точнее на неразборчивой речи, но медленнее. */
    fun isWhisperBeamSearch(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_WHISPER_BEAM, false)

    fun setWhisperBeamSearch(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WHISPER_BEAM, enabled).apply()

    fun getAsrLanguage(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ASR_LANGUAGE, LANG_AUTO) ?: LANG_AUTO

    fun setAsrLanguage(ctx: Context, lang: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ASR_LANGUAGE, lang).apply()

    /** Результат замера GPU vs CPU для конкретной модели Whisper (null — не мерили). */
    fun getWhisperBenchWinner(ctx: Context, modelFile: String): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_WHISPER_BENCH + modelFile, null)

    fun setWhisperBenchWinner(ctx: Context, modelFile: String, winner: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_WHISPER_BENCH + modelFile, winner).apply()

    /** Разрешать LLM использовать GPU (Vulkan). Итоговый выбор — по замеру [getLlmBenchWinner]. */
    fun isLlmGpuEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LLM_GPU, true)

    fun setLlmGpuEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LLM_GPU, enabled).apply()

    // Замер GPU/CPU — свой у каждой модели. Ключ Gemma прежний, чтобы не
    // перемерять у тех, кто обновился.
    private fun llmBenchKey(modelId: String) =
        if (modelId == LlmModels.GEMMA.id) KEY_LLM_BENCH else "$KEY_LLM_BENCH:$modelId"

    fun getLlmBenchWinner(ctx: Context, modelId: String): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(llmBenchKey(modelId), null)

    fun setLlmBenchWinner(ctx: Context, modelId: String, winner: String?) {
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (winner == null) e.remove(llmBenchKey(modelId)) else e.putString(llmBenchKey(modelId), winner)
        e.apply()
    }

    /** Выбранная языковая модель ([LlmModels]); null — по умолчанию (Gemma). */
    fun getLlmModelId(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LLM_MODEL, null)

    fun setLlmModelId(ctx: Context, id: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_LLM_MODEL, id).apply()

    /** Потоки ИИ, подобранные «Тестом скорости»: (генерация, промпт); (0, 0) — автоматически. */
    fun getLlmThreads(ctx: Context): Pair<Int, Int> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getInt("llm_threads_gen", 0) to p.getInt("llm_threads_batch", 0)
    }

    fun setLlmThreads(ctx: Context, gen: Int, batch: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("llm_threads_gen", gen).putInt("llm_threads_batch", batch).apply()
    }

    /** Сбросить замеры скорости (после смены модели/обновления драйвера). */
    fun clearBenchResults(ctx: Context) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_WHISPER_BENCH) || it.startsWith(KEY_LLM_BENCH) }.forEach { e.remove(it) }
        e.apply()
    }

    // ── Страховка от падения GPU-драйвера ──────────────────────────────────────
    // Падение внутри драйвера Vulkan (SIGSEGV) не поймать из кода — процесс просто
    // умирает, и при автоперезапуске всё повторяется по кругу. Поэтому перед
    // попыткой GPU синхронно ставим флаг и снимаем его после. Если при запуске
    // флаг уже стоит — прошлая попытка убила процесс, и GPU больше не пробуем.
    private const val KEY_GPU_ATTEMPT = "gpu_attempt_in_progress_"

    /** true — прошлая попытка GPU для [who] не завершилась (процесс упал). */
    fun gpuAttemptCrashed(ctx: Context, who: String): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_GPU_ATTEMPT + who, false)

    fun markGpuAttempt(ctx: Context, who: String, inProgress: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GPU_ATTEMPT + who, inProgress).commit()
    }
}
