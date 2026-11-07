package com.example.guzlikvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Разделение записи по голосам в фоне, на уровне приложения.
 *
 * Раньше задача жила на lifecycleScope экрана и отменялась, когда тот закрывался (смахнули приложение,
 * система выгрузила экран). Теперь она живёт здесь: пока идёт обработка, работает foreground-сервис
 * (RecordingService.processingStarted), а экран только подписывается на состояние. Закрыли лист записи или
 * свернули приложение — разделение продолжается; результат сохраняется в запись, а если никто его не
 * смотрит, приходит уведомление.
 *
 * Тяжёлые модели грузятся и считают по одной записи за раз (общая очередь), как и раньше при одной кнопке.
 */
object DiarizationJobRunner {
    private const val TAG = "DiarizationJob"

    sealed class State {
        abstract val timestamp: Long
        /** [stage] — что сейчас делается: подпись для кнопки. */
        data class Running(override val timestamp: Long, val stage: String) : State()
        /** [text] = null: текст записи не менялся (найден один голос, а меток раньше не было). */
        data class Done(override val timestamp: Long, val text: String?, val hasCentroids: Boolean, val speakers: Int, val requested: Int?) : State()
        /** [needsModels]: не скачана модель голосов — лист предложит её установить. */
        data class Failed(override val timestamp: Long, val message: String, val needsModels: Boolean = false) : State()
        /** Пользователь нажал «Отменить разделение»: ничего не сохранено. */
        data class Cancelled(override val timestamp: Long) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<Long, State>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val systemPaused = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val jobContexts = ConcurrentHashMap<Long, Context>()
    /** Идущее сейчас распознавание Whisper (у записей без сохранённых слов) и его токен отмены. */
    private val whisperCalls = ConcurrentHashMap<Long, Pair<WhisperHelper, Long>>()

    /**
     * Подписчик на изменения. Возвращает true, если показал результат пользователю (открыт лист этой записи):
     * тогда уведомление не нужно.
     */
    private val listeners = CopyOnWriteArrayList<(State) -> Boolean>()

    fun addListener(cb: (State) -> Boolean) { listeners.add(cb) }
    fun removeListener(cb: (State) -> Boolean) { listeners.remove(cb) }

    fun stateOf(timestamp: Long): State? = states[timestamp]
    internal fun hasActiveJob(timestamp: Long) = jobs[timestamp]?.isCompleted == false

    /** Результат уже показан: чтобы при следующем открытии записи он не затёр ручные правки текста. */
    fun consume(timestamp: Long) {
        val s = states[timestamp]
        if (s != null && s !is State.Running) states.remove(timestamp, s)
    }

    /**
     * Останавливает разделение этой записи (идущее или ждущее очереди). Куски в работе дорабатываются, это до пары секунд;
     * после остановки ничего не сохраняется, текст и метки записи остаются прежними.
     */
    fun cancel(timestamp: Long) {
        val job = jobs[timestamp] ?: return
        DiarizationEvidenceCache.remove(timestamp)
        jobContexts[timestamp]?.let { DiarizationCheckpointStore.remove(it, timestamp) }
        pushRunning(State.Running(timestamp, "Отменяю..."))
        job.cancel()
        whisperCalls[timestamp]?.let { (whisper, token) -> whisper.abort(token) }
    }

    fun pauseForSystem() {
        for ((timestamp, job) in jobs) {
            systemPaused.add(timestamp)
            job.cancel()
            whisperCalls[timestamp]?.let { (whisper, token) -> whisper.abort(token) }
        }
    }

