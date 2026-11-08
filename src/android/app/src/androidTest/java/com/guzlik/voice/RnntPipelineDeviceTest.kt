package com.guzlik.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public fixtures only; isolated .dev package, no user recordings or global model settings. */
@RunWith(AndroidJUnit4::class)
class RnntPipelineDeviceTest {
    @Test fun silenceAndPrecancelledSpeechDoNotProduceText() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rnntPipeline") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".dev"))
        val engine = GigaAmEngine(ctx)
        try {
            val ready = CountDownLatch(1)
            var initError: String? = null
            engine.init({ ready.countDown() }, { initError = it; ready.countDown() })
            assertTrue(ready.await(90, TimeUnit.SECONDS)); assertNull(initError)
            assertTrue("Silence produced hallucinated text", engine.transcribeSegments(ShortArray(5 * 16000),
                useVad = true, cancelToken = WhisperHelper.newCancelToken()).isEmpty())
            val pcm = AudioDecoder.decode(File(ctx.filesDir, "comparison/ru_meeting_2spk.wav"))
            val token = WhisperHelper.newCancelToken()
            engine.abort(token)
            assertTrue("Precancelled audio was decoded", engine.transcribeSegments(pcm,
                useVad = true, cancelToken = token).isEmpty())
        } finally { engine.release() }
    }

    @Test fun monologueAndDialogueExportWithAutomaticAndExplicitCounts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("rnntPipeline") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".dev"))
        val fixture = File(ctx.filesDir, "comparison/ru_meeting_2spk.wav")
        require(fixture.isFile)
        val full = AudioDecoder.decode(fixture)
        // Public reference: first speaker ends at 4779 ms; the second starts at 5279 ms.
        val cases = listOf("monologue" to full.copyOfRange(0, 5000 * 16), "dialogue" to full)
        val out = File(ctx.filesDir, "comparison/pipeline").apply { mkdirs() }
        val marker = File(ctx.filesDir, "use_gigaam")
        val markerExisted = marker.exists()
        if (!markerExisted) marker.writeText("")
        val asr = WhisperHelper(ctx)
        val embedder = SpeakerEmbedder(ctx)
        val failures = mutableListOf<String>()
        try {
            val loaded = CountDownLatch(1)
            var initError: String? = null
            asr.init({ loaded.countDown() }, { initError = it; loaded.countDown() })
            assertTrue("RNNT initialization timeout", loaded.await(90, TimeUnit.SECONDS))
            assertNull(initError)
            assertEquals("gigaam-v3-transducer-punct-int8", asr.loadedModelName)
            assertTrue("Speaker model unavailable", embedder.load())
            for ((id, pcm) in cases) {
                val asrStart = SystemClock.elapsedRealtime()
                val segments = asr.transcribeSegments(PcmNormalizer.normalize(pcm), language = AppPrefs.LANG_RU)
                val asrMs = SystemClock.elapsedRealtime() - asrStart
                assertTrue("No ASR text for $id", segments.isNotEmpty())
                val expected = if (id == "monologue") 1 else 2
                for (count in listOf<Int?>(null, expected)) {
                    val mode = if (count == null) "auto" else "fixed$count"
                    val start = SystemClock.elapsedRealtime()
                    val result = SpeakerDiarizer.diarize(segments, emptyMap(), embedder, pcm, ctx, numSpeakers = count)
                    val diarMs = SystemClock.elapsedRealtime() - start
                    val report = linkedMapOf<String, Any?>("case" to id, "mode" to mode,
                        "audio_ms" to pcm.size / 16L, "asr_ms" to asrMs, "diar_ms" to diarMs,
                        "expected_speakers" to expected, "found_speakers" to result.centroids.size,
                        "text" to result.text, "turns" to result.turns, "segments" to segments,
                        "units" to result.units, "speakers" to result.speakers,
                        "raw_speakers" to result.rawSpeakers)
                    try {
                        val document = JSONObject(JsonTranscriptExporter.export(result.text, segments, result.displayNames))
                        report["export"] = document.toString()
                        assertEquals(setOf("transcript"), document.keys().asSequence().toSet())
                        val turns = document.getJSONArray("transcript")
                        assertTrue("Empty transcript $id/$mode", turns.length() > 0)
                        val texts = mutableListOf<String>()
                        for (i in 0 until turns.length()) {
                            val turn = turns.getJSONObject(i)
                            assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), turn.keys().asSequence().toSet())
                            assertTrue("Noncanonical speaker ID: $id/$mode", turn.getString("speaker_id").matches(Regex("speaker_[1-9][0-9]*")))
                            assertTrue(turn.getLong("start_ms") >= 0)
                            assertTrue(turn.getLong("end_ms") > turn.getLong("start_ms"))
                            assertTrue(turn.getLong("end_ms") <= pcm.size / 16L)
                            texts += turn.getString("text")
                        }
                        fun words(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.toList()
                        assertEquals("Diarization/export lost or repeated ASR words: $id/$mode",
                            words(segments.joinToString(" ") { it.text }), words(texts.joinToString(" ")))
                        assertEquals("Speaker count: $id/$mode", expected, result.centroids.size)
                        report["passed"] = true
                    } catch (e: Throwable) {
                        report["passed"] = false
                        report["error"] = "${e.javaClass.simpleName}: ${e.message}"
                        failures += "$id/$mode: ${e.message}"
                    } finally {
                        File(out, "$id.$mode.json").writeText(Gson().toJson(report))
                    }
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            embedder.release()
            asr.release()
            if (!markerExisted) marker.delete()
        }
    }
}
