package com.guzlik.voice

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
 * Протокол совещания по записи (шаблон [LlmHelper.Template.MEETING]) — в фоне,
 * как [QaJobRunner]: закрыли лист — генерация идёт дальше, по готовности
 * уведомление. Готовый протокол сохраняется в запись и попадает в экспорт PDF/Word.
 */
object ProtocolRunner {

    sealed class State {
        object Idle : State()
        data class Running(val timestamp: Long, val partial: String = "") : State()
        data class Done(val timestamp: Long, val text: String, val saved: Boolean = true) : State()
        data class Failed(val timestamp: Long, val error: String) : State()
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

    /** Вызывать после ensureLlmLoaded — [llm] должен быть готов. */
    fun start(ctx: Context, llm: LlmHelper, timestamp: Long, transcript: String) {
        if(!llm.retain()) {startWith(ctx,timestamp,transcript){error("ИИ-помощник ещё не готов")};return}
        startWith(ctx,timestamp,transcript,{llm.cancel()},{llm.releaseUse()}) { partial -> llm.generateFromTemplate(transcript,LlmHelper.Template.MEETING,partial) }
    }

    @Synchronized internal fun startWith(ctx: Context, timestamp: Long, transcript: String,
        cancelGeneration: () -> Unit = {}, onFinished: () -> Unit = {}, generate: ((String) -> Unit) -> String) {
        val request = generation.incrementAndGet()
        if (currentJob?.isActive == true) activeCancel?.invoke()
        currentJob?.cancel()
        activeCancel = cancelGeneration
        val app = ctx.applicationContext
        val lease = UserDataJobs.begin() ?: run {onFinished();setState(State.Idle,request);return}
        lease.onCancel { cancelRequest(request) }
        val token = try { RecordingService.processingStarted(app) }
        catch (e: Exception) {lease.close();onFinished();setState(State.Failed(timestamp,ErrorMessages.humanize(e)),request);return}
        setState(State.Running(timestamp),request)
        val job = scope.launch {
            try {
                lease.check()
                var lastPost = 0L
                val text = generate { partial ->
                    val now=System.currentTimeMillis()
                    if (now-lastPost>=150) {lastPost=now;setState(State.Running(timestamp,partial),request)}
                }
                lease.check()
                synchronized(this@ProtocolRunner) {
                if (!isActive || generation.get()!=request) return@launch
                if (text.isBlank()) {
                    setState(State.Failed(timestamp,"Не удалось составить протокол. Попробуйте ещё раз."),request)
                } else {
                    val entry=RecordStorage.findEntryByTimestamp(app,timestamp)?.second
                    val saved=entry!=null && !entry.isEncrypted && RecordStorage.saveTemplateResult(app,timestamp,transcript,LlmHelper.Template.MEETING.id,text)
                    if(entry==null || (!entry.isEncrypted && !saved)) {
                        setState(State.Failed(timestamp,"Запись удалена или изменена. Откройте её заново и повторите анализ."),request)
                    } else {
                        setState(State.Done(timestamp,text,saved),request)
                        if(saved)ProcessingNotifier.notifyReady(app,timestamp,"Протокол готов")
                    }
                }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                if(isActive)setState(State.Failed(timestamp,ErrorMessages.humanize(e)),request)
            }
        }
        currentJob=job
        job.invokeOnCompletion {try{RecordingService.processingFinished(app,token)}finally{try{lease.close()}finally{onFinished()}}}
    }

    @Synchronized private fun cancelRequest(request:Long) {if(generation.get()==request)cancel()}

    @Synchronized fun cancel() {
        generation.incrementAndGet()
        if(currentJob?.isActive==true)activeCancel?.invoke()
        currentJob?.cancel()
        currentJob=null
        activeCancel=null
        setState(State.Idle)
    }
}
