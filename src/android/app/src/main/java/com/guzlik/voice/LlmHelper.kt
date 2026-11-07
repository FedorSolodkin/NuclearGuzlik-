package com.guzlik.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [spec] — какая модель грузится; по умолчанию выбранная в настройках. */
class LlmHelper(
    private val context: Context,
    val spec: LlmModels.Spec = LlmModels.selected(context),
) {

    enum class Template(val id: String, val displayName: String, val promptAsset: String) {
        MEETING     ("meeting",      "Встреча / планёрка",     "template_meeting.txt"),
        MEDICAL     ("medical",      "Медицинский приём",      "template_medical.txt"),
        CONSULTATION("consultation", "Консультация клиента",   "template_consultation.txt"),
        INTERVIEW   ("interview",    "Интервью / CustDev",     "template_interview.txt"),
        LECTURE     ("lecture",      "Лекция / обучение",      "template_lecture.txt");

        companion object {
            fun fromId(id: String?): Template? = id?.let { v -> values().firstOrNull { it.id == v } }
        }
    }

    companion object {
        private const val TAG = "LlmHelper"
        private val nativeOwnerLock = Any()
        private val releaseExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task,"GuzlikVoice-model-release").apply { isDaemon=true }
        }
        @Volatile internal var active: LlmHelper? = null
            private set
        private const val GPU_GUARD = "llm"

        // Контекст Gemma-2 — 8192 токена; native сам выбирает 4096/8192 под задачу.
        private const val MAX_CTX_TOKENS = 8192

        // Первичный лимит на ввод в шаблоны/Q&A по символам; точная подгонка —
        // по токенам через nativeTokenCount (см. fitToTokens).
        private const val MAX_INPUT_CHARS = 20_000
        private const val PROMPT_ASSET = "prompt.txt"
        private const val RESULT_KEY   = "напоминания"

        // Прижимаем модель к 4 правилам: русский, точный формат, никакого преамбула,
        // ничего не выдумывать. Gemma-2-2B слабовата на zero-shot — короткий и
        // конкретный system prompt даёт заметно более чистые ответы, чем расплывчатый
        // "будь точным помощником". Используется только для динамических промптов
        // (reduce, Q&A). Шаблонные ассеты (prompt.txt, template_*.txt) держат свой
        // собственный ===SYSTEM=== блок, специфичный для задачи.
        private const val SYSTEM_PROMPT =
            "Ты русскоязычный ассистент. Правила: (1) отвечаешь только на русском языке, " +
            "английские слова недопустимы; (2) точно соблюдаешь заданный пользователем формат, " +
            "без вступительных фраз и комментариев; (3) используешь только факты из " +
            "предоставленного текста, ничего не додумываешь; (4) если чего-то в тексте нет — " +
            "прямо об этом пишешь, а не придумываешь правдоподобное."

        // Маркеры секций в асcетах prompt.txt / template_*.txt.
        // Формат: ===SYSTEM=== / ===USER=== / ===MODEL=== на отдельной строке.
        // Раскладка: один system → чередующиеся user/model few-shot пары → финальный
        // user с плейсхолдером [ТЕКСТ_ЗАМЕТКИ] или [ТЕКСТ_ЗАПИСИ]. Gemma-2 не имеет
        // системной роли, поэтому SYSTEM склеиваем с первым USER-сообщением при
        // отправке в llama_chat_apply_template.
        private val SECTION_MARKER = Regex("(?m)^===(\\w+)===\\s*$")
        private const val PLACEHOLDER_NOTE   = "[ТЕКСТ_ЗАМЕТКИ]"
        private const val PLACEHOLDER_RECORD = "[ТЕКСТ_ЗАПИСИ]"

        // Gemma иногда вставляет английские названия дней недели несмотря на инструкцию
        private val WEEKDAY_EN_TO_RU = mapOf(
            "Monday" to "понедельник", "Tuesday" to "вторник",
            "Wednesday" to "среда",    "Thursday" to "четверг",
            "Friday" to "пятница",     "Saturday" to "суббота",
            "Sunday" to "воскресенье"
        )

        private val JUNK_STARTERS = listOf("погода", "почта")

        /**
         * GBNF грамматика для формата {"напоминания":["строка", ...]}.
         *
         * Работает на уровне сэмплера в llama.cpp: маскирует любые токены,
         * которые нарушили бы структуру. Модель физически не может выдать
         * недопустимый JSON или обвесить его текстом типа «Вот твой ответ:».
         *
         * Байтовая семантика [^"\\] корректна для UTF-8, т.к. кириллические
         * многобайтные последовательности никогда не содержат байтов " (0x22)
         * или \ (0x5C) в качестве продолжения.
         */
        private val REMINDERS_GRAMMAR = """
            root      ::= ws "{" ws "\"напоминания\"" ws ":" ws array ws "}" ws
            array     ::= "[" ws (string (ws "," ws string)*)? ws "]"
            string    ::= "\"" char* "\""
            char      ::= [^"\\] | "\\" (["\\/bfnrt] | "u" hex hex hex hex)
            hex       ::= [0-9a-fA-F]
            ws        ::= [ \t\n]*
        """.trimIndent()

        /** Reduce-фаза запускается только когда мап-этап выдал существенно
         *  больше пунктов, чем разумно — иначе один лишний вызов LLM ради
         *  косметики не окупает время. */
        private const val REDUCE_THRESHOLD = 10

        // ── Дообученная Qwen («Выделить главное») ──────────────────────────────
        // Инструкция должна совпадать с той, на которой модель обучалась, слово в слово.
        private const val SPECIALIST_INSTRUCTION =
            "Ты обрабатываешь фрагмент голосового дневника. Извлеки только конкретные " +
            "задачи, встречи, звонки, покупки, обещания и дедлайны — то, что человек " +
            "забудет, если не запишет. Не включай общие мысли, эмоции, пересказ " +
            "прошедших событий без действия впереди и бытовые мелочи. Ответ — строго " +
            "JSON {\"напоминания\":[\"пункт\", ...]}, пункты — короткие фразы с " +
            "глагола в повелительном наклонении, на русском, с сохранением срока, " +
            "если он был. Если важного нет — {\"напоминания\":[]}."
        // Тексты до ~2000 симв модель видела при обучении целиком.
        private const val SPECIALIST_CHUNK = 2000
        // Стресс-тест (4248 прогонов): принятые ответы не длиннее 277 токенов.
        private const val SPECIALIST_MAX_NEW = 350
        // Без штрафа за повторы F1 0.72 (со штрафом 0.53); штраф — только для повтора битого ответа.
        private const val SPECIALIST_PENALTY = 1.0f
        private const val RETRY_PENALTY      = 1.15f
        private const val MAX_ITEMS_PER_CHUNK = 25
        // Причины остановки — первый байт ответа nativeInferRaw (см. llama_jni.cpp).
        private const val STOP_EOG   = 0
        private const val STOP_LIMIT = 1
        private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")

        const val SPECIALIST_NO_QA =
            "Выбранная модель умеет только выделять главное. Для вопросов к записи " +
            "выберите в настройках Gemma 2."

        const val PARTIAL_CONTEXT_NOTE =
            "ℹ️ Запись длинная — ответ дан по наиболее подходящим к вопросу фрагментам."

        init { System.loadLibrary("guzlikvoice-llm") }
    }

    /** Получает кусочки ответа (UTF-8, целые символы) по мере генерации. */
    fun interface PieceListener { fun onPiece(bytes: ByteArray) }

    private external fun nativeInit(modelPath: String, useGpu: Boolean, nativeLibDir: String): Boolean
    /**
     * Chat inference: chat template из GGUF metadata (для Gemma-2 это
     * <start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n) + few-shot как
     * реальные user/assistant пары. grammar="" отключает грамматическое семплирование.
     * roles/contents должны быть параллельны, длина > 0. contents и результат — UTF-8.
     */
    private external fun nativeInferChat(
        roles: Array<String>,
        contents: Array<ByteArray>,
        maxNewTokens: Int,
        grammar: String,
        listener: PieceListener?,
    ): ByteArray
    /** Промпт уже размечен; первый байт ответа — причина остановки (STOP_*). */
    private external fun nativeInferRaw(prompt: ByteArray, maxNewTokens: Int, repeatPenalty: Float): ByteArray
    private external fun nativeTokenCount(text: ByteArray): Int
    private external fun nativeIsGpuActive(): Boolean
    private external fun nativeCancel()
    private external fun nativeBench(nPrompt: Int, nGen: Int): FloatArray
    private external fun nativeBenchThreads(nPrompt: Int, nGen: Int, nThreads: Int, nThreadsBatch: Int): FloatArray
    private external fun nativeSystemInfo(): String
    private external fun nativeSetThreads(gen: Int, batch: Int)
    private external fun nativeLastStats(): FloatArray
    private external fun nativeResetCache()
    private external fun nativeSetLazyGrammar(enabled: Boolean)
    private external fun nativeFree()

    /** «Тест скорости» подписывается сюда, чтобы получать тайминги этапов «Выделить главное». */
    @Volatile var labSink: ((String) -> Unit)? = null
    private fun note(msg: String) { Log.i(TAG, msg); labSink?.invoke("    $msg") }

    /** Для «Теста скорости»: скорости чтения запроса и ответа в последнем вызове модели. */
    private fun noteStats(what: String) {
        if (labSink == null) return
        val st = nativeLastStats()
        val newTok = st[0] - st[1]
        val pf = if (st[2] > 0) newTok / st[2] else 0f
        val gn = if (st[4] > 0) st[3] / st[4] else 0f
        labSink?.invoke("    [$what] запрос ${st[0].toInt()} ток (из кэша ${st[1].toInt()}): %.1f с, %.1f ток/с; ответ ${st[3].toInt()} ток: %.1f с, %.2f ток/с".format(st[2], pf, st[4], gn))
    }

    var isReady        = false; private set
    var loadedModelName = "";   private set
    /** true — модель работает на GPU (Vulkan). */
    var isGpuActive    = false; private set

    /** Q4-квант 2B требует ~2 ГБ RAM. На устройствах где доступно меньше ~1.5 ГБ
     *  llama.cpp либо упадёт по OOM (native crash, вся app умрёт), либо будет
     *  сильно свапить. Лучше отказаться от загрузки и вернуть false — вызывающий
     *  покажет пользователю "устройство слабое, ИИ-функции недоступны". */
    private fun hasEnoughRam(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // availMem — сколько свободно RAM в данный момент. Для Gemma порог 1.5 ГБ — с запасом,
        // чтобы KV-cache + весы + фрагментация вместились; маленькой модели хватает меньшего.
        val needed = minOf(1_500L * 1024 * 1024, spec.sizeBytes + 600L * 1024 * 1024)
        if (info.availMem < needed) {
            Log.w(TAG, "Not enough RAM for LLM: avail=${info.availMem / 1_000_000}MB, need=${needed / 1_000_000}MB, lowMem=${info.lowMemory}")
            return false
        }
        return true
    }

    private val lifetime = ModelLifetime({isReady && active===this},{releaseExecutor.execute(it)}) {
        isReady=false
        synchronized(nativeOwnerLock) {
            if(active===this) { nativeFree();active=null }
        }
    }
    internal fun retain()=lifetime.retain()
    internal fun releaseUse()=lifetime.releaseUse()
    internal fun attachOwner()=lifetime.attachOwner()
    internal fun ownsNativeModel()=active===this
    private inline fun <T> withModel(fallback:T,block:()->T):T {
        if(!retain())return fallback
        try{return synchronized(nativeOwnerLock){if(active===this)block()else fallback}}finally{releaseUse()}
    }
    private fun ownedInit(path:String,gpu:Boolean,libDir:String):Boolean = synchronized(nativeOwnerLock) {
        active=null
        val ok=nativeInit(path,gpu,libDir)
        active=if(ok)this else null
        ok
    }

    fun load(): Boolean = synchronized(nativeOwnerLock) { loadInternal() }

    private fun loadInternal(): Boolean {
        val model = findModel() ?: run { Log.i(TAG, "No LLM model found: ${spec.fileName}"); return false }
        if (!hasEnoughRam()) return false
        loadedModelName = model.name
        Log.i(TAG, "Loading ${model.name} (${model.length() / 1_000_000} MB)...")
        AppPrefs.getLlmThreads(context).let { (g, bt) -> nativeSetThreads(g, bt) }   // 0/0 = авто
        isReady = loadWithBestBackend(model.absolutePath)
        isGpuActive = isReady && nativeIsGpuActive()
        if (isReady) Log.i(TAG, "LLM ready (${if (isGpuActive) "GPU" else "CPU"})") else Log.e(TAG, "LLM load failed")
        return isReady
    }

    /**
     * На телефонах llama.cpp через Vulkan нередко медленнее CPU (особенно Adreno),
     * поэтому один раз меряем оба варианта и запоминаем победителя. Оценка —
     * время типичного вызова: ~1500 токенов промпта + ~300 токенов ответа.
     */
    private fun loadWithBestBackend(path: String): Boolean {
        val libDir = context.applicationInfo.nativeLibraryDir
        if (AppPrefs.gpuAttemptCrashed(context, GPU_GUARD)) {
            Log.e(TAG, "Previous GPU attempt crashed the app — using CPU")
            AppPrefs.setLlmBenchWinner(context, spec.id, AppPrefs.BACKEND_CPU)
            AppPrefs.markGpuAttempt(context, GPU_GUARD, false)
        }
        val cpuInit = { ownedInit(path, false, libDir) }
        if (!AppPrefs.isLlmGpuEnabled(context)) return cpuInit()
        when (AppPrefs.getLlmBenchWinner(context, spec.id)) {
            AppPrefs.BACKEND_CPU -> return cpuInit()
            AppPrefs.BACKEND_GPU -> return withGpuGuard { ownedInit(path, true, libDir) } || cpuInit()
        }
        var loaded = false
        val gpu = withGpuGuard {
            loaded = ownedInit(path, true, libDir)
            if (!loaded || !nativeIsGpuActive()) null
            else nativeBench(256, 32)
        }
        if (gpu == null) {
            // Модель уже на CPU, если загрузилась без GPU — повторно не грузим.
            AppPrefs.setLlmBenchWinner(context, spec.id, AppPrefs.BACKEND_CPU)
            return loaded || cpuInit()
        }
        if (!cpuInit()) return withGpuGuard { ownedInit(path, true, libDir) }
        val cpu = nativeBench(256, 32)
        fun cost(b: FloatArray): Double =
            if (b[0] <= 0f || b[1] <= 0f) Double.MAX_VALUE else 1500.0 / b[0] + 300.0 / b[1]
        val winner = if (cost(gpu) < cost(cpu)) AppPrefs.BACKEND_GPU else AppPrefs.BACKEND_CPU
        Log.i(TAG, "LLM bench: GPU pf=${gpu[0]} tg=${gpu[1]}, CPU pf=${cpu[0]} tg=${cpu[1]} → $winner")
        AppPrefs.setLlmBenchWinner(context, spec.id, winner)
        return if (winner == AppPrefs.BACKEND_GPU) withGpuGuard { ownedInit(path, true, libDir) } else true
    }

    /** См. [AppPrefs.markGpuAttempt]: если процесс умрёт внутри драйвера, в следующий раз — CPU. */
    private inline fun <T> withGpuGuard(block: () -> T): T {
        AppPrefs.markGpuAttempt(context, GPU_GUARD, true)
        try { return block() } finally { AppPrefs.markGpuAttempt(context, GPU_GUARD, false) }
    }

    // Отмена всей задачи, а не только текущего вызова модели: structure() идёт по
    // кускам текста, и без флага следующий кусок начинался бы сразу после отмены.
    @Volatile private var cancelRequested = false

    /** Прерывает идущую генерацию; вызвавший получит то, что успело сгенерироваться. */
    fun cancel() {
        cancelRequested = true
        if (isReady) nativeCancel()
    }

    /** Человекочитаемое имя загруженной модели для статусов в UI. */
    fun displayName(): String = "ИИ"

    /**
     * Разбивает текст на смысловые чанки (см. [SemanticChunker]), прогоняет
     * каждый через Gemma, парсит JSON-напоминания, объединяет и дедуплицирует.
     * Возвращает bullet-список или "Ничего важного." если ничего не найдено.
     */
    fun structure(rawText: String): String = withModel(rawText) { structureInternal(rawText) }

    private fun structureInternal(rawText: String): String {
        if (!isReady || rawText.isBlank()) return rawText
        if (spec.reminderSpecialist) return structureSpecialist(rawText)

        val template = try {
            parseSplitAsset(context.assets.open(PROMPT_ASSET).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prompt asset: ${e.message}")
            return rawText
        }

        val chunks = SemanticChunker.chunk(rawText)
        val t0 = System.currentTimeMillis()
        note("Structuring ${rawText.length} chars in ${chunks.size} chunks...")

        val merged = mutableListOf<String>()
        cancelRequested = false
        for ((i, chunk) in chunks.withIndex()) {
            if (cancelRequested) { Log.i(TAG, "Structuring cancelled"); break }
            val tc = System.currentTimeMillis()
            Log.i(TAG, "Chunk ${i + 1}/${chunks.size}...")
            val messages = template.buildMessages(chunk)
            val raw = runChat(messages, grammar = REMINDERS_GRAMMAR)
            noteStats("основной вызов")
            val items = extractItems(raw)?.let { ReminderGrounding.filter(ReminderPostprocess.clean(it), chunk) }
            if (items != null) merged.addAll(items)
            else Log.w(TAG, "Chunk ${i + 1}: JSON parse failed, skipping")
            note("Chunk ${i + 1} done in ${System.currentTimeMillis() - tc} ms")
        }
        if (cancelRequested) return ""

        val cleanedLocal = dedupeItems(merged.filterNot { isJunkItem(it) })
        // Map-reduce: если после локальной чистки всё ещё много пунктов,
        // прогоняем финальный «слей близкие по смыслу» — это делает LLM,
        // потому что substring-dedupe не ловит перефразы («позвонить маме» vs
        // «набрать маму до пятницы»).
        val cleaned =
            if (cleanedLocal.size > REDUCE_THRESHOLD) applyReduce(cleanedLocal)
            else cleanedLocal
        note("Gemma total: ${System.currentTimeMillis() - t0} ms, ${cleaned.size} reminders")

        return if (cleaned.isEmpty()) "Ничего важного."
               else cleaned.joinToString("\n") { "• $it" }
    }

    /**
     * Reduce-фаза map-reduce: скармливаем сырой список пунктов модели с просьбой
     * склеить дубли и перефразы и оставить самые важные. Если LLM выдаст мусор
     * (или всего 1–2 пункта — модель могла «переусердствовать»), откатываемся
     * к результату мап-этапа, чтобы не потерять данные.
     */
    private fun applyReduce(items: List<String>): List<String> {
        val listBlock = items.joinToString("\n") { "- $it" }
        val userPrompt = """
            Ниже сырой список напоминаний, собранный из разных фрагментов голосового дневника.
            Твоя задача:
            - Склей близкие по смыслу пункты (перефразы, дубли, разные формулировки одного дела).
            - Убери шум и слишком общие фразы.
            - Оставь до 15 самых важных пунктов.
            - Каждый пункт — короткая фраза в повелительном наклонении.

            Ответ — строго JSON: {"напоминания":["...","..."]}

            Сырой список:
            $listBlock

            Ответ:
        """.trimIndent()

        val tc = System.currentTimeMillis()
        val raw = runChat(singleUserMessage(userPrompt), maxNewTokens = 900, grammar = REMINDERS_GRAMMAR)
        noteStats("проход «слить дубли»")
        val reduced = extractItems(raw)
        note("Reduce phase done in ${System.currentTimeMillis() - tc} ms: ${items.size} → ${reduced?.size ?: 0}")

        if (reduced == null || reduced.size < 2) {
            Log.w(TAG, "Reduce output looks broken (size=${reduced?.size}) — keeping map result")
            return items
        }
        return dedupeItems(ReminderGrounding.filter(ReminderPostprocess.clean(reduced), items.joinToString("\n")).filterNot { isJunkItem(it) })
    }

    /**
     * Прогоняет расшифровку через выбранный профессиональный шаблон (встреча, приём и т.д.).
     * Возвращает Markdown-структурированный текст или пустую строку при ошибке.
     * Один вызов на всю запись — шаблоны требуют целостного контекста, чанки бы разорвали смысл.
     */
    fun generateFromTemplate(rawText: String, template: Template, onPartial: ((String) -> Unit)? = null): String = withModel("") { generateFromTemplateInternal(rawText,template,onPartial) }

    private fun generateFromTemplateInternal(rawText: String, template: Template, onPartial: ((String) -> Unit)? = null): String {
        if (!isReady || rawText.isBlank() || !spec.supportsQa) return ""
        val parsed = try {
            parseSplitAsset(context.assets.open(template.promptAsset).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load template ${template.promptAsset}: ${e.message}")
            return ""
        }
        // Gemma 2B работает с 8192-токенным контекстом; в русской транскрипции
        // это ≈ 24-28 тыс. символов. Даём буфер под сам шаблон и ответ — режем
        // сырой текст на 20k символов, оставляя серединный срез (обычно там суть).
        val maxNew = 1500
        val safeText = fitToTokens(rawText, maxNew) { parsed.buildMessages(it) }
        val messages = parsed.buildMessages(safeText)
        val t0 = System.currentTimeMillis()
        val result = runChat(messages, maxNewTokens = maxNew, onPartial = onPartial).trim()
        Log.i(TAG, "Template ${template.id} done in ${System.currentTimeMillis() - t0} ms, ${result.length} chars")
        return result
    }

    /**
     * Отвечает на вопрос по тексту записи. Отвечает строго из контекста —
     * если ответа в записи нет, честно говорит об этом.
     */
    fun askQuestion(rawText: String, question: String, onPartial: ((String) -> Unit)? = null): String = withModel("") { askQuestionInternal(rawText,question,onPartial) }

    private fun askQuestionInternal(rawText: String, question: String, onPartial: ((String) -> Unit)? = null): String {
        if (!isReady || rawText.isBlank() || question.isBlank()) return ""
        if (!spec.supportsQa) return SPECIALIST_NO_QA
        val maxNew = 500
        val ctx = selectQaContext(rawText, question, maxNew)
        val t0 = System.currentTimeMillis()
        val answer = runChat(singleUserMessage(qaPrompt(ctx.text, question, ctx.complete)),
            maxNewTokens = maxNew, onPartial = onPartial).trim()
        Log.i(TAG, "Q&A done in ${System.currentTimeMillis() - t0} ms, ${answer.length} chars, " +
            "pieces ${ctx.usedPieces}/${ctx.totalPieces}")
        if (answer.isEmpty() || ctx.complete) return answer
        return "$answer\n\n$PARTIAL_CONTEXT_NOTE"
    }

    /**
     * Запись целиком, если влезает в контекст; иначе — фрагменты, относящиеся к
     * вопросу ([QaContextSelector]). Запись идёт ДО вопроса: при следующем вопросе
     * к короткой записи native переиспользует KV-кэш всего общего префикса.
     */
    private fun selectQaContext(rawText: String, question: String, maxNew: Int): QaContextSelector.Selection {
        val budget = MAX_CTX_TOKENS - maxNew - 128
        fun tokens(text: String, complete: Boolean) =
            singleUserMessage(qaPrompt(text, question, complete))
                .sumOf { nativeTokenCount(it.second.toByteArray(Charsets.UTF_8)) + 8 }
        val full = tokens(rawText, true)
        if (full <= budget) return QaContextSelector.Selection(rawText, true, 1, 1)
        // Плотность символов на токен у этой записи — для перевода бюджета в символы.
        val overhead = tokens("", false)
        val charsPerToken = rawText.length.toDouble() / (full - overhead).coerceAtLeast(1)
        var maxChars = ((budget - overhead) * charsPerToken * 0.95).toInt().coerceAtLeast(1000)
        var sel = QaContextSelector.select(rawText, question, maxChars)
        repeat(5) {
            val t = tokens(sel.text, false)
            if (t <= budget) return sel
            maxChars = (maxChars * budget.toDouble() / t * 0.95).toInt().coerceAtLeast(500)
            sel = QaContextSelector.select(rawText, question, maxChars)
        }
        return sel
    }

    private fun qaPrompt(recordText: String, question: String, complete: Boolean = true): String {
        val intro = if (complete) "Прочитай запись ниже и ответь на вопрос пользователя."
        else "Запись длинная, ниже — только фрагменты, относящиеся к вопросу (пропуски отмечены […]). " +
             "Ответь на вопрос пользователя по этим фрагментам."
        return """
            $intro

            ВАЖНО:
            - Отвечай ТОЛЬКО на основе информации из записи.
            - Если в записи нет ответа на вопрос, честно ответь: "В записи нет ответа на этот вопрос."
            - Отвечай кратко и по существу, без вводных фраз.
            - Отвечай на русском языке.

            ЗАПИСЬ:
            $recordText

            ВОПРОС:
            $question

            ОТВЕТ:
        """.trimIndent()
    }

    fun release() = lifetime.releaseOwner()

    /** Одна строка замера: потоки генерации / промпта и токены в секунду. */
    data class LabRow(val gen: Int, val batch: Int, val prefill: Float, val decode: Float)

    /** Итог прогона: микро-замеры (если делались) и время полного «Выделить главное» в секундах. */
    data class LabResult(val rows: List<LabRow>, val pipelineSec: Double, val pipelineItems: Int, val text: String = "")

    /**
     * «Тест скорости» одной модели: грузит её только на процессор, греет, при [micro] меряет скорость чтения запроса
     * и ответа (потоки по умолчанию и 4/4, каждый дважды, контроль в конце), затем с чистым кэшем гонит настоящий
     * «Выделить главное» на [sample] и пишет скорости чтения запроса и ответа по каждому вызову модели.
     * [lazyGrammar] выбирает способ подбора слова в JSON-ответе: false — прежний, true — новый (lazy_grammar.h).
     * Вызывать не на главном потоке; рабочая модель должна быть выгружена (контекст один на процесс).
     */
    fun speedLab(sample: String, micro: Boolean, lazyGrammar: Boolean, emit: (String) -> Unit): LabResult? =
        synchronized(nativeOwnerLock) { speedLabInternal(sample,micro,lazyGrammar,emit) }

    private fun speedLabInternal(sample: String, micro: Boolean, lazyGrammar: Boolean, emit: (String) -> Unit): LabResult? {
        val model = findModel() ?: run { emit("Файл ${spec.fileName} не найден: скачайте/установите модель в настройках"); return null }
        val libDir = context.applicationInfo.nativeLibraryDir
        emit("==== ${spec.title}: ${model.length() / 1_000_000} МБ, только процессор ====")
        val t0 = android.os.SystemClock.elapsedRealtime()
        nativeSetThreads(0, 0)   // потоки по умолчанию, как в приложении
        if (!ownedInit(model.absolutePath, false, libDir)) { emit("Модель не загрузилась"); return null }
        isReady = true
        loadedModelName = model.name
        try {
            emit("Загрузка модели: ${(android.os.SystemClock.elapsedRealtime() - t0) / 1000.0} с")
            val cores = Runtime.getRuntime().availableProcessors()
            val def = if (cores <= 4) cores else minOf(6, cores - 2)   // как pick_n_threads() в нативном коде
            // Разогрев: первый проход читает веса с диска и «будит» ядра, его результат не считаем.
            val w = nativeBenchThreads(96, 8, def, def)
            emit("  (разогрев: промпт %.1f, генерация %.2f ток/с, не учитывается)".format(w[0], w[1]))
            val rows = mutableListOf<LabRow>()
            if (micro) {
                emit("Движок: " + nativeSystemInfo().replace('\n', ' ').trim())
                fun measure(g: Int, b: Int, tag: String) {
                    val r = nativeBenchThreads(160, 16, g, b)
                    rows += LabRow(g, b, r[0], r[1])
                    emit("  потоки %d/%d%s: промпт %.1f ток/с, генерация %.2f ток/с".format(g, b, tag, r[0], r[1]))
                }
                val alt = minOf(4, def)
                measure(def, def, ""); measure(alt, alt, ""); measure(def, def, " (повтор)"); measure(alt, alt, " (повтор)")
                measure(def, def, " (контроль в конце)")
            }
            var sec = -1.0
            var items = 0
            var resultText = ""
            if (sample.isNotBlank()) {
                nativeResetCache()   // без этого второй прогон берёт запрос из кэша первого и выглядит быстрее
                labSink = emit
                try {
                    nativeSetLazyGrammar(lazyGrammar)
                    emit("Полный прогон «Выделить главное» (${sample.length} символов), потоки $def/$def, кэш сброшен, выбор слова: ${if (lazyGrammar) "НОВЫЙ" else "прежний"}…")
                    val t1 = android.os.SystemClock.elapsedRealtime()
                    val result = structure(sample)
                    sec = (android.os.SystemClock.elapsedRealtime() - t1) / 1000.0
                    items = result.lines().count { it.startsWith("•") }
                    emit("  готово за %.1f с, пунктов: %d".format(sec, items))
                    emit("  результат: " + result.replace('\n', ' ').take(500))
                    resultText = result
                } finally {
                    labSink = null
                    nativeSetLazyGrammar(true)   // в приложении всегда новый способ
                }
            }
            return LabResult(rows, sec, items, resultText)
        } finally {
            release()
        }
    }

    // ── inference ──────────────────────────────────────────────────────────────

    /**
     * Итоговый рантайм-контейнер разобранного split-ассета. `system` — общие
     * правила задачи, `messages` — few-shot пары (role, content) + финальный user
     * с плейсхолдером под пользовательский текст.
     */
    private data class ParsedTemplate(
        val system: String,
        val messages: List<Pair<String, String>>,
    ) {
        /**
         * Готовит финальные chat-сообщения для передачи в JNI.
         *   1) Подставляет `taskText` в оба возможных плейсхолдера в ПОСЛЕДНЕМ user-turn.
         *   2) Склеивает system с первым user-сообщением (Gemma-2 не имеет системной роли).
         */
        fun buildMessages(taskText: String): List<Pair<String, String>> {
            if (messages.isEmpty()) return emptyList()
            val result = messages.toMutableList()
            val (lastRole, lastContent) = result.last()
            val substituted = lastContent
                .replace(PLACEHOLDER_NOTE, taskText)
                .replace(PLACEHOLDER_RECORD, taskText)
            result[result.lastIndex] = lastRole to substituted
            if (system.isNotBlank() && result[0].first == "user") {
                result[0] = "user" to "$system\n\n${result[0].second}"
            }
            return result
        }
    }

    /**
     * Разбирает ассет с делимитерами ===SYSTEM===/===USER===/===MODEL=== на
     * структурированный список сообщений. Порядок и парность user/model
     * определяются порядком в файле; неизвестные теги игнорируются с warning'ом.
     * Если делимитеров нет — считаем весь текст одним user-сообщением (fallback
     * для случайной работы со старым форматом до миграции ассетов).
     */
    private fun parseSplitAsset(text: String): ParsedTemplate {
        val markers = SECTION_MARKER.findAll(text).toList()
        if (markers.isEmpty()) {
            Log.w(TAG, "Asset has no ===SECTION=== markers — treating as single user turn")
            return ParsedTemplate(system = "", messages = listOf("user" to text.trim()))
        }
        var system = ""
        val messages = mutableListOf<Pair<String, String>>()
        for (i in markers.indices) {
            val m = markers[i]
            val tag = m.groupValues[1].uppercase()
            val start = m.range.last + 1
            val end = if (i + 1 < markers.size) markers[i + 1].range.first else text.length
            val content = text.substring(start, end).trim()
            when (tag) {
                "SYSTEM" -> system = content
                "USER"   -> messages.add("user" to content)
                "MODEL"  -> messages.add("assistant" to content)
                else     -> Log.w(TAG, "Unknown section tag ===$tag=== — skipping")
            }
        }
        return ParsedTemplate(system, messages)
    }

    /**
     * Одно динамическое user-сообщение с прикреплённым SYSTEM_PROMPT'ом.
     * Для reduce / Q&A, где нет заранее заданного шаблона с few-shot примерами.
     */
    private fun singleUserMessage(userText: String): List<Pair<String, String>> =
        listOf("user" to "$SYSTEM_PROMPT\n\n$userText")

    /**
     * Прогоняет messages через chat template → llama_chat_apply_template → generate.
     * grammar="" — свободное семплирование.
     */
    private fun runChat(
        messages: List<Pair<String, String>>,
        maxNewTokens: Int = 450,
        grammar: String = "",
        onPartial: ((String) -> Unit)? = null,
    ): String {
        if (!isReady || messages.isEmpty()) return ""
        val roles    = Array(messages.size) { messages[it].first }
        val contents = Array(messages.size) { messages[it].second.toByteArray(Charsets.UTF_8) }
        val listener = onPartial?.let { cb ->
            val acc = java.io.ByteArrayOutputStream()
            PieceListener { bytes -> acc.write(bytes); cb(acc.toString("UTF-8")) }
        }
        return String(nativeInferChat(roles, contents, maxNewTokens, grammar, listener), Charsets.UTF_8).trim()
    }

    /**
     * Подгоняет текст записи под контекст по ТОКЕНАМ: символьный лимит не
     * гарантирует, что prompt + ответ влезут в 8192 (для кириллицы плотность
     * токенов заметно плавает). Режем середину, пока не влезет.
     */
    private fun fitToTokens(
        rawText: String, maxNewTokens: Int, build: (String) -> List<Pair<String, String>>
    ): String {
        val budget = MAX_CTX_TOKENS - maxNewTokens - 128   // запас на разметку chat template
        var limit = minOf(rawText.length, MAX_INPUT_CHARS)
        var text = truncateForContext(rawText, limit)
        repeat(6) {
            val total = build(text).sumOf { nativeTokenCount(it.second.toByteArray(Charsets.UTF_8)) + 8 }
            if (total <= budget) return text
            limit = (limit * budget.toDouble() / total * 0.95).toInt().coerceAtLeast(1000)
            text = truncateForContext(rawText, limit)
        }
        return text
    }

    // ── дообученная модель ──────────────────────────────────────────────────────

    /** Ответ [nativeInferRaw]: текст и причина остановки. */
    private class RawGen(val text: String, val stop: Int)

    private fun inferRaw(prompt: String, maxNewTokens: Int, repeatPenalty: Float): RawGen {
        if (!isReady) return RawGen("", -1)
        val bytes = nativeInferRaw(prompt.toByteArray(Charsets.UTF_8), maxNewTokens, repeatPenalty)
        if (bytes.isEmpty()) return RawGen("", -1)
        val text = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
        // На случай если Qwen всё же начнёт «думать» — блок размышления в ответ не попадает.
        return RawGen(THINK_BLOCK.replace(text, "").trim(), bytes[0].toInt())
    }

    // Промпт в формате обучения: Qwen3 chat template с пустым блоком размышления.
    private fun specialistPrompt(chunk: String) =
        "<|im_start|>user\n$SPECIALIST_INSTRUCTION\n\n$chunk<|im_end|>\n" +
        "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    private fun structureSpecialist(rawText: String): String {
        val chunks = splitIntoChunks(rawText.trim(), SPECIALIST_CHUNK)
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "Specialist: ${rawText.length} chars in ${chunks.size} chunks")
        val merged = mutableListOf<String>()
        cancelRequested = false
        for ((i, chunk) in chunks.withIndex()) {
            if (cancelRequested) { Log.i(TAG, "Structuring cancelled"); break }
            val items = extractWithGuard(chunk)?.let { ReminderGrounding.filter(ReminderPostprocess.clean(it), chunk) }
            if (items != null) merged.addAll(items)
            else Log.w(TAG, "Specialist chunk ${i + 1}/${chunks.size}: no valid answer even after retry — skipped")
        }
        if (cancelRequested) return ""
        val cleaned = dedupeItems(merged.filterNot { isJunkItem(it) })
        Log.i(TAG, "Specialist total: ${System.currentTimeMillis() - t0} ms, ${cleaned.size} reminders")
        return if (cleaned.isEmpty()) "Ничего важного."
               else cleaned.joinToString("\n") { "• $it" }
    }

    /** Сначала без штрафа за повторы; битый ответ — один повтор со штрафом [RETRY_PENALTY]. */
    private fun extractWithGuard(chunk: String): List<String>? {
        val prompt = specialistPrompt(chunk)
        val first = inferRaw(prompt, SPECIALIST_MAX_NEW, SPECIALIST_PENALTY)
        acceptedItems(first)?.let { return it }
        // После упора в лимит токенов повтор не помогает (в стресс-тесте 0 из 4), а отмену не повторяем.
        if (first.stop == STOP_LIMIT || cancelRequested) return null
        Log.w(TAG, "Specialist answer rejected (stop=${first.stop}, ${first.text.length} chars) — retry with penalty")
        return acceptedItems(inferRaw(prompt, SPECIALIST_MAX_NEW, RETRY_PENALTY))
    }

    /**
     * Ответ принимается, только если он целостный: модель закончила сама (не упёрлась
     * в лимит и не зациклилась), JSON разобрался, пунктов не аномально много и это не
     * «эхо» одного пункта.
     */
    private fun acceptedItems(g: RawGen): List<String>? {
        if (g.stop != STOP_EOG) return null
        val items = extractItems(g.text) ?: return null
        if (items.size > MAX_ITEMS_PER_CHUNK) return null
        if (items.size >= 4 && items.map { it.lowercase().trim() }.toSet().size * 2 < items.size) return null
        return items
    }

    /** Куски до [maxChars] по границе слова — так нарезались тексты при обучении. */
    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val lastSpace = text.lastIndexOf(' ', end)
                if (lastSpace > start) end = lastSpace
            }
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) chunks.add(piece)
            start = end
        }
        return chunks
    }

    // ── pipeline helpers ────────────────────────────────────────────────────────

    /**
     * Обрезает текст под лимит контекста. Берём начало и конец записи, склеиваем
     * через маркер «[...пропущено...]» — так лучше, чем срезать хвост: и первая
     * реплика, и итоги/договорённости обычно нужны в шаблоне.
     */
    private fun truncateForContext(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val marker = "\n\n[...пропущена середина записи (${text.length - limit} симв)...]\n\n"
        val half = (limit - marker.length) / 2
        if (half <= 0) return text.take(limit)
        return text.take(half) + marker + text.takeLast(half)
    }

    /** Парсит {"напоминания":[...]} или голый [...] — Gemma иногда опускает обёртку. */
    private fun extractItems(raw: String): List<String>? {
        val objStart = raw.indexOf('{'); val objEnd = raw.lastIndexOf('}')
        if (objStart != -1 && objEnd > objStart) {
            try {
                val arr = JSONObject(raw.substring(objStart, objEnd + 1)).optJSONArray(RESULT_KEY)
                if (arr != null) return jsonArrayToList(arr)
            } catch (_: Exception) {}
        }
        val arrStart = raw.indexOf('['); val arrEnd = raw.lastIndexOf(']')
        if (arrStart != -1 && arrEnd > arrStart) {
            try { return jsonArrayToList(JSONArray(raw.substring(arrStart, arrEnd + 1))) }
            catch (_: Exception) {}
        }
        return null
    }

    private fun jsonArrayToList(arr: JSONArray): List<String> =
        (0 until arr.length()).mapNotNull { i ->
            val v = arr.optString(i, "").trim()
            if (v.isEmpty()) null else fixLeakedEnglish(v)
        }

    private fun fixLeakedEnglish(value: String): String {
        var result = value
        for ((en, ru) in WEEKDAY_EN_TO_RU) result = result.replace(en, ru, ignoreCase = true)
        return result
    }

    private fun isJunkItem(value: String): Boolean {
        val words = value.trim().split(Regex("\\s+"))
        val lower = value.lowercase()
        if (words.size == 1) return true
        if (JUNK_STARTERS.any { lower.startsWith(it) }) return true
        if (lower.contains("план на завтра") && words.size <= 4) return true
        if (lower.contains("работать надо") || lower.contains("надо работать")) return true
        return false
    }

    /** Убирает точные дубли и случаи когда короткая строка полностью входит в длинную. */
    private fun dedupeItems(items: List<String>): List<String> {
        val normalized = items.map { it.lowercase().trim().trimEnd('.', '!', '?') }
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        items.forEachIndexed { i, item ->
            val ni = normalized[i]
            if (ni in seen) return@forEachIndexed
            val subsumedByLater = items.indices.any { j ->
                j != i && normalized[j] != ni &&
                normalized[j].contains(ni) && normalized[j].length > ni.length
            }
            if (!subsumedByLater) { result.add(item); seen.add(ni) }
        }
        return result
    }

    // ── model search ────────────────────────────────────────────────────────────

    private fun findModel(): File? {
        LlmModels.findFile(context, spec)?.let { return it }
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        for (name in listOf(spec.fileName)) {
            val src = File(dl, name)
            if (src.exists() && src.length() > spec.minSize) {
                val dest = File(context.filesDir, name)
                if (!dest.exists()) {
                    Log.i(TAG, "Copying $name from Downloads...")
                    val tmp = File(context.filesDir, "$name.tmp")
                    try {
                        src.inputStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        if (!tmp.renameTo(dest)) { tmp.delete(); continue }
                    } catch (e: Exception) {
                        Log.e(TAG, "Copy failed: ${e.message}")
                        tmp.delete()
                        continue
                    }
                }
                return dest
            }
        }
        return null
    }
}
