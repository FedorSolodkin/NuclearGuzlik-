package com.example.voicemind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit local-fixture opt-in. Writes a review copy only into the debug package. */
@RunWith(AndroidJUnit4::class)
class DiarizationReplayDeviceTest {
    @Test fun replayAndJobRunner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e historyReplay true", args.getString("historyReplay") == "true")
        assertTrue("Debug package required", ctx.packageName.endsWith(".dev"))
        val dir = File(ctx.getExternalFilesDir(null), "eval/history")
        val gson = Gson()
        val evidence = File(dir,"evidence.json").bufferedReader().use { gson.fromJson(it, SpeakerDiarizer.EvidenceSnapshot::class.java) }
        val segments = File(dir,"segments.json").bufferedReader().use { gson.fromJson(it, Array<WhisperHelper.Segment>::class.java).toList() }
        val expected = JSONObject(File(dir,args.getString("expected") ?: "expected-v2.json").readText())
        val durations = JSONArray()
        var result: SpeakerDiarizer.Result? = null
        repeat(2) {
            val start = android.os.SystemClock.elapsedRealtime()
            result = SpeakerDiarizer.diarize(segments, emptyMap(), null, context=ctx,
                onEvidenceReady={ assertSame("Cached evidence must be reused", evidence, it) }, cachedEvidence=evidence)
            durations.put(android.os.SystemClock.elapsedRealtime()-start)
        }
        val final = result!!
        val sha = MessageDigest.getInstance("SHA-256").digest(final.text.lineSequence().map { it.trimEnd() }.joinToString("\n").trim().toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("Phone/host full transcript differs", expected.getString("text_sha256"),sha)
        assertEquals(expected.getInt("speakers"),final.displayNames.size)
        assertEquals(expected.getInt("units"),final.units.size)
        val report = JSONObject().put("core_replay_ms",durations).put("speakers",final.displayNames.size)
            .put("units",final.units.size).put("host_text_matches",true)
        if (args.getString("importReviewCopy") == "true") {
            val audio = File(dir,"original.mp3")
            assertTrue("Review audio missing",audio.exists())
            val prefs = ctx.getSharedPreferences("diarization_audit",0)
            var timestamp = prefs.getLong("review_timestamp",0)
            if (timestamp == 0L || RecordStorage.findEntryByTimestamp(ctx,timestamp) == null) {
                timestamp = RecordStorage.addToToday(ctx,final.text,"text",null,audio.absolutePath,linkPendingEvent=false)
                prefs.edit().putLong("review_timestamp",timestamp).commit()
            }
            RecordStorage.updateEntrySegments(ctx,timestamp,segments)
            SpeakerCentroidStorage.putFromResult(ctx,timestamp,final)
            val key = DiarizationEvidenceCache.keyFor(ctx,audio,timestamp)
            val checkpoint = DiarizationCheckpointCodec.Checkpoint(key,evidence.windows.size,evidence.windows.size,evidence,hadLabels=true,
                expectedTextHash=DiarizationCheckpointCodec.textFingerprint(RecordStorage.textSnapshot(ctx,timestamp)!!))
            assertTrue(DiarizationCheckpointStore.save(ctx,checkpoint,DiarizationEvidenceCache.ticket(timestamp)))
            if (args.getString("seedOnly") == "true") {
                File(dir,"seed-validation.json").writeText(report.put("seeded",true).put("review_timestamp",timestamp).toString(2))
                return
            }
            DiarizationEvidenceCache.clearAll()
            repeat(2) { pass ->
                val done = CountDownLatch(1)
                var outcome: DiarizationJobRunner.State? = null
                var unexpectedInference = false
                val listener: (DiarizationJobRunner.State) -> Boolean = { state ->
                    if (state.timestamp == timestamp) {
                        if (state is DiarizationJobRunner.State.Running && state.stage == "Диаризация...") {
                            unexpectedInference = true; DiarizationJobRunner.cancel(timestamp)
                        }
                        if (state !is DiarizationJobRunner.State.Running) { outcome=state;done.countDown() }
                    }
                    true
                }
                DiarizationJobRunner.addListener(listener)
                val start = android.os.SystemClock.elapsedRealtime()
                try {
                    DiarizationJobRunner.start(ctx,audio,timestamp,null,segments,null,true)
                    assertTrue("Job runner timeout",done.await(30,TimeUnit.SECONDS))
                    assertFalse("Job runner unexpectedly invoked models",unexpectedInference)
                    assertTrue("Job runner did not finish: $outcome",outcome is DiarizationJobRunner.State.Done)
                    report.put(if(pass==0) "job_runner_ms" else "warm_job_runner_ms",android.os.SystemClock.elapsedRealtime()-start)
                    val deadline=android.os.SystemClock.elapsedRealtime()+5000
                    while(DiarizationJobRunner.hasActiveJob(timestamp) && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(10)
                    assertFalse("Job did not release resources",DiarizationJobRunner.hasActiveJob(timestamp))
                    DiarizationJobRunner.consume(timestamp)
                } finally { DiarizationJobRunner.removeListener(listener) }
            }
            report.put("review_timestamp",timestamp)
            val saved = RecordStorage.findEntryByTimestamp(ctx,timestamp)!!.second
            assertEquals(TextFormatter.format(final.text),saved.text)
            assertTrue(DiarizationCheckpointStore.load(ctx,key)!!.finished)
            report.put("durable_cache_hit",true)
        }
        File(dir,"validation.json").writeText(report.toString(2))
    }
    @Test fun seedRecoveryFromCompletedCheckpoint() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("historySeed")=="true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val ts=ctx.getSharedPreferences("diarization_audit",0).getLong("review_timestamp",0)
        assertTrue(ts>0)
        val audio=File(ctx.getExternalFilesDir(null),"eval/history/original.mp3")
        val key=DiarizationEvidenceCache.keyFor(ctx,audio,ts)
        val completed=DiarizationCheckpointStore.load(ctx,key)!!
        assertTrue(completed.finished)
        assertTrue(DiarizationCheckpointStore.save(ctx,completed.copy(finished=false,
            expectedTextHash=DiarizationCheckpointCodec.textFingerprint(RecordStorage.textSnapshot(ctx,ts)!!)),DiarizationEvidenceCache.ticket(ts)))
        assertTrue(DiarizationCheckpointStore.hasPending(ctx,ts))
    }
    @Test fun recoverPersistedJobInFreshProcess() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("historyRecovery") == "true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val timestamp=ctx.getSharedPreferences("diarization_audit",0).getLong("review_timestamp",0)
        assertTrue(timestamp>0)
        val dir=File(ctx.getExternalFilesDir(null),"eval/history")
        val audio=File(dir,"original.mp3")
        val key=DiarizationEvidenceCache.keyFor(ctx,audio,timestamp)
        val checkpoint=DiarizationCheckpointStore.load(ctx,key)!!
        assertFalse(checkpoint.finished)
        assertNull(DiarizationEvidenceCache.get(key,DiarizationEvidenceCache.ticket(timestamp)))
        val done=CountDownLatch(1);var outcome:DiarizationJobRunner.State?=null
        var invokedModels=false
        val listener:(DiarizationJobRunner.State)->Boolean={ state ->
            if(state.timestamp==timestamp){
                if(state is DiarizationJobRunner.State.Running && state.stage=="Диаризация..."){
                    invokedModels=true;DiarizationJobRunner.cancel(timestamp)
                }
                if(state !is DiarizationJobRunner.State.Running){outcome=state;done.countDown()}
            }
            true
        }
        DiarizationJobRunner.addListener(listener)
        val start=android.os.SystemClock.elapsedRealtime()
        try {
            kotlinx.coroutines.runBlocking { DiarizationJobRunner.resumeInterrupted(ctx) }
            assertTrue("Recovery timeout",done.await(45,TimeUnit.SECONDS))
            assertFalse("Unexpected full inference",invokedModels)
            assertTrue("Recovery failed: $outcome",outcome is DiarizationJobRunner.State.Done)
            assertEquals(3,(outcome as DiarizationJobRunner.State.Done).speakers)
            assertTrue(DiarizationCheckpointStore.load(ctx,key)!!.finished)
            val expected=JSONObject(File(dir,"expected-v2.json").readText())
            val saved=RecordStorage.findEntryByTimestamp(ctx,timestamp)!!.second
            // Formatting may adjust punctuation; compare against exactly the same core result.
            val segments=Gson().fromJson(File(dir,"segments.json").readText(),Array<WhisperHelper.Segment>::class.java).toList()
            val result=SpeakerDiarizer.diarize(segments,emptyMap(),null,context=ctx,cachedEvidence=checkpoint.evidence)
            assertEquals(TextFormatter.format(result.text),saved.text)
            assertEquals(expected.getInt("units"),result.units.size)
            File(dir,"recovery-validation.json").writeText(JSONObject().put("fresh_process_recovery",true)
                .put("no_models_loaded",true).put("saved_text_matches",true).put("ms",android.os.SystemClock.elapsedRealtime()-start).toString(2))
        } finally { DiarizationJobRunner.removeListener(listener) }
    }

}
