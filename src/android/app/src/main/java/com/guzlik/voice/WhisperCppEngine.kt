package com.guzlik.voice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * whisper.cpp через JNI — единственный сейчас, но не обязательно навсегда,
 * ASR-движок. Абстракция вынесена в [AsrEngine] чтобы вторым бэкендом
 * позже добавить Qwen3-ASR через sherpa-onnx без переписывания вызывающего кода.
 *
 * Native-функции живут в этом классе (whisper_jni.cpp экспортирует
 * `Java_com_guzlik_voice_WhisperCppEngine_*`), а не в фасаде [WhisperHelper] —
 * так фасад не тянет System.loadLibrary в class-init'е, если пользователь
 * никогда не выберет whisper-бэкенд.
 *
 * Нативный контекст один на процесс (whisper_jni.cpp) и защищён мьютексом,
 * поэтому несколько экземпляров движка безопасны, но делят одну модель.
 */
class WhisperCppEngine(private val context: Context) : AsrEngine {

    companion object {
        private const val TAG = "WhisperCppEngine"
        init { System.loadLibrary("guzlikvoice-jni") }

        // "Точная" — Whisper large-v3-turbo, дообученный на русском подкаст-корпусе
        // podlodka (bond005/whisper-podlodka-turbo → GGML от JoaoZaokk), квант q4_0.
        const val PODLODKA_TURBO_FILE = "ggml-whisper-podlodka-turbo-q4_0.bin"
        const val PODLODKA_TURBO_URL  =
            "https://huggingface.co/JoaoZaokk/whisper-podlodka-turbo-ggml/resolve/main/ggml-whisper-podlodka-turbo-q4_0.bin"
        const val PODLODKA_TURBO_MIN_BYTES = 400_000_000L   // файл 474 МБ

        // Legacy q5_0 — если пользователь уже скачал в v3.5, продолжаем использовать.
        const val PODLODKA_TURBO_Q5_0_FILE = "ggml-whisper-podlodka-turbo-q5_0.bin"
        const val PODLODKA_TURBO_Q5_0_MIN_BYTES = 500_000_000L

        // "Быстрее" — whisper-small q5_1 (190 МБ). tiny для русского давала
        // слишком много ошибок, small в разы точнее при приемлемой скорости.
        const val SMALL_FILE = "ggml-small-q5_1.bin"
        const val SMALL_URL  = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
        const val SMALL_MIN_BYTES = 180_000_000L

        // Legacy tiny — у пользователей, скачавших «Быстрее» до v3.6.
        const val TINY_FILE = "ggml-tiny.bin"
        const val TINY_MIN_BYTES = 50_000_000L

        // Silero VAD в формате ggml — лежит в assets (885 КБ), копируется при первом init.
        private const val VAD_ASSET = "ggml-silero-v6.2.0.bin"
        private const val GPU_GUARD = "whisper"

        /**
         * Последний известный статус backend'а: true если native слой смог
         * поднять Vulkan. Виден из Settings UI для бейджа «GPU/CPU».
         */
        @Volatile var lastKnownGpuActive: Boolean = false
            private set

        internal fun setLastKnownGpuActive(v: Boolean) { lastKnownGpuActive = v }
    }

    private external fun nativeInit(modelPath: String, useGpu: Boolean, nativeLibDir: String): Boolean
    private external fun nativeTranscribe(
        pcmData: ShortArray, initialPrompt: String, carryPrompt: Boolean,
        vadModelPath: String, vadThreshold: Float, beamSize: Int, audioCtx: Int, language: String,
        cancelToken: Long,
    ): ByteArray
    private external fun nativeDetectLanguage(pcmData: ShortArray, vadModelPath: String, vadThreshold: Float): String
    private external fun nativeOpenWordTimingSession(): Long
    private external fun nativeCloseWordTimingSession(handle: Long)
    private external fun nativeRefineWordTiming(handle: Long, pcmData: ShortArray, language: String, cancelToken: Long): ByteArray
    private external fun nativeIsGpuActive(): Boolean
    private external fun nativeAbort(cancelToken: Long)
    private external fun nativeFree()