    private fun setState(s: State): Boolean {
        states[s.timestamp] = s
        var handled = false
        // Подписчики работают с интерфейсом: вызываем в главном потоке и ждём ответа только для итоговых состояний.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { if (it(s)) handled = true }
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try { listeners.forEach { if (it(s)) handled = true } } finally { latch.countDown() }
            }
            latch.await()
        }
        return handled
    }

    /**
     * Запускает разделение. Если для этой записи оно уже идёт, ничего не делает.
     * [storedSegments] — фразы со словами, сохранённые при записи (v3.8+); без них запись распознаётся заново через [whisper].
     */
    @Synchronized fun start(
        ctx: Context,
        audioFile: File,
        timestamp: Long,
        numSpeakers: Int?,
        storedSegments: List<WhisperHelper.Segment>?,
        whisper: WhisperHelper?,
        hadLabels: Boolean,
        resumeCheckpoint: DiarizationCheckpointCodec.Checkpoint? = null,
    ) {
        val appCtx = ctx.applicationContext
        if (states[timestamp] is State.Running || jobs[timestamp]?.isCompleted == false) return
        val lease = UserDataJobs.begin() ?: return
        val requestedText = try { RecordStorage.textSnapshot(appCtx,timestamp) }
            catch(e:Throwable){lease.close();throw e}
        jobContexts[timestamp] = appCtx
        // Если запуск не удался до создания задачи, аренду нужно вернуть, иначе «Удалить все данные» будет ждать её до таймаута.
        var processingToken: Long? = null
        try {
            lease.onCancel { cancel(timestamp) }
            pushRunning(State.Running(timestamp, "Обработка..."))
            processingToken = RecordingService.processingStarted(appCtx)
        } catch (e: Throwable) {
            states.remove(timestamp)
            jobContexts.remove(timestamp)
            lease.close()
            throw e
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var notify = false
            try {
                queue.withLock {
                    val outcome = run(appCtx, audioFile, timestamp, numSpeakers, storedSegments, whisper, hadLabels, resumeCheckpoint,requestedText)
                    when (outcome) {
                        is State.Done -> {
                            notify = !setState(outcome)
                            val single = outcome.speakers <= 1
                            val req = outcome.requested
                            toast(appCtx, when {
                                !outcome.hasCentroids -> "Не удалось уверенно разделить голоса. Проверьте модели и качество записи; текст сохранён без новых меток"
                                single && req != null && req > 1 ->
                                    "Указано людей: $req, но найден один голос: голоса очень похожи, либо один из собеседников почти не слышен. Попробуйте записать ближе к говорящим"
                                single -> "Найден один голос: разделять нечего. Если говорили несколько человек, укажите их число (2–5) и разделите заново"
                                req != null && outcome.speakers < req -> "Найдено голосов: ${outcome.speakers} из $req — остальные слишком похожи"
                                else -> "Готово: найдено голосов — ${outcome.speakers}"
                            }, long = single || !outcome.hasCentroids || (req != null && outcome.speakers < req))
                        }
                        is State.Failed -> {
                            val shown = setState(outcome)
                            // Про отсутствующую модель лист спросит сам; без него (закрыт) напоминаем тостом.
                            if (!(shown && outcome.needsModels)) toast(appCtx, outcome.message, long = outcome.needsModels)
                        }
                        is State.Running, is State.Cancelled -> Unit
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Diarization cancelled by user")
                setState(State.Cancelled(timestamp))
                toast(appCtx, if (systemPaused.remove(timestamp)) "Обработка приостановлена системой. Продолжим при открытии приложения" else "Разделение отменено")
            } catch (e: Exception) {
                Log.e(TAG, "Diarization failed: ${e.message}", e)
                val msg = ErrorMessages.humanize(e)
                setState(State.Failed(timestamp, msg)); toast(appCtx, msg)
            } finally {
                // Уведомление нужно, только если результат никто не видел (лист записи закрыт, приложение свёрнуто).
                if (notify) ProcessingNotifier.notifyReady(appCtx, timestamp, "Разделение по голосам готово")
            }
        }
        jobs[timestamp] = job
        job.invokeOnCompletion {
            if (jobs.remove(timestamp, job)) {
                jobContexts.remove(timestamp)
                if (job.isCancelled && states[timestamp] is State.Running) setState(State.Cancelled(timestamp))
                systemPaused.remove(timestamp)
            }
            try { processingToken?.let { RecordingService.processingFinished(appCtx, it) } }
            finally { lease.close() }
        }
        job.start()
        if (lease.cancelled) cancel(timestamp)
    }

    /** Промежуточное состояние: подписчикам сообщаем без ожидания ответа (для итога нужен ответ, см. setState). */
    private fun pushRunning(s: State) {
        states[s.timestamp] = s
        val push = { listeners.forEach { it(s) } }
        if (Looper.myLooper() == Looper.getMainLooper()) push() else mainHandler.post { push() }
    }

    suspend fun resumeInterrupted(ctx: Context, canStart: () -> Boolean = { true }) {
        val pending = kotlinx.coroutines.withContext(Dispatchers.IO) { DiarizationCheckpointStore.pending(ctx) }
        for (checkpoint in pending) {
            val ts = checkpoint.key.timestamp
            val entry = RecordStorage.findEntryByTimestamp(ctx, ts)?.second
            val audio = File(checkpoint.key.audioPath)
            if (entry == null || entry.isEncrypted || !audio.exists() ||
                DiarizationEvidenceCache.keyFor(ctx, audio, ts) != checkpoint.key) {
                kotlinx.coroutines.withContext(Dispatchers.IO) { DiarizationCheckpointStore.remove(ctx, ts) }
                continue
            }
            // A process may die after a manual edit but before the final CAS.
            // Never restart that older job against the newer text on next launch.
            if(checkpoint.expectedTextHash!=null && checkpoint.expectedTextHash!=DiarizationCheckpointCodec.textFingerprint(entry.text)){
                DiarizationCheckpointStore.complete(ctx,checkpoint.key,DiarizationEvidenceCache.ticket(ts))
                continue
            }
            if (!canStart()) return
            if (DiarizationUnits.canReuse(entry.segments.orEmpty())) try {
                start(ctx, audio, ts, checkpoint.requested, entry.segments, null, checkpoint.hadLabels, checkpoint)
            } catch (e: Exception) {
                Log.w(TAG, "Resume deferred: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun toast(appCtx: Context, msg: String, long: Boolean = false) =
        mainHandler.post { Toast.makeText(appCtx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }

    private suspend fun run(
        appCtx: Context, audioFile: File, timestamp: Long, numSpeakers: Int?,
        storedSegments: List<WhisperHelper.Segment>?, whisper: WhisperHelper?, hadLabels: Boolean,
        preloaded: DiarizationCheckpointCodec.Checkpoint?,
        requestedText:String?,
    ): State {
        val tStart = System.nanoTime()
        val original = RecordStorage.findEntryByTimestamp(appCtx, timestamp)?.second
            ?: return State.Failed(timestamp, "Запись удалена")
        if (original.isEncrypted) return State.Failed(timestamp, "Для разделения сначала снимите защиту записи")
        val job = currentCoroutineContext()[Job]
        fun cancelled() = job?.isActive == false
        fun lap(what: String, since: Long) = Log.i(TAG, "$what: %.1f s".format((System.nanoTime() - since) / 1e9))
        val reuse = storedSegments?.takeIf { DiarizationUnits.canReuse(it) }
        val key = DiarizationEvidenceCache.keyFor(appCtx, audioFile, timestamp)
        val requestedHash=DiarizationCheckpointCodec.textFingerprint(requestedText ?: original.text)
        val ticket = DiarizationEvidenceCache.ticket(timestamp)
        val checkpointAllowed = RecordStorage.findEntryByTimestamp(appCtx, timestamp)?.second?.isEncrypted == false
        val inMemory = if (reuse != null) DiarizationEvidenceCache.get(key,ticket) else null
        var durable = preloaded?.takeIf { it.key == key && reuse != null && checkpointAllowed }
            ?: if (reuse != null && checkpointAllowed && (inMemory == null || DiarizationCheckpointStore.hasPending(appCtx,timestamp)))
                DiarizationCheckpointStore.load(appCtx, key) else null
        fun finishCheckpoint() {
            durable?.takeIf { checkpointAllowed && !it.finished }?.let {
                DiarizationCheckpointStore.complete(appCtx,key,ticket)
            }
        }
        // A system pause can leave newer short-window progress on disk than in RAM.
        val pendingFull = durable?.takeIf { !it.finished && it.processed == it.total }?.evidence
        val cached = pendingFull ?: inMemory ?: durable?.takeIf { it.processed == it.total }?.evidence
        if(original.text!=requestedText){
            finishCheckpoint()
            return State.Failed(timestamp,"Текст изменён во время ожидания. Ваши правки сохранены; запустите разделение снова")
        }
        if (cached != null) DiarizationEvidenceCache.put(key,ticket,cached)
        var decoded: FilePcm? = null
        val reusedPcm: PcmAudio? = DiarizationEvidenceCache.getPcm(key, ticket)
        fun pcm(): PcmAudio = reusedPcm ?: decoded ?: AudioDecoder.decodeToFile(audioFile, appCtx.cacheDir, ::cancelled).also {
            decoded = it
            if (!cancelled()) DiarizationEvidenceCache.putPcm(key, ticket, it)
            lap("decode ${it.size / 16_000} s of audio", tStart)
        }
        try {
            // Missing times in one phrase no longer cause full-record ASR.
            var segments = reuse ?: run {
                if (whisper == null || !whisper.isReady)
                    return State.Failed(timestamp, "Сначала откройте вкладку «Запись»: распознавание ещё запускается")
                pushRunning(State.Running(timestamp, "Распознаю..."))
                val audio = pcm()
                val token = WhisperHelper.newCancelToken()
                if (!whisper.retain()) return State.Failed(timestamp, "Распознавание ещё запускается")
                whisperCalls[timestamp] = whisper to token
                val recognized = try {
                    val parts = mutableListOf<WhisperHelper.Segment>()
                    var pos = 0
                    while (pos < audio.size) {
                        currentCoroutineContext().ensureActive()
                        val end = minOf(audio.size, pos + 58 * 16_000)
                        parts += whisper.transcribeSegments(audio.copyOfRange(pos, end), cancelToken = token)
                            .map { it.shifted(pos * 1000L / 16_000) }
                        pos = end
                    }
                    parts
                } finally {
                    whisperCalls.remove(timestamp); whisper.releaseUse()
                }
                currentCoroutineContext().ensureActive()
                recognized
            }
            if (segments.isEmpty()) return State.Failed(timestamp, "Речь не распознана")
            if (reuse == null && checkpointAllowed) RecordStorage.updateEntrySegments(appCtx, timestamp, segments)
            val tDiar = System.nanoTime()
            var result = if (cached?.boundaryRefinement?.completed == true) {
                pushRunning(State.Running(timestamp, "Обновляю разделение..."))
                Log.i(TAG, "Reusing in-memory speaker evidence; skipping decode and models")
                SpeakerDiarizer.diarize(segments, emptyMap(), null, context = appCtx, numSpeakers = numSpeakers,
                    isCancelled = ::cancelled, cachedEvidence = cached)
            } else {
                pushRunning(State.Running(timestamp, "Диаризация..."))
                val audio = pcm()
                currentCoroutineContext().ensureActive()
                val embedder = SpeakerEmbedder(appCtx)
                if (!embedder.load()) return State.Failed(timestamp,
                    "Разделение голосов не установлено (около 40 МБ). Скачайте его, чтобы приложение различало людей", needsModels = true)
                try {
                    SpeakerDiarizer.diarize(segments, emptyMap(), embedder, audio, appCtx, numSpeakers,
                        onProgress = { pct -> if (!cancelled()) pushRunning(State.Running(timestamp, "Диаризация $pct%")) },
                        isCancelled = ::cancelled,
                        onEvidenceReady = { evidence -> if (!cancelled()) DiarizationEvidenceCache.put(key, ticket, evidence) },
                        cachedEvidence = cached,
                        processing = SpeakerDiarizer.EvidenceProcessing(resume = durable?.evidence,
                            processedChunks = durable?.processed ?: 0,
                            workers = DiarizationRuntimePolicy.workers(appCtx),
                            checkpoint = if (checkpointAllowed) { processed, total, evidence ->
                                if (!cancelled()) {
                                    val next = DiarizationCheckpointCodec.Checkpoint(key, processed, total, evidence, numSpeakers, hadLabels,expectedTextHash=requestedHash)
                                    if (DiarizationCheckpointStore.save(appCtx, next, ticket)) durable = next
                                }
                            } else null))
                } finally { embedder.release() }
            }
            lap("diarization", tDiar)
            val aligner = (whisper ?: WhisperHelper.active)?.takeIf { it.isReady }
            val evidence = DiarizationEvidenceCache.get(key, ticket)
            if (aligner != null && evidence != null && result.centroids.size > 1 && aligner.retain()) {
                val token = WhisperHelper.newCancelToken()
                whisperCalls[timestamp] = aligner to token
                try {
                    val clips = BoundaryWordTiming.plan(segments, result.turns, evidence.windows.maxOf { it.endMs })
                    val audio = if (clips.isNotEmpty()) pcm() else null
                    if (audio != null) aligner.withWordTimingSession(token) { refine ->
                        for ((i, clip) in clips.withIndex()) {
                            if (cancelled()) throw CancellationException("Boundary pass cancelled")
                            pushRunning(State.Running(timestamp, "Уточняю слова у смены голоса ${i + 1}/${clips.size}..."))
                            val refined = refine(audio.copyOfRange((clip.startMs * 16).toInt(),
                                minOf(audio.size, (clip.endMs * 16).toInt())))
                                .map { it.copy(startMs = it.startMs + clip.startMs, endMs = it.endMs + clip.startMs) }
                            segments = BoundaryWordTiming.apply(segments, clip, refined, result.turns)
                        }
                    }
                    if (clips.isNotEmpty()) result = SpeakerDiarizer.reassignWords(segments, result)
                } finally { whisperCalls.remove(timestamp); aligner.releaseUse() }
            }
            currentCoroutineContext().ensureActive()
            if (result.centroids.isEmpty()) {
                finishCheckpoint()
                return State.Failed(timestamp,"Не удалось уверенно разделить голоса. Проверьте качество записи и модели. Текст и прежние метки сохранены")
            }
            if (DiarizationEvidenceCache.keyFor(appCtx, audioFile, timestamp) != key)
                return State.Failed(timestamp, "Аудио или модели изменились во время обработки. Запустите разделение снова")
            val latest = RecordStorage.findEntryByTimestamp(appCtx, timestamp)?.second
            if (latest == null || latest.isEncrypted || !DiarizationEvidenceCache.isCurrent(ticket))
                throw CancellationException("Запись удалена или защищена")
            if (latest.text != original.text) {
                finishCheckpoint()
                return State.Failed(timestamp,"Текст изменён во время обработки. Ваши правки сохранены; запустите разделение снова")
            }
            val speakers = result.displayNames.size
            val keepText = speakers <= 1 && !hadLabels
            val text = DiarizedTranscript.apply(original.text,result)
            if (!RecordStorage.saveDiarizationResult(appCtx, timestamp, original.text, if (keepText) null else text, segments, result)) {
                finishCheckpoint()
                return State.Failed(timestamp, "Запись изменилась. Ваши правки сохранены; запустите разделение снова")
            }
            finishCheckpoint()
            lap("total", tStart)
            return State.Done(timestamp, if (keepText) null else text, true, speakers, numSpeakers)
        } finally { decoded?.close() }
    }
}
