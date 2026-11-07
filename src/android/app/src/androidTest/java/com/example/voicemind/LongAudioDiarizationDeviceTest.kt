package com.example.voicemind

import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit local long-recording evaluation, with bounded PCM memory and resumable extraction. */
@RunWith(AndroidJUnit4::class)
class LongAudioDiarizationDeviceTest {
    @Test fun compareDecoderSourceLocations() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("decoderAudit")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;assertTrue(ctx.packageName.endsWith(".dev"))
        val dir=File(ctx.getExternalFilesDir(null),"eval/acoustic/interview-2026-11-04")
        val external=File(dir,"source.m4a");val internal=File(ctx.cacheDir,"long-audit-source.m4a")
        if(!internal.exists() || internal.length()!=external.length())external.copyTo(internal,true)
        val token=RecordingService.processingStarted(ctx)
        val runs=mutableListOf<Map<String,Any>>()
        var expected:ByteArray?=null
        try {
            val legacy=File(dir,"decoder-baseline-prefix.pcm")
            val legacyHash=if(legacy.exists()){
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                legacy.inputStream().use{input->
                    var remaining=60*16000*2;val bytes=ByteArray(65536)
                    while(remaining>0){val n=input.read(bytes,0,minOf(bytes.size,remaining));check(n>0);digest.update(bytes,0,n);remaining-=n}
                }
                digest.digest()
            }else null
            for(source in listOf(external,internal)){
                var samples=0L;val digest=java.security.MessageDigest.getInstance("SHA-256");val start=SystemClock.elapsedRealtime()
                try {
                    AudioDecoder.decodeStreaming(source){frame->
                        val bytes=java.nio.ByteBuffer.allocate(frame.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bytes.asShortBuffer().put(frame);digest.update(bytes.array());samples+=frame.size
                        if(samples>=60*16000)throw java.util.concurrent.CancellationException("Measured prefix complete")
                    }
                }catch(_:java.util.concurrent.CancellationException){}
                assertEquals(60*16000L,samples)
                val hash=digest.digest();if(legacyHash!=null)assertArrayEquals("Legacy decoder PCM changed",legacyHash,hash)
                if(expected==null)expected=hash else assertArrayEquals(expected,hash)
                runs.add(mapOf("internal" to (source==internal),"ms" to (SystemClock.elapsedRealtime()-start),"samples" to samples))
            }
            File(dir,"decoder-source-audit.json").writeText(Gson().toJson(runs))
        }finally{RecordingService.processingFinished(ctx,token)}
    }

