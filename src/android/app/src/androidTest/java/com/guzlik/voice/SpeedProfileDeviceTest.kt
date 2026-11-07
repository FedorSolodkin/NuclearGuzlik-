package com.guzlik.voice

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Профиль конвейера «импорт файла → расшифровка → разделение голосов → JSON» по стадиям.
 * Вход: <externalFilesDir>/eval/audio (файлы wav) (кладёт adb push), выход: eval/out/<имя>.json и
 * <имя>.timing.json. Повторяет путь AudioImporter + DiarizationJobRunner без UI.
 * Включается аргументом -e speedProfile true; -e only <подстрока имени>.
 */
@RunWith(AndroidJUnit4::class)
class SpeedProfileDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val tag = "SpeedProfile"
    private fun now() = SystemClock.elapsedRealtime()

    @Test fun profile() {
        assumeTrue(args.getString("speedProfile") == "true")
        val inDir = File(ctx.filesDir, "eval/audio")
        val outDir = File(ctx.filesDir, "eval/out").apply { mkdirs() }
        val only = args.getString("only")
        val files = inDir.listFiles { f -> f.name.endsWith(".wav") && (only == null || only.split(",").any { f.name.contains(it) }) }
            ?.sortedBy { it.name }.orEmpty()
        assumeTrue("no audio", files.isNotEmpty())

        if (BundledModels.pending(ctx).isNotEmpty()) assertTrue(BundledModels.install(ctx) { _, _, _ -> } is BundledModels.Result.Ok)
        val whisper = WhisperHelper(ctx)
        val ready = CountDownLatch(1)
        var err: String? = null
        val tInit = now()
        whisper.init({ ready.countDown() }, { err = it; ready.countDown() })
        assertTrue(ready.await(10, TimeUnit.MINUTES)); assertTrue("init: $err", err == null && whisper.isReady)
        Log.i(tag, "whisper init ${now() - tInit} ms")

        for (f in files) {
            val name = f.nameWithoutExtension
            val t = JSONObject()
            val tStart = now()
            check(whisper.retain())
            val session = TranscriptionSession(whisper = whisper, embedder = null, context = ctx,
                glossaryPrompt = Glossary.buildPrompt(ctx), onChunkReady = { _, _ -> }, onProcessingChanged = {}, persistWal = false)
            var decodeMs = 0L
            val samples = try {
                AudioDecoder.decodeStreaming(f) { frame ->
                    val a = now()
                    while (session.pendingChunks() >= 2) Thread.sleep(50)
                    session.pushAudio(frame)
                    decodeMs += 0
                }
            } finally { }
            val audioMs = samples * 1000L / 16000
            val tPushed = now()
            var segments: List<WhisperHelper.Segment> = emptyList()
            session.flushAndWait(onDone = { _, segs, _ -> segments = segs })
            session.release(); session.awaitStopped(); whisper.releaseUse()
            val tAsr = now()
            t.put("audio_ms", audioMs).put("asr_total_ms", tAsr - tStart).put("segments", segments.size)
            Log.i(tag, "$name ASR ${tAsr - tStart} ms for $audioMs ms audio (rtf ${(tAsr - tStart).toDouble() / audioMs})")

            // Разделение голосов: как DiarizationJobRunner (workers по политике, затем уточнение слов у смены голоса).
            var result: SpeakerDiarizer.Result? = null
            var diarMs = 0L; var refineMs = 0L
            if (segments.isNotEmpty() && args.getString("noDiar") != "true") {
                val pcm = AudioDecoder.decode(f)
                val embedder = SpeakerEmbedder(ctx)
                assertTrue(embedder.load())
                val d0 = now()
                try {
                    result = SpeakerDiarizer.diarize(segments, emptyMap(), embedder, pcm, ctx, null,
                        processing = SpeakerDiarizer.EvidenceProcessing(workers = DiarizationRuntimePolicy.workers(ctx)))
                } finally { embedder.release() }
                diarMs = now() - d0
                val r0 = now()
                val res = result
                if (res != null && res.centroids.size > 1 && whisper.retain()) {
                    try {
                        val clips = BoundaryWordTiming.plan(segments, res.turns, audioMs)
                        if (clips.isNotEmpty()) whisper.withWordTimingSession(WhisperHelper.newCancelToken()) { refine ->
                            for (clip in clips) {
                                val refined = refine(pcm.copyOfRange((clip.startMs * 16).toInt(), minOf(pcm.size, (clip.endMs * 16).toInt())))
                                    .map { it.copy(startMs = it.startMs + clip.startMs, endMs = it.endMs + clip.startMs) }
                                segments = BoundaryWordTiming.apply(segments, clip, refined, res.turns)
                            }
                        }
                        if (clips.isNotEmpty()) result = SpeakerDiarizer.reassignWords(segments, res)
                    } finally { whisper.releaseUse() }
                }
                refineMs = now() - r0
            }
            val e0 = now()
            val res = result
            val json = if (res != null) JsonTranscriptExporter.export(res.text, segments, res.displayNames)
                else JsonTranscriptExporter.export(segments.joinToString(" ") { it.text }, segments, listOf("1"))
            File(outDir, "$name.json").writeText(json)
            t.put("diar_ms", diarMs).put("refine_ms", refineMs).put("export_ms", now() - e0)
                .put("speakers", res?.centroids?.size ?: 0).put("total_ms", now() - tStart)
                .put("rtf_total", (now() - tStart).toDouble() / audioMs)
            File(outDir, "$name.timing.json").writeText(t.toString())
            Log.i(tag, "$name DONE $t")
        }
    }
}
