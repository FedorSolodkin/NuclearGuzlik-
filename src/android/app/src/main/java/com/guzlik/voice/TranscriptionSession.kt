package com.guzlik.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Потоковая расшифровка во время записи.
 *
 * Сюда идёт ВСЁ аудио с микрофона (без энергетического гейта): тишину отсекает
 * Silero VAD внутри whisper.cpp, а таймкоды сегментов остаются в шкале реальной
 * записи. Это важно для диаризации и перехода по тапу — они режут полную запись
 * по этим таймкодам.
 *
 * Аудио режется на чанки ~40-58 с по самому тихому месту (паузе между фразами),
 * без перекрытий: чанки идут встык, смещение каждого — точное число сэмплов до
 * него. Короткие чанки распознаются прямо во время записи, так что после «Стоп»
 * остаётся только хвост.
 */
class TranscriptionSession(
    private val whisper:  WhisperHelper,
    private val embedder: SpeakerEmbedder? = null,
    private val context:  Context? = null,
    private val sampleRate: Int = 16000,
    // «Мой словарь» пользователя (см. Glossary.buildPrompt). Пусто — словаря нет.
    glossaryPrompt: String = "",
    private val onChunkReady:       (chunkText: String, fullText: String) -> Unit,
    private val onProcessingChanged: (active: Boolean) -> Unit,
    // Страховка от падения процесса. Импорту файла она не нужна: исходник цел,
    // его можно расшифровать заново.
    persistWal: Boolean = true,
) {
    companion object {
        private const val TAG = "TranscriptionSession"

        // Границы чанка. 58 с — чуть меньше двух 30-сек окон энкодера Whisper,
        // чтобы не гонять почти пустое третье окно. Разрез ищем в [40, 58] с.
        private const val CHUNK_MAX_SEC = 58
        private const val CHUNK_MIN_SEC = 40
        // Окно для поиска паузы: 200 мс, шаг 20 мс.
        private const val QUIET_WIN_MS  = 200
        private const val QUIET_HOP_MS  = 20

        // Хвост предыдущего чанка как промпт следующего. Whisper берёт до 224
        // токенов промпта (берёт ПОСЛЕДНИЕ, если длиннее); ~2.4 симв/токен у
        // кириллицы → 500 симв ≈ 210 токенов.
        private const val PROMPT_MAX_CHARS = 500

        // Сырое PCM для диаризации держим до ~45 мин (~86 МБ int16).
        private const val MAX_RAW_PCM_SAMPLES = 45 * 60 * 16_000L

        private const val LEGACY_WAL_FILE = "transcription_wal.txt"
        private const val WAL_PREFIX = "transcription_wal_"

        /** WAL-файлы живых сессий этого процесса — их не считаем «упавшими». */
        private val liveWals = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * WAL-файлы прерванных сессий (процесс умер посреди записи). У каждой сессии
         * свой файл: импорт файла или вторая запись больше не перетирают и не стирают
         * страховку живой записи. Вызывающий читает, сохраняет и удаляет файл сам.
         */
        fun crashedWalFiles(context: Context): List<File> {
            val dir = context.filesDir
            val files = dir.listFiles { f ->
                (f.name == LEGACY_WAL_FILE || (f.name.startsWith(WAL_PREFIX) && f.name.endsWith(".txt")))
                    && f.absolutePath !in liveWals && f.length() > 0L
            } ?: return emptyList()
            return files.sortedBy { it.lastModified() }
        }
    }

    /**
     * Глоссарий передаём с carry_initial_prompt: whisper.cpp повторяет его в
     * каждом 30-сек окне. Без этого термины влияли только на первые ~30 с записи —
     * дальше промпт вытеснялся уже распознанным текстом.
     */
    private val glossary = glossaryPrompt.trim()

    // Стилевого промпта («Ну, в общем, я думаю…») больше нет: замер на 40 записях
    // (tools/vad_bench/exp_prompt.py) — с ним Whisper выкидывал короткие фразы и
    // склеивал соседние; WER на ближнем микрофоне 21.3% → 17.6% без него, пунктуацию
    // podlodka-turbo ставит и так. Английский пример раньше тоже выкидывал фразы.

    /**
     * Язык прошлого чанка. Язык определяется на КАЖДОМ чанке (≈3-4 с на 40-58 с
     * аудио), чтобы запись, где перешли с русского на английский, не
     * переводилась. Только из executor-потока.
     */
    private var lastLanguage: String? = null

    private val maxSamples = CHUNK_MAX_SEC * sampleRate
    private val minSamples = CHUNK_MIN_SEC * sampleRate

    private val executor = Executors.newSingleThreadExecutor()

    // Отмена этой сессии не трогает чужие расшифровки на общем контексте Whisper
    // (импорт файла во время записи) и не теряется, если чанк ещё ждёт контекст.
    private val cancelToken = WhisperHelper.newCancelToken()

    private val walFile: File? = context?.takeIf { persistWal }?.let {
        File(it.filesDir, "$WAL_PREFIX${System.currentTimeMillis()}_${System.nanoTime()}.txt")
    }?.also { liveWals.add(it.absolutePath) }

    /** Текст сохранён в историю — страховка больше не нужна. */
    fun clearWal() {
        walFile ?: return
        walFile.delete()
        File(walFile.path + ".tmp").delete()
        liveWals.remove(walFile.absolutePath)
    }

    // Буфер ещё не отправленного аудио + абсолютная позиция его начала.
    private val bufLock = Any()
    private var pending = ShortArray(maxSamples + sampleRate)
    private var pendingLen = 0
    private var pendingStartSample = 0L

    // Live preview text
    private val fullTranscript = StringBuilder()
    private val lastTail = AtomicReference("")

    // Фразы для финальной диаризации. Отдельных копий аудио на фразу не держим:
    // диаризатор режет их из полной записи сам, если понадобятся.
    private val allSegments  = Collections.synchronizedList(mutableListOf<WhisperHelper.Segment>())

    // Полная запись — только для диаризации. Без модели голосов (запись и импорт с 58e7491:
    // голоса делятся отдельной кнопкой) её не копим: на 45 минутах это ~86 МБ впустую.
    private val keepRawAudio = embedder != null
    private val rawAudio = Collections.synchronizedList(mutableListOf<ShortArray>())
    private var rawAudioSamples = 0L
    @Volatile var rawAudioCapped = false
        private set

    private val activeJobs = AtomicInteger(0)
    private val failed = AtomicInteger(0)

    /** Сколько кусков не удалось распознать (движок освобождён или ошибка) — запись неполная. */
    val failedChunks: Int get() = failed.get()
    @Volatile private var cancelled = false

    /**
     * Сколько кусков ждут распознавания или распознаются. Импорт файла декодирует быстрее,
     * чем Whisper распознаёт, и без ожидания по этому счётчику вся запись встала бы в
     * очередь кусками (~1,8 МБ на минуту) — та же нехватка памяти, только позже.
     */
    fun pendingChunks(): Int = activeJobs.get()

    /** Сессию отменили: ждать очередь больше незачем (после shutdownNow куски не доработаются). */
    val isCancelled: Boolean get() = cancelled

    /** Уже распознанный текст (для черновика записи в Истории). */
    fun currentText(): String = synchronized(fullTranscript) { fullTranscript.toString().trim() }

    /** Кадр с микрофона. Вызывается из capture-потока. */
    fun pushAudio(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        appendRaw(pcm)
        val ready: Pair<ShortArray, Long>? = synchronized(bufLock) {
            if (pendingLen + pcm.size > pending.size) {
                pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + pcm.size))
            }
            pcm.copyInto(pending, pendingLen)
            pendingLen += pcm.size
            if (pendingLen >= maxSamples) cutChunkLocked() else null
        }
        ready?.let { (chunk, start) -> enqueue(chunk, start, isFinal = false) }
    }

    private fun appendRaw(pcm: ShortArray) {
        if (!keepRawAudio) return
        if (rawAudioSamples >= MAX_RAW_PCM_SAMPLES) {
            if (!rawAudioCapped) {
                rawAudioCapped = true
                Log.w(TAG, "Raw PCM buffer hit cap (${MAX_RAW_PCM_SAMPLES / 16_000}s). " +
                          "Diarization will use only the first 45 min.")
            }
            return
        }
        rawAudio.add(pcm.copyOf())
        rawAudioSamples += pcm.size
    }

    /** Отрезает чанк по самой тихой точке в [CHUNK_MIN_SEC, CHUNK_MAX_SEC]. Под bufLock. */
    private fun cutChunkLocked(): Pair<ShortArray, Long> {
        val cut = quietestPoint(pending, minSamples, minOf(pendingLen, maxSamples))
        val chunk = pending.copyOf(cut)
        val start = pendingStartSample
        pending.copyInto(pending, 0, cut, pendingLen)
        pendingLen -= cut
        pendingStartSample += cut
        return chunk to start
    }

    /** Центр 200-мс окна с минимальной энергией в диапазоне [from, to). */
    private fun quietestPoint(pcm: ShortArray, from: Int, to: Int): Int {
        val win = QUIET_WIN_MS * sampleRate / 1000
        val hop = QUIET_HOP_MS * sampleRate / 1000
        if (to - from < win) return to
        var bestPos = to
        var bestEnergy = Double.MAX_VALUE
        var pos = from
        while (pos + win <= to) {
            var e = 0.0
            var i = pos
            while (i < pos + win) { val s = pcm[i].toDouble(); e += s * s; i += 4 }
            if (e < bestEnergy) { bestEnergy = e; bestPos = pos + win / 2 }
            pos += hop
        }
        return bestPos
    }

    /** Cancel any queued work and signal flushAndWait to return early. */
    fun cancel() {
        cancelled = true
        executor.shutdownNow()
        whisper.abort(cancelToken)
    }

    /** Stop recording, process remaining audio, run diarization, return final formatted text.
     *  [onRawReady] is called after Whisper finishes but before diarization — safe to save immediately.
     *  [onDone] also receives the raw (pre-diarization) phrase segments with timecodes (for instant
     *  tap-to-seek later) and the full diarization [SpeakerDiarizer.Result] — including per-speaker
     *  centroids — so the caller can persist a [SpeakerCentroidStorage] snapshot immediately.
     *  Null if there was nothing to diarize (segs empty). */
    fun flushAndWait(
        onRawReady: (rawText: String) -> Unit = {},
        onDone: (fullText: String, segments: List<WhisperHelper.Segment>, diarization: SpeakerDiarizer.Result?) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        val tail: Pair<ShortArray, Long>? = synchronized(bufLock) {
            if (pendingLen > sampleRate / 2) {
                val chunk = pending.copyOf(pendingLen)
                val start = pendingStartSample
                pendingStartSample += pendingLen
                pendingLen = 0
                chunk to start
            } else null
        }
        if (tail != null && !cancelled) enqueue(tail.first, tail.second, isFinal = true)
        executor.shutdown()
        // Poll instead of blocking for 30 min so cancel() takes effect promptly.
        while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
            if (cancelled) { executor.shutdownNow(); break }
        }

        if (cancelled) {
            onDone("", emptyList(), null)
            return
        }

        val whisperDone = System.currentTimeMillis()
        val rawText = synchronized(fullTranscript) { fullTranscript.toString().trim() }
        Log.i(TAG, "Whisper tail done in ${whisperDone - t0} ms, ${rawText.length} chars, ${allSegments.size} segments")
        onRawReady(rawText)

        var segs = allSegments.toList()

        // Склеиваем кадры, отпуская каждый сразу после копирования: на пике в
        // памяти ~одна копия записи: на 45 мин 86 МБ, а раньше (кадры + склейка +
        // копии фраз) — до ~250 МБ.
        // Без модели голосов кадров нет (keepRawAudio = false) — массив выйдет пустым.
        val chunks = synchronized(rawAudio) { rawAudio.toTypedArray<ShortArray?>().also { rawAudio.clear() } }
        val fullPcm = ShortArray(chunks.sumOf { it?.size ?: 0 })
        var pos = 0
        for (i in chunks.indices) {
            val c = chunks[i] ?: continue
            c.copyInto(fullPcm, pos)
            pos += c.size
            chunks[i] = null
        }

        // Без модели голосов (embedder == null) не делим ничего: раньше диаризатор в этом случае молча расставлял
        // «говорящих» по паузам, и запись сразу получала неверные метки.
        var diarization = if (segs.isNotEmpty() && !cancelled && embedder != null) {
            SpeakerDiarizer.diarize(segs, emptyMap(), embedder, fullPcm, context, isCancelled = { cancelled })
        } else null
        val base = diarization
        if (base != null && base.centroids.size > 1 && !cancelled) {
            val clips = BoundaryWordTiming.plan(segs, base.turns, fullPcm.size * 1000L / 16_000)
            if (clips.isNotEmpty()) whisper.withWordTimingSession(cancelToken) { refine ->
                for (clip in clips) {
                    if (cancelled) return@withWordTimingSession
                    val anchors = refine(fullPcm.copyOfRange((clip.startMs * 16).toInt(),
                        minOf(fullPcm.size, (clip.endMs * 16).toInt())))
                        .map { it.copy(startMs = it.startMs + clip.startMs, endMs = it.endMs + clip.startMs) }
                    segs = BoundaryWordTiming.apply(segs, clip, anchors, base.turns)
                }
            }
            if (cancelled) { onDone("", emptyList(), null); return }
            diarization = SpeakerDiarizer.reassignWords(segs, base)
        }
        val finalText = diarization?.text ?: rawText
        Log.i(TAG, "Diarization done in ${System.currentTimeMillis() - whisperDone} ms")

        onDone(finalText, segs, diarization)
    }

    fun release() {
        executor.shutdownNow()
    }

    /** Called by the background owner before releasing its data lease. */
    fun awaitStopped() {
        var interrupted = false
        while (true) {
            try { if (executor.awaitTermination(100, TimeUnit.MILLISECONDS)) break }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun enqueue(chunk: ShortArray, startSample: Long, isFinal: Boolean) {
        if (activeJobs.getAndIncrement() == 0) onProcessingChanged(true)
        Log.d(TAG, "Enqueued chunk @${startSample / sampleRate}s len=${chunk.size / sampleRate}s final=$isFinal")

        try {
            executor.submit { processChunk(chunk, startSample) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Сессию уже отменили/закрыли — кадр никому не нужен.
            if (activeJobs.decrementAndGet() == 0) onProcessingChanged(false)
        }
    }

    /**
     * Язык куска. Смена языка относительно прошлого куска (для первого — относительно
     * русского) принимается, только если её подтверждает вторая проба по другой части
     * куска: на дальнем микрофоне определитель уверенно ошибался (p(en)=0.997 на
     * русской речи), и весь кусок уходил в перевод на английский.
     */
    private fun chunkLanguage(chunk: ShortArray): String {
        val prior = lastLanguage ?: AppPrefs.LANG_RU
        val first = whisper.detectLanguage(chunk) ?: return prior
        if (first == prior) return first
        // Короткая запись (голосовое на пару фраз) — перепроверять не по чему.
        if (chunk.size < sampleRate * 10) return first
        val second = whisper.detectLanguage(chunk.copyOfRange(chunk.size / 2, chunk.size))
        Log.i(TAG, "Language check: first=$first second=$second prior=$prior")
        return if (second == first) first else prior
    }

    private fun processChunk(chunk: ShortArray, startSample: Long) {
        try {
            if (cancelled) return
            if (!whisper.isReady) {
                failed.incrementAndGet()
                Log.w(TAG, "Chunk @${startSample / sampleRate}s skipped: engine not ready")
                return
            }
            // Речи слишком мало, чтобы решить, — язык прошлого чанка (или русский).
            val lang = chunkLanguage(chunk)
            val languageChanged = lastLanguage != null && lastLanguage != lang
            if (lang != lastLanguage) Log.i(TAG, "Chunk @${startSample / sampleRate}s language: $lang")
            lastLanguage = lang
            if (cancelled) return
            val (prompt, carry) = if (glossary.isNotEmpty()) {
                // Слова словаря — в КОНЦЕ: если подсказка длиннее лимита, whisper.cpp
                // отбрасывает её начало, а не конец.
                "$glossary." to true
            } else {
                // Хвост прошлого чанка даёт связность; если он похож на галлюцинацию
                // или он на другом языке — не тащим его дальше, иначе через 2-3
                // чанка модель «съезжает».
                val tail = lastTail.get()
                (if (tail.isEmpty() || languageChanged || !HallucinationFilter.isSafePrompt(tail)) "" else tail) to false
            }
            val segments = try {
                whisper.transcribeSegments(chunk, prompt, carryPrompt = carry, useVad = true, language = lang,
                                           cancelToken = cancelToken)
            } catch (e: Exception) {
                Log.e(TAG, "Whisper error in chunk @${startSample / sampleRate}s: ${e.message}", e)
                failed.incrementAndGet()
                emptyList()
            }
            if (segments.isEmpty() || cancelled) return

            val offsetMs = startSample * 1000L / sampleRate
            for (seg in segments) allSegments.add(seg.shifted(offsetMs))

            val text = segments.joinToString(" ") { it.text }.trim()
            if (text.isBlank()) return
            val snapshot: String
            synchronized(fullTranscript) {
                if (fullTranscript.isNotEmpty()) fullTranscript.append(" ")
                fullTranscript.append(text)
                lastTail.set(if (text.length > PROMPT_MAX_CHARS) text.takeLast(PROMPT_MAX_CHARS) else text)
                snapshot = fullTranscript.toString().trim()
            }
            // WAL: crash-recovery snapshot. Overwrite atomically.
            walFile?.let { wal ->
                try {
                    val tmp = File(wal.path + ".tmp")
                    tmp.writeText(snapshot)
                    tmp.renameTo(wal)
                } catch (e: Exception) {
                    Log.w(TAG, "WAL write failed: ${e.message}")
                }
            }
            onChunkReady(text, snapshot)
        } catch (e: Exception) {
            if (!cancelled) {
                failed.incrementAndGet()
                Log.e(TAG, "Chunk processing failed", e)
            }
        } finally {
            if (activeJobs.decrementAndGet() == 0) onProcessingChanged(false)
        }
    }
}