    @Volatile override var isReady = false
        private set
    override var loadedModelName = ""
        private set

    /** true если native слой поднял Vulkan-backend. Валидно после успешного [init]. */
    @Volatile var isGpuActive: Boolean = false
        private set

    private val initLock = Any()
    @Volatile private var initInFlight = false
    private var vadPath: String = ""

    private val libDir: String get() = context.applicationInfo.nativeLibraryDir

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        synchronized(initLock) {
            if (isReady) { onReady(); return }
            if (initInFlight) return
            initInFlight = true
        }
        Thread {
            try {
                val modelFile = findModel()
                if (modelFile == null) {
                    onError("Голосовой движок не найден — откройте настройки и скачайте")
                    return@Thread
                }
                vadPath = ensureVadModel()
                loadedModelName = modelFile.name
                val ok = initWithBestBackend(modelFile)
                isReady = ok
                if (ok) {
                    isGpuActive = nativeIsGpuActive()
                    setLastKnownGpuActive(isGpuActive)
                    Log.i(TAG, "Whisper backend: ${if (isGpuActive) "GPU (Vulkan)" else "CPU"}")
                    onReady()
                } else {
                    setLastKnownGpuActive(false)
                    onError("Не удалось запустить голосовой движок")
                }
            } finally {
                synchronized(initLock) { initInFlight = false }
            }
        }.start()
    }

    /**
     * На телефонах Vulkan не всегда быстрее CPU (многие Adreno/Mali медленнее
     * NEON+i8mm). Поэтому при первом запуске модели меряем прогрев на обоих
     * бэкендах и запоминаем победителя для этой модели. Прогрев — 1 с тишины без
     * VAD: время почти целиком уходит в энкодер (30-сек окно), это и есть узкое место.
     */
    private fun initWithBestBackend(modelFile: File): Boolean {
        val path = modelFile.absolutePath
        if (AppPrefs.gpuAttemptCrashed(context, GPU_GUARD)) {
            Log.e(TAG, "Previous GPU attempt crashed the app — using CPU for ${modelFile.name}")
            AppPrefs.setWhisperBenchWinner(context, modelFile.name, AppPrefs.BACKEND_CPU)
            AppPrefs.markGpuAttempt(context, GPU_GUARD, false)
        }
        // На CPU прогрев не нужен: он лишь прогоняет полное 30-сек окно энкодера
        // (на среднем телефоне — минуты) и держит модель занятой на старте.
        val cpuInit = { nativeInit(path, false, libDir) }
        if (!AppPrefs.isWhisperGpuEnabled(context)) return cpuInit()
        when (AppPrefs.getWhisperBenchWinner(context, modelFile.name)) {
            AppPrefs.BACKEND_CPU -> return cpuInit()
            AppPrefs.BACKEND_GPU -> return withGpuGuard {
                nativeInit(path, true, libDir).also { if (it) warmUp() }
            }
        }

        // Замер: всё, что трогает GPU, — под страховкой.
        var loaded = false
        val gpuMs = withGpuGuard {
            loaded = nativeInit(path, true, libDir)
            if (!loaded || !nativeIsGpuActive()) null
            else { warmUp(); warmUp() }   // первый прогон компилирует шейдеры
        }
        if (gpuMs == null) {
            // GPU непригоден (нет Vulkan, не хватает возможностей устройства).
            // Если модель при этом загрузилась — она уже на CPU, второй раз не грузим.
            AppPrefs.setWhisperBenchWinner(context, modelFile.name, AppPrefs.BACKEND_CPU)
            return loaded || cpuInit()
        }
        if (!nativeInit(path, false, libDir)) return withGpuGuard { nativeInit(path, true, libDir) }
        warmUp()
        val cpuMs = warmUp()
        val winner = if (gpuMs < cpuMs) AppPrefs.BACKEND_GPU else AppPrefs.BACKEND_CPU
        Log.i(TAG, "Backend bench for ${modelFile.name}: GPU=$gpuMs ms, CPU=$cpuMs ms → $winner")
        AppPrefs.setWhisperBenchWinner(context, modelFile.name, winner)
        return if (winner == AppPrefs.BACKEND_GPU) withGpuGuard { nativeInit(path, true, libDir).also { if (it) warmUp() } }
               else true
    }

    private inline fun <T> withGpuGuard(block: () -> T): T {
        AppPrefs.markGpuAttempt(context, GPU_GUARD, true)
        try { return block() } finally { AppPrefs.markGpuAttempt(context, GPU_GUARD, false) }
    }

    private fun warmUp(): Long {
        val t0 = System.currentTimeMillis()
        // audio_ctx=256 (~5 с окна) — для сравнения GPU/CPU этого хватает,
        // а на слабом CPU замер занимает секунды вместо минут.
        nativeTranscribe(ShortArray(16_000), "", false, "", 0.5f, 1, 256, AppPrefs.LANG_RU, 0L)
        return (System.currentTimeMillis() - t0).also { Log.i(TAG, "Warm-up: $it ms") }
    }

    override fun transcribeSegments(
        pcm: ShortArray, initialPrompt: String, carryPrompt: Boolean, useVad: Boolean, language: String,
        cancelToken: Long,
    ): List<WhisperHelper.Segment> {
        check(isReady) { "Whisper model is not ready" }
        if (pcm.isEmpty()) return emptyList()
        val beam = if (AppPrefs.isWhisperBeamSearch(context)) 4 else 1
        val raw = String(
            nativeTranscribe(
                pcm, initialPrompt, carryPrompt,
                if (useVad) vadPath else "", AppPrefs.getVadThreshold(context), beam, 0, language,
                cancelToken,
            ),
            Charsets.UTF_8
        )
        return decodeResult(raw)
    }

    internal fun decodeResult(raw: String): List<WhisperHelper.Segment> {
        NativeAsrResult.check(raw)
        return if (raw.isBlank()) emptyList() else parseSegments(raw)
    }

    inner class WordTimingSession internal constructor(private var handle: Long) : java.io.Closeable {
        @Synchronized fun refine(pcm: ShortArray, language: String, cancelToken: Long): List<WhisperHelper.Word> {
            check(handle != 0L) { "Boundary timing session is closed" }
            require(pcm.size <= 16 * 16_000)
            return decodeResult(String(nativeRefineWordTiming(handle, pcm, language, cancelToken), Charsets.UTF_8))
                .flatMap { it.words.orEmpty() }
        }
        @Synchronized override fun close() {
            if (handle != 0L) { nativeCloseWordTimingSession(handle); handle = 0L }
        }
    }

    fun openWordTimingSession(): WordTimingSession {
        check(isReady)
        return WordTimingSession(nativeOpenWordTimingSession().also {
            check(it != 0L) { "Не удалось загрузить модель уточнения границ слов" }
        })
    }

    override fun detectLanguage(pcm: ShortArray): String? {
        if (!isReady || pcm.isEmpty()) return null
        val t0 = System.currentTimeMillis()
        val lang = nativeDetectLanguage(pcm, vadPath, AppPrefs.getVadThreshold(context)).ifEmpty { null }
        Log.i(TAG, "Language: ${lang ?: "undecided"} in ${System.currentTimeMillis() - t0} ms")
        return lang
    }

    /** Разбор вывода nativeTranscribe: строки "S…" — фразы, "W…" — слова последней фразы. */
    private fun parseSegments(raw: String): List<WhisperHelper.Segment> {
        data class Pending(val text: String, val start: Long, val end: Long, val words: MutableList<WhisperHelper.Word>)
        val out = mutableListOf<WhisperHelper.Segment>()
        var cur: Pending? = null
        fun close() {
            val c = cur ?: return
            if (c.text.isNotEmpty()) out += WhisperHelper.Segment(c.text, c.start, c.end, c.words.ifEmpty { null })
            cur = null
        }
        for (line in raw.lineSequence()) {
            if (line.length < 2) continue
            when (line[0]) {
                'S' -> {
                    close()
                    val p = line.split("\t", limit = 4)
                    if (p.size < 4) continue
                    val start = p[1].toLongOrNull() ?: continue
                    val end = p[2].toLongOrNull() ?: continue
                    cur = Pending(p[3].trim(), start, end, mutableListOf())
                }
                'W' -> {
                    val p = line.split("\t", limit = 5)
                    if (p.size < 5) continue
                    val text = p[4].trim()
                    if (text.isEmpty()) continue
                    cur?.words?.add(WhisperHelper.Word(
                        text,
                        p[1].toLongOrNull() ?: continue,
                        p[2].toLongOrNull() ?: continue,
                        (p[3].toIntOrNull() ?: 1000) / 1000f,
                    ))
                }
            }
        }
        close()
        return out
    }

    override fun transcribe(pcm: ShortArray, initialPrompt: String): String =
        transcribeSegments(pcm, initialPrompt).joinToString(" ") { it.text }.trim()

    override fun abort(cancelToken: Long) = nativeAbort(cancelToken)

    override fun release() {
        if (isReady) {
            isReady = false
            nativeFree()
        }
    }

    /** Копирует VAD-модель из assets в filesDir (один раз). Пусто — VAD недоступен. */
    private fun ensureVadModel(): String {
        val dest = File(context.filesDir, VAD_ASSET)
        if (dest.exists() && dest.length() > 0) return dest.absolutePath
        return try {
            val tmp = File(context.filesDir, "$VAD_ASSET.tmp")
            context.assets.open(VAD_ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.renameTo(dest)) dest.absolutePath else ""
        } catch (e: Exception) {
            Log.e(TAG, "VAD model copy failed: ${e.message}")
            ""
        }
    }

    private fun findModel(): File? {
        if (BuildConfig.CONTEST_BUILD) return File(context.filesDir, PODLODKA_TURBO_FILE)
            .takeIf { it.isFile && it.length() > PODLODKA_TURBO_MIN_BYTES }
        // Новая q4_0 скачана — старая q5_0 (574 МБ) больше не нужна.
        val q4 = File(context.filesDir, PODLODKA_TURBO_FILE)
        val q5 = File(context.filesDir, PODLODKA_TURBO_Q5_0_FILE)
        if (q4.exists() && q4.length() > PODLODKA_TURBO_MIN_BYTES && q5.exists()) {
            if (q5.delete()) Log.i(TAG, "Removed legacy ${q5.name}")
        }
        // Сначала — модель выбранного режима; если её нет — любая доступная
        // (лучше «что-то работает», чем «пусто»).
        val preferred = when (AppPrefs.getWhisperModel(context)) {
            AppPrefs.MODEL_TINY -> listOf(SMALL_FILE to SMALL_MIN_BYTES, TINY_FILE to TINY_MIN_BYTES)
            else -> listOf(
                PODLODKA_TURBO_FILE to PODLODKA_TURBO_MIN_BYTES,
                PODLODKA_TURBO_Q5_0_FILE to PODLODKA_TURBO_Q5_0_MIN_BYTES,
            )
        }
        val fallbacks = listOf(
            PODLODKA_TURBO_FILE      to PODLODKA_TURBO_MIN_BYTES,
            PODLODKA_TURBO_Q5_0_FILE to PODLODKA_TURBO_Q5_0_MIN_BYTES,
            "ggml-medium.bin"        to 400_000_000L,
            "ggml-small.bin"         to 400_000_000L,
            SMALL_FILE               to SMALL_MIN_BYTES,
            "ggml-base.bin"          to  50_000_000L,
            TINY_FILE                to TINY_MIN_BYTES,
        )
        for ((name, minBytes) in preferred + fallbacks) {
            val f = File(context.filesDir, name)
            if (f.exists() && f.length() > minBytes) {
                if (preferred.none { it.first == name }) Log.w(TAG, "Preferred model missing; falling back to $name")
                return f
            }
        }
        return null
    }
}
