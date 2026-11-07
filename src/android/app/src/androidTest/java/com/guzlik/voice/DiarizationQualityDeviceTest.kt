package com.guzlik.voice

import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** All data stays in the opt-in debug package. Never touches the working app. */
@RunWith(AndroidJUnit4::class)
class DiarizationQualityDeviceTest {
    @Test fun inferenceResumeAndPerformance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("qualityAudit") == "true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval")
        val fixture=File(dir,"diar/tts_one_minute.s16")
        assumeTrue(fixture.exists())
        val bytes=fixture.readBytes(); val pcm=ShortArray(bytes.size/2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val duration=pcm.size*1000L/16000
        val segments=(0L until duration step 100).map { WhisperHelper.Segment("probe",it,minOf(duration,it+100)) }
        val savedSpinning=DiarizationRuntimePolicy.spinning(ctx)
        val savedThreads=DiarizationRuntimePolicy.threads(ctx)
        val embedder=SpeakerEmbedder(ctx);assertTrue(embedder.load(savedSpinning,savedThreads))
        val report=JSONObject();val runs=JSONArray();val timings=mutableMapOf<Int,MutableList<Long>>()
        var baseline: SpeakerDiarizer.Result?=null
        var captured: SpeakerDiarizer.EvidenceSnapshot?=null
        fun batteryTemp()=ctx.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature",-1) ?: -1
        try {
            // Symmetric order reduces warmup/thermal bias. No reduced precision or skipped audio.
            for (workers in listOf(1,2,3,3,2,1)) {
                val temp=batteryTemp();val start=SystemClock.elapsedRealtime()
                val result=SpeakerDiarizer.diarize(segments,emptyMap(),embedder,pcm,ctx,
                    onEvidenceReady={ captured=it },processing=SpeakerDiarizer.EvidenceProcessing(workers=workers))
                val elapsed=SystemClock.elapsedRealtime()-start
                if (baseline==null) baseline=result else {
                    assertEquals("Worker count changed acoustic turns",baseline!!.turns,result.turns)
                    assertArrayEquals("Worker count changed attribution",baseline!!.speakers,result.speakers)
                }
                assertEquals(2,result.centroids.size)
                timings.getOrPut(workers){mutableListOf()}.add(elapsed)
                runs.put(JSONObject().put("workers",workers).put("ms",elapsed).put("rtf",elapsed.toDouble()/duration)
                    .put("battery_temp_before_tenths_c",temp).put("battery_temp_after_tenths_c",batteryTemp())
                    .put("pss_kib",Debug.getPss()).put("native_heap_bytes",Debug.getNativeHeapAllocatedSize()))
                android.util.Log.i("DiarQuality","workers=$workers ms=$elapsed")
            }
            val medians=timings.mapValues { (_,v)->v.average() }
            val winner=medians.minBy { it.value }.key
            // Only retain a measured change with >5% advantage over the current three-worker default.
            val chosen=if (medians.getValue(winner) < medians.getValue(3)*0.95) winner else 3
            DiarizationRuntimePolicy.saveMeasured(ctx,chosen,savedSpinning,savedThreads)
            report.put("runs",runs).put("chosen_workers",chosen).put("audio_ms",duration)

            // Save after two chunks, interrupt, drop all RAM evidence, then resume from real Keystore file.
            val ts=System.currentTimeMillis()+101
            val key=DiarizationEvidenceCache.keyFor(ctx,fixture,ts)
            val ticket=DiarizationEvidenceCache.ticket(ts)
            try {
                assertThrows(CancellationException::class.java) {
                    SpeakerDiarizer.diarize(segments,emptyMap(),embedder,pcm,ctx,
                        processing=SpeakerDiarizer.EvidenceProcessing(workers=chosen,checkpointEveryChunks=1,
                            checkpoint={ processed,total,e ->
                                if (processed==2) {
                                    assertTrue(DiarizationCheckpointStore.save(ctx,DiarizationCheckpointCodec.Checkpoint(key,processed,total,e),ticket))
                                    throw CancellationException("Simulated process interruption")
                                }
                            }))
                }
                DiarizationEvidenceCache.clearAll()
                val checkpoint=DiarizationCheckpointStore.load(ctx,key)!!
                assertEquals(2,checkpoint.processed)
                assertTrue(DiarizationCheckpointStore.hasPending(ctx,ts))
                assertTrue(DiarizationCheckpointStore.pending(ctx).any { it.key.timestamp==ts })
                assertTrue(DiarizationCheckpointStore.save(ctx,checkpoint.copy(processed=checkpoint.total, finished=true,
                    evidence=captured!!),DiarizationEvidenceCache.ticket(ts)))
                assertFalse(DiarizationCheckpointStore.hasPending(ctx,ts))
                assertFalse(DiarizationCheckpointStore.pending(ctx).any { it.key.timestamp==ts })
                val complete=checkpoint.copy(processed=checkpoint.total,evidence=captured!!)
                assertTrue(DiarizationCheckpointStore.save(ctx,complete,DiarizationEvidenceCache.ticket(ts)))
                val encryptedFile=File(ctx.noBackupFilesDir,"diarization/$ts.aead")
                val beforeComplete=encryptedFile.readBytes()
                assertTrue(DiarizationCheckpointStore.complete(ctx,key,DiarizationEvidenceCache.ticket(ts)))
                assertArrayEquals("Completing should not rewrite voice evidence",beforeComplete,encryptedFile.readBytes())
                assertTrue(DiarizationCheckpointStore.load(ctx,key)!!.finished)
                var firstRemaining=0
                val resumed=SpeakerDiarizer.diarize(segments,emptyMap(),embedder,pcm,ctx,
                    processing=SpeakerDiarizer.EvidenceProcessing(resume=checkpoint.evidence,processedChunks=checkpoint.processed,
                        workers=chosen,checkpointEveryChunks=1,checkpoint={ p,_,_->if(firstRemaining==0)firstRemaining=p }))
                assertEquals(3,firstRemaining)
                assertEquals(baseline!!.turns,resumed.turns)
                assertArrayEquals(baseline!!.speakers,resumed.speakers)
                assertEquals(baseline!!.text,resumed.text)
                assertNull(DiarizationCheckpointStore.load(ctx,key.copy(audioModified=key.audioModified+1)))
                assertTrue(DiarizationCheckpointStore.save(ctx,checkpoint,DiarizationEvidenceCache.ticket(ts)))
                // Ciphertext tampering fails closed; no plaintext fallback.
                val encrypted=File(ctx.noBackupFilesDir,"diarization/$ts.aead")
                val raw=encrypted.readBytes();raw[raw.lastIndex]=(raw.last().toInt() xor 1).toByte();encrypted.writeBytes(raw)
                assertNull(DiarizationCheckpointStore.load(ctx,key))
                report.put("resumed_from_chunk",2).put("resume_identical",true).put("tamper_rejected",true)
            } finally { DiarizationCheckpointStore.remove(ctx,ts);DiarizationEvidenceCache.remove(ts) }
        } finally { embedder.release() }
        File(dir,"out").mkdirs();File(dir,"out/quality-audit.json").writeText(report.toString(2))
    }
    @Test fun spinningComparison() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("spinAudit") == "true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval")
        val bytes=File(dir,"diar/tts_one_minute.s16").readBytes();val pcm=ShortArray(bytes.size/2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val duration=pcm.size*1000L/16000
        val segments=(0L until duration step 100).map{WhisperHelper.Segment("probe",it,minOf(it+100,duration))}
        val workers=DiarizationRuntimePolicy.workers(ctx);val threads=DiarizationRuntimePolicy.threads(ctx)
        val runs=JSONArray();val elapsedByMode=mutableMapOf<Boolean,MutableList<Long>>()
        var expected:SpeakerDiarizer.Result?=null
        for (spinning in listOf(true,false,false,true)) {
            val embedder=SpeakerEmbedder(ctx);assertTrue(embedder.load(spinning,threads))
            val start=SystemClock.elapsedRealtime()
            val result=try { SpeakerDiarizer.diarize(segments,emptyMap(),embedder,pcm,ctx,
                processing=SpeakerDiarizer.EvidenceProcessing(workers=workers,allowSpinning=spinning,onnxThreads=threads)) }
                finally { embedder.release() }
            val ms=SystemClock.elapsedRealtime()-start
            if(expected==null)expected=result else {
                assertEquals(expected!!.turns,result.turns);assertArrayEquals(expected!!.speakers,result.speakers)
            }
            elapsedByMode.getOrPut(spinning){mutableListOf()}.add(ms)
            runs.put(JSONObject().put("spinning",spinning).put("workers",workers).put("ms",ms)
                .put("battery_temp_tenths_c",ctx.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature",-1)))
            android.util.Log.i("DiarQuality","spinning=$spinning workers=$workers ms=$ms")
        }
        val spinning=elapsedByMode.getValue(true).average()
        val sleeping=elapsedByMode.getValue(false).average()
        val useSpinning=spinning <= sleeping*1.05
        DiarizationRuntimePolicy.saveMeasured(ctx,workers,useSpinning,threads)
        File(dir,"out/spinning-audit.json").writeText(JSONObject().put("runs",runs).put("chosen_spinning",useSpinning).put("identical",true).toString(2))
    }

    @Test fun onnxThreadComparison() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("threadAudit") == "true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval")
        val chosenFixture=InstrumentationRegistry.getArguments().getString("threadFixture") ?: "tts_one_minute.s16"
        require(chosenFixture.matches(Regex("[A-Za-z0-9_-]+\\.s16")))
        val bytes=File(dir,"diar/$chosenFixture").readBytes();val pcm=ShortArray(bytes.size/2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val duration=pcm.size*1000L/16000
        val segments=(0L until duration step 100).map{WhisperHelper.Segment("probe",it,minOf(it+100,duration))}
        val workers=DiarizationRuntimePolicy.workers(ctx);val spinning=DiarizationRuntimePolicy.spinning(ctx)
        val runs=JSONArray();val timings=mutableMapOf<Int,MutableList<Long>>()
        var expected:SpeakerDiarizer.Result?=null
        for (threads in listOf(2,4,4,2)) {
            val embedder=SpeakerEmbedder(ctx);assertTrue(embedder.load(spinning,threads))
            val start=SystemClock.elapsedRealtime()
            val result=try { SpeakerDiarizer.diarize(segments,emptyMap(),embedder,pcm,ctx,
                processing=SpeakerDiarizer.EvidenceProcessing(workers=workers,allowSpinning=spinning,onnxThreads=threads)) }
                finally { embedder.release() }
            val ms=SystemClock.elapsedRealtime()-start
            if(expected==null)expected=result else {
                assertEquals(expected!!.turns,result.turns);assertArrayEquals(expected!!.speakers,result.speakers)
            }
            timings.getOrPut(threads){mutableListOf()}.add(ms)
            runs.put(JSONObject().put("threads",threads).put("workers",workers).put("ms",ms).put("pss_kib",Debug.getPss())
                .put("battery_temp_tenths_c",ctx.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature",-1)))
            android.util.Log.i("DiarQuality","threads=$threads workers=$workers ms=$ms")
        }
        val chosen=if(timings.getValue(4).average()<timings.getValue(2).average()*0.95)4 else 2
        DiarizationRuntimePolicy.saveMeasured(ctx,workers,spinning,chosen)
        File(dir,"out/threads-audit.json").writeText(JSONObject().put("runs",runs).put("chosen_threads",chosen)
            .put("fixture",chosenFixture).put("audio_ms",duration).put("identical",true).toString(2))
    }

}