    @Test fun extractWholeRecording() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("longAudio")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ctx.packageName.endsWith(".dev"))
        val name=args.getString("fixture") ?: error("fixture required")
        require(name.matches(Regex("[A-Za-z0-9_-]+")))
        val dir=File(ctx.getExternalFilesDir(null),"eval/acoustic/$name")
        val source=File(dir,"source.m4a");assertTrue(source.isFile)
        val gson=Gson();val wall=SystemClock.elapsedRealtime()
        fun temperature()=ctx.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature",-1)
        fun status(stage:String,percent:Int=0) {
            File(dir,"long-status.json").writeText(JSONObject().put("stage",stage).put("percent",percent)
                .put("elapsed_ms",SystemClock.elapsedRealtime()-wall).put("pss_kib",Debug.getPss())
                .put("battery_temp_tenths_c",temperature()).toString())
            Log.i("LongDiarAudit","$stage $percent%; elapsed=${SystemClock.elapsedRealtime()-wall}ms")
        }
        val prefs=ctx.getSharedPreferences("long_diar_audit",0)
        var ts=prefs.getLong(name,0)
        if(ts==0L){ts=System.currentTimeMillis();prefs.edit().putLong(name,ts).commit()}
        val key=DiarizationEvidenceCache.keyFor(ctx,source,ts)
        val ticket=DiarizationEvidenceCache.ticket(ts)
        val resume=DiarizationCheckpointStore.load(ctx,key)
        val token=RecordingService.processingStarted(ctx)
        var pcm:FilePcm?=null
        val embedder=SpeakerEmbedder(ctx)
        try {
            val predecoded=args.getString("predecoded")=="true"
            status(if(predecoded)"copy_pcm" else "decode")
            if(predecoded){
                val raw=File(dir,"source.s16");assertTrue(raw.isFile)
                val temporary=File(ctx.cacheDir,"long-audit-input.pcm")
                raw.copyTo(temporary,true);pcm=FilePcm(temporary)
            }else{
            // Production imports live in internal app storage. Avoid doing tiny
            // MediaExtractor reads through the external-storage FUSE bridge.
            val decodeSource=File(ctx.cacheDir,"long-audit-source.m4a")
            if(!decodeSource.exists() || decodeSource.length()!=source.length())source.copyTo(decodeSource,true)
            val metadata=android.media.MediaMetadataRetriever()
            val expectedDuration=try {metadata.setDataSource(decodeSource.absolutePath);metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L} finally {metadata.release()}
            var decodedFrames=0L;var lastDecodeReport=SystemClock.elapsedRealtime()
            pcm=AudioDecoder.decodeToFile(decodeSource,ctx.cacheDir){
                decodedFrames++
                if(SystemClock.elapsedRealtime()-lastDecodeReport>=10_000){
                    status("decode",if(expectedDuration>0)(decodedFrames*100*100/expectedDuration).coerceAtMost(99).toInt() else 0)
                    lastDecodeReport=SystemClock.elapsedRealtime()
                }
                false
            }
            }
            val preparationMs=SystemClock.elapsedRealtime()-wall
            val decodeMs=if(predecoded)0 else preparationMs
            val duration=pcm.size*1000L/16000
            val prefix=File(dir,"decoder-baseline-prefix.pcm")
            if(prefix.exists() && !predecoded) {
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                val expectedHash=prefix.inputStream().buffered().use {input->
                    val bytes=ByteArray(65536)
                    while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}
                    digest.digest()
                }
                var offset=0
                while(offset<prefix.length()/2){
                    val end=minOf((prefix.length()/2).toInt(),offset+32768)
                    val bytes=java.nio.ByteBuffer.allocate((end-offset)*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(pcm.copyOfRange(offset,end));digest.update(bytes.array());offset=end
                }
                assertArrayEquals("Decoder optimization changed PCM",expectedHash,digest.digest())
                File(dir,"decoder-parity.json").writeText(JSONObject().put("identical",true).put("prefix_bytes",prefix.length())
                    .put("decode_ms",decodeMs).put("audio_ms",duration).toString(2))
            }
            val probes=(0L until duration step 100L).map{WhisperHelper.Segment("probe",it,minOf(it+100,duration))}
            assertTrue(embedder.load())
            val inferenceStart=SystemClock.elapsedRealtime()
            var peakPss=Debug.getPss();var peakTemp=temperature() ?: -1
            val result=SpeakerDiarizer.diarize(probes,emptyMap(),embedder,pcm,ctx,
                onProgress={percent->
                    peakPss=maxOf(peakPss,Debug.getPss());peakTemp=maxOf(peakTemp,temperature() ?: -1)
                    status("inference",percent)
                },onEvidenceReady={evidence->
                    File(dir,"phone-full-evidence.json").bufferedWriter().use{gson.toJson(evidence,it)}
                },processing=SpeakerDiarizer.EvidenceProcessing(resume=resume?.evidence,processedChunks=resume?.processed ?: 0,
                    workers=DiarizationRuntimePolicy.workers(ctx),checkpointEveryChunks=50,
                    checkpoint={processed,total,evidence->
                        assertTrue(DiarizationCheckpointStore.save(ctx,DiarizationCheckpointCodec.Checkpoint(key,processed,total,evidence),ticket))
                    }))
            val inferenceMs=SystemClock.elapsedRealtime()-inferenceStart
            assertTrue(result.centroids.isNotEmpty());assertTrue(result.turns.isNotEmpty())
            assertEquals(probes.size,result.speakers.size)
            val report=mapOf("audio_ms" to duration,"speakers" to result.centroids.size,"requested_speakers" to null,
                "input_predecoded" to predecoded,"input_copy_ms" to (if(predecoded)preparationMs else 0),"decode_ms" to decodeMs,"inference_and_clustering_ms" to inferenceMs,"total_ms" to SystemClock.elapsedRealtime()-wall,
                "rtf" to inferenceMs.toDouble()/duration,"resumed_chunks" to (resume?.processed ?: 0),
                "peak_pss_kib" to peakPss,"peak_battery_temp_tenths_c" to peakTemp,
                "turns" to result.turns,"centroids" to result.centroids,"preview_ms" to result.previewOffsetsMs,
                "probe_counts" to result.speakers.toList().groupingBy{it}.eachCount(),"probe_assignments" to result.speakers,
                "probe_step_ms" to 100,"full_phone_inference" to true)
            File(dir,"phone-full-result.json").bufferedWriter().use{gson.toJson(report,it)}
            DiarizationCheckpointStore.complete(ctx,key,ticket)
            status("done",100)
        } finally {embedder.release();pcm?.close();RecordingService.processingFinished(ctx,token)}
    }
}
