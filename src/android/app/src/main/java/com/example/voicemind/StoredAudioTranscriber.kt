package com.example.voicemind

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException

/** Old recordings without timestamps use the same bounded pipeline as imports. */
object StoredAudioTranscriber {
    fun transcribe(ctx:Context, audio:File, whisper:WhisperHelper, cancelled:()->Boolean):List<WhisperHelper.Segment> {
        check(whisper.retain()) { "Распознавание ещё не готово" }
        var session:TranscriptionSession?=null
        val monitor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task,"VoiceMind-audio-cancellation").apply { isDaemon=true }
        }
        try {
            val current=TranscriptionSession(whisper=whisper,embedder=null,context=ctx,
                onChunkReady={_,_->},onProcessingChanged={},persistWal=false)
            session=current
            monitor.scheduleWithFixedDelay({if(cancelled())current.cancel()},0,100,java.util.concurrent.TimeUnit.MILLISECONDS)
            fun checkCancelled(){if(cancelled())throw CancellationException("Обработка отменена")}
            AudioDecoder.decodeStreaming(audio){frame->
                checkCancelled()
                while(current.pendingChunks()>=2){checkCancelled();Thread.sleep(50)}
                current.pushAudio(frame)
            }
            checkCancelled()
            var result=emptyList<WhisperHelper.Segment>()
            current.flushAndWait(onDone={_,segments,_->checkCancelled();result=segments})
            check(current.failedChunks==0) { "Часть аудио не распознана. Повторите обработку" }
            checkCancelled()
            return result
        }finally{
            monitor.shutdownNow()
            try{session?.cancel();session?.release();session?.awaitStopped()}finally{whisper.releaseUse()}
        }
    }
}
