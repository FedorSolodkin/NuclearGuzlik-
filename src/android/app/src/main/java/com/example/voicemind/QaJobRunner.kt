package com.example.voicemind

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Application-scoped runner для одного Q&A-запроса к Gemma.
 *
 * Раньше QaSheet держал задачу на своём lifecycleScope — при закрытии листа
 * (пользователь ушёл на другую вкладку) задача отменялась. Теперь запрос
 * живёт в SupervisorJob, а UI подписывается на состояние: если ушли и вернулись,
 * увидим либо прогресс, либо готовый ответ.
 *
 * Хранится ровно одно состояние (последний вопрос). Новый ask отменяет
 * предыдущий — параллельные Q&A нам не нужны, а Gemma всё равно работает
 * последовательно.
 */
object QaJobRunner {

    sealed class State {
        object Idle : State()
        /** [partial] — уже сгенерированная часть ответа (потоковый вывод). */
        data class Running(val recordKey: String, val question: String, val partial: String = "") : State()
        data class Done(val recordKey: String, val question: String, val answer: String) : State()
        data class Failed(val recordKey: String, val question: String, val error: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(State) -> Unit>()
    private var currentJob: Job? = null
    @Volatile private var activeCancel: (() -> Unit)? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    var state: State = State.Idle
        private set

    fun addListener(cb: (State) -> Unit) {
        synchronized(listeners) { listeners.add(cb) }
        cb(state)
    }

    fun removeListener(cb: (State) -> Unit) {
        synchronized(listeners) { listeners.remove(cb) }
    }

    @Synchronized private fun setState(s: State, request: Long = generation.get()) {
        if (generation.get() != request) return
        state = s
        mainHandler.post {
            if (generation.get() == request) {
                val snapshot = synchronized(listeners) { listeners.toList() }
                snapshot.forEach { it(s) }
            }
        }
    }

    /**
     * Запускает Q&A. Вызывать только после ensureLlmLoaded — [llm] должен быть готов.
     * [recordKey] — стабильный идентификатор записи (обычно timestamp.toString()),
     * чтобы UI знал, относится ли фоновой результат к текущей открытой записи.
     */
    fun ask(ctx: Context, llm: LlmHelper, recordKey: String, transcript: String, question: String) {
        if(!llm.retain()) { askWith(ctx,recordKey,question){error("ИИ-помощник ещё не готов")};return }
        askWith(ctx,recordKey,question,{llm.cancel()},{llm.releaseUse()}) { partial -> llm.askQuestion(transcript,question,partial) }
    }

    @Synchronized internal fun askWith(ctx: Context, recordKey: String, question: String,
        cancelGeneration: () -> Unit = {}, onFinished: () -> Unit = {}, generate: ((String) -> Unit) -> String) {
        val request = generation.incrementAndGet()
        if (currentJob?.isActive == true) activeCancel?.invoke()
        currentJob?.cancel()
        activeCancel = cancelGeneration
        val app = ctx.applicationContext
        val lease = UserDataJobs.begin() ?: run { onFinished();setState(State.Idle,request);return }
        lease.onCancel { cancelRequest(request) }
        val token = try { RecordingService.processingStarted(app) }
        catch (e: Exception) { lease.close();onFinished();setState(State.Failed(recordKey,question,ErrorMessages.humanize(e)),request);return }
        setState(State.Running(recordKey,question),request)
        val job = scope.launch {
            try {
                lease.check()
                var lastPost = 0L
                val answer = generate { partial ->
                    val now = System.currentTimeMillis()
                    if (now-lastPost >= 100) { lastPost=now; setState(State.Running(recordKey,question,partial),request) }
                }
                lease.check()
                if (!isActive || generation.get() != request) return@launch
                setState(State.Done(recordKey,question,answer),request)
                recordKey.toLongOrNull()?.let { ProcessingNotifier.notifyReady(app,it,"Ответ готов") }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancellation and replacement cannot publish an older failure.
            } catch (e: Exception) {
                if (isActive) setState(State.Failed(recordKey,question,ErrorMessages.humanize(e)),request)
            }
        }
        currentJob = job
        // Completion also runs if cancellation wins before the coroutine body starts.
        job.invokeOnCompletion { try { RecordingService.processingFinished(app,token) } finally { try{lease.close()}finally{onFinished()} } }
    }

    @Synchronized private fun cancelRequest(request:Long) { if(generation.get()==request)clear() }

    /** Discard a pending or completed answer, invalidating queued UI callbacks too. */
    @Synchronized fun clear() {
        generation.incrementAndGet()
        if (currentJob?.isActive == true) activeCancel?.invoke()
        currentJob?.cancel()
        currentJob = null
        activeCancel = null
        setState(State.Idle)
    }
}
