package com.example.voicemind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in checks on actual captured audio: short-window recovery and manual-edit safety. */
@RunWith(AndroidJUnit4::class)
class BoundaryRefinementDeviceTest {
    @Test fun interruptedSecondPassRestoresTheMissedReply() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("boundaryAudit")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval/acoustic/interview-2026-11-04")
        val gson=Gson()
        val full=File(dir,"phone-full-evidence.json").bufferedReader().use{gson.fromJson(it,SpeakerDiarizer.EvidenceSnapshot::class.java)}
        val origin=225000L;val duration=15000L
        val probes=(origin until origin+duration step 100L).map{WhisperHelper.Segment("probe",it,it+100)}
        val baseline=SpeakerDiarizer.diarize(probes,emptyMap(),null,context=ctx,cachedEvidence=full.copy(boundaryRefinement=null))
        val local=full.copy(windows=listOf(SpeakerActivityFusion.Window(0,duration)),regions=full.regions.filter{
            it.endMs>origin && it.startMs<origin+duration
        }.map{it.copy(startMs=maxOf(0,it.startMs-origin),endMs=minOf(duration,it.endMs-origin),window=0,previewMs=maxOf(0,it.previewMs-origin))},boundaryRefinement=null)
        val originalTurns=baseline.turns.filter{it.endMs>origin && it.startMs<origin+duration}
            .map{it.copy(startMs=maxOf(0,it.startMs-origin),endMs=minOf(duration,it.endMs-origin))}
        val bytes=ByteArray((duration*32).toInt())
        RandomAccessFile(File(dir,"source.s16"),"r").use{it.seek(origin*32);it.readFully(bytes)}
        val samples=ShortArray(bytes.size/2);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val audio=ArrayPcm(samples);val embedder=SpeakerEmbedder(ctx);assertTrue(embedder.load())
        val ts=System.currentTimeMillis();val source=File(dir,"source.s16")
        val key=DiarizationEvidenceCache.keyFor(ctx,source,ts)
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java){
                SpeakerBoundaryRefiner.capture(local,baseline.centroids,audio,embedder::embed,checkpointEveryObservations=2,checkpoint={partial->
                    if(partial.observations.size==2){
                        assertTrue(DiarizationCheckpointStore.save(ctx,DiarizationCheckpointCodec.Checkpoint(key,1,1,local.copy(boundaryRefinement=partial)),DiarizationEvidenceCache.ticket(ts)))
                        throw java.util.concurrent.CancellationException("Simulated interruption of boundary pass")
                    }
                })
            }
            DiarizationEvidenceCache.clearAll()
            val restored=DiarizationCheckpointStore.load(ctx,key)!!
            assertEquals(2,restored.evidence.boundaryRefinement!!.observations.size)
            assertFalse(restored.evidence.boundaryRefinement!!.completed)
            val resumed=SpeakerBoundaryRefiner.capture(restored.evidence,baseline.centroids,audio,embedder::embed)
            val fresh=SpeakerBoundaryRefiner.capture(local,baseline.centroids,audio,embedder::embed)
            assertEquals(fresh.observations.size,resumed.observations.size)
            fresh.observations.zip(resumed.observations).forEach{(a,b)->assertArrayEquals(a.embedding,b.embedding,0f)}
            val refined=SpeakerBoundaryRefiner.refine(originalTurns,resumed.observations,baseline.centroids)
            assertEquals(SpeakerBoundaryRefiner.refine(originalTurns,fresh.observations,baseline.centroids),refined)
            val globalTimeline=SpeakerTimeline(baseline.turns)
            val second=globalTimeline.decide(227500,228000).speaker
            val third=globalTimeline.decide(1185000,1185500).speaker
            assertTrue(second>=0 && third>=0);assertNotEquals(second,third)
            assertEquals(third,SpeakerTimeline(refined).decide(7000,7250).speaker)
            assertEquals(second,SpeakerTimeline(refined).decide(2500,3000).speaker)
            assertTrue(DiarizationCheckpointStore.save(ctx,DiarizationCheckpointCodec.Checkpoint(key,1,1,local.copy(boundaryRefinement=resumed)),DiarizationEvidenceCache.ticket(ts)))
            assertTrue(DiarizationCheckpointStore.complete(ctx,key,DiarizationEvidenceCache.ticket(ts)))
            assertTrue(DiarizationCheckpointStore.load(ctx,key)!!.finished)
            File(dir,"boundary-recovery-validation.json").writeText(gson.toJson(mapOf("restored_observations" to 2,
                "short_windows" to resumed.observations.size,"resumed_identical" to true,"missed_reply_recovered" to true)))
        }finally{embedder.release();DiarizationCheckpointStore.remove(ctx,ts);DiarizationEvidenceCache.remove(ts)}
    }

    @Test fun changedTextDoesNotRestartAutomatically() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("boundaryAudit")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval/history");val gson=Gson()
        val evidence=File(dir,"evidence.json").bufferedReader().use{gson.fromJson(it,SpeakerDiarizer.EvidenceSnapshot::class.java)}
        assertTrue("Fully refined fixture required",evidence.boundaryRefinement?.completed==true)
        val segments=File(dir,"segments.json").bufferedReader().use{gson.fromJson(it,Array<WhisperHelper.Segment>::class.java).toList()}
        val source=File.createTempFile("diar-edit-race-",".fixture",ctx.cacheDir).apply{writeBytes(byteArrayOf(1,2,3,4))}
        val before="Текст до проверки конкурентного редактирования."
        val edited="Эту ручную правку необходимо сохранить."
        val ts=RecordStorage.addToToday(ctx,before,"text",null,source.absolutePath,linkPendingEvent=false)
        val key=DiarizationEvidenceCache.keyFor(ctx,source,ts)
        val done=CountDownLatch(1);var outcome:DiarizationJobRunner.State?=null;var editMade=false;var invokedModels=false
        val listener:(DiarizationJobRunner.State)->Boolean={state->
            if(state.timestamp==ts){
                if(state is DiarizationJobRunner.State.Running && state.stage=="Обновляю разделение..." && !editMade){
                    RecordStorage.updateEntryText(ctx,ts,edited);editMade=true
                }
                if(state is DiarizationJobRunner.State.Running && state.stage=="Диаризация..."){invokedModels=true;DiarizationJobRunner.cancel(ts)}
                if(state !is DiarizationJobRunner.State.Running){outcome=state;done.countDown()}
            }
            true
        }
        try {
            RecordStorage.updateEntrySegments(ctx,ts,segments)
            assertTrue(DiarizationCheckpointStore.save(ctx,DiarizationCheckpointCodec.Checkpoint(key,evidence.windows.size,evidence.windows.size,evidence),DiarizationEvidenceCache.ticket(ts)))
            assertTrue(DiarizationCheckpointStore.hasPending(ctx,ts))
            DiarizationJobRunner.addListener(listener)
            DiarizationJobRunner.start(ctx,source,ts,null,segments,null,true)
            assertTrue(done.await(45,TimeUnit.SECONDS));assertFalse(invokedModels);assertTrue(editMade)
            assertTrue("Expected a safe refusal: $outcome",outcome is DiarizationJobRunner.State.Failed)
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            while(DiarizationJobRunner.hasActiveJob(ts) && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(10)
            assertEquals(edited,RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text)
            assertFalse("A terminal conflict must not resume on next launch",DiarizationCheckpointStore.hasPending(ctx,ts))
        }finally{
            DiarizationJobRunner.removeListener(listener);DiarizationJobRunner.cancel(ts)
            RecordStorage.deleteByTimestamp(ctx,ts);source.delete();DiarizationJobRunner.consume(ts)
        }
    }
    @Test fun queuedJobCannotOverwriteAnEditMadeWhileWaiting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("boundaryAudit")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval/history")
        val evidence=File(dir,"evidence.json").bufferedReader().use{Gson().fromJson(it,SpeakerDiarizer.EvidenceSnapshot::class.java)}
        assertTrue(evidence.boundaryRefinement?.completed==true)
        val segments=listOf(WhisperHelper.Segment("Проверка.",100000,101000,words=listOf(WhisperHelper.Word("Проверка.",100000,101000,1f))))
        val files=List(2){File.createTempFile("diar-queue-",".fixture",ctx.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))}}
        val timestamps=files.map{RecordStorage.addToToday(ctx,"Исходный текст.","text",null,it.absolutePath,linkPendingEvent=false)}
        val busy=CountDownLatch(1);val done=CountDownLatch(1);var outcome:DiarizationJobRunner.State?=null
        val listener:(DiarizationJobRunner.State)->Boolean={state->
            if(state.timestamp==timestamps[0] && state is DiarizationJobRunner.State.Running && state.stage=="Обновляю разделение...")busy.countDown()
            if(state.timestamp==timestamps[1] && state !is DiarizationJobRunner.State.Running){outcome=state;done.countDown()}
            true
        }
        try {
            timestamps.forEachIndexed{i,ts->
                val key=DiarizationEvidenceCache.keyFor(ctx,files[i],ts)
                assertTrue(DiarizationCheckpointStore.save(ctx,DiarizationCheckpointCodec.Checkpoint(key,evidence.windows.size,evidence.windows.size,evidence),DiarizationEvidenceCache.ticket(ts)))
                RecordStorage.updateEntrySegments(ctx,ts,segments)
            }
            DiarizationJobRunner.addListener(listener)
            DiarizationJobRunner.start(ctx,files[0],timestamps[0],null,segments,null,true)
            assertTrue(busy.await(30,TimeUnit.SECONDS))
            DiarizationJobRunner.start(ctx,files[1],timestamps[1],null,segments,null,true)
            val edited="Ручная правка во время ожидания очереди."
            RecordStorage.updateEntryText(ctx,timestamps[1],edited)
            assertTrue(done.await(45,TimeUnit.SECONDS))
            assertTrue("Queued edit was not protected: $outcome",outcome is DiarizationJobRunner.State.Failed)
            assertEquals(edited,RecordStorage.findEntryByTimestamp(ctx,timestamps[1])!!.second.text)
            assertFalse(DiarizationCheckpointStore.hasPending(ctx,timestamps[1]))
        }finally{
            DiarizationJobRunner.removeListener(listener)
            timestamps.forEach{ts->
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                while(DiarizationJobRunner.hasActiveJob(ts) && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(10)
                DiarizationJobRunner.cancel(ts);RecordStorage.deleteByTimestamp(ctx,ts);DiarizationJobRunner.consume(ts)
            }
            files.forEach{it.delete()}
        }
    }

    @Test fun startupDoesNotResumeAJobWhoseTextChangedBeforeProcessDeath() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("boundaryAudit")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval/history")
        val evidence=File(dir,"evidence.json").bufferedReader().use{Gson().fromJson(it,SpeakerDiarizer.EvidenceSnapshot::class.java)}
        val source=File.createTempFile("diar-restart-edit-",".fixture",ctx.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))}
        val before="Текст на момент запуска обработки."
        val edited="Новая ручная правка перед перезапуском."
        val ts=RecordStorage.addToToday(ctx,before,"text",null,source.absolutePath,linkPendingEvent=false)
        try {
            val segment=WhisperHelper.Segment("Проверка.",100000,101000,words=listOf(WhisperHelper.Word("Проверка.",100000,101000,1f)))
            RecordStorage.updateEntrySegments(ctx,ts,listOf(segment))
            val key=DiarizationEvidenceCache.keyFor(ctx,source,ts)
            assertTrue(DiarizationCheckpointStore.save(ctx,DiarizationCheckpointCodec.Checkpoint(key,evidence.windows.size,evidence.windows.size,evidence,
                expectedTextHash=DiarizationCheckpointCodec.textFingerprint(before)),DiarizationEvidenceCache.ticket(ts)))
            RecordStorage.updateEntryText(ctx,ts,edited)
            kotlinx.coroutines.runBlocking{DiarizationJobRunner.resumeInterrupted(ctx)}
            assertFalse(DiarizationJobRunner.hasActiveJob(ts))
            assertEquals(edited,RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text)
            assertFalse(DiarizationCheckpointStore.hasPending(ctx,ts))
        }finally{DiarizationJobRunner.cancel(ts);RecordStorage.deleteByTimestamp(ctx,ts);source.delete();DiarizationJobRunner.consume(ts)}
    }

}
