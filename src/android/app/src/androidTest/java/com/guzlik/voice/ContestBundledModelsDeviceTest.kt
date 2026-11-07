package com.guzlik.voice

import android.content.Context
import android.content.ContextWrapper
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

/** Opt-in offline audit in a clean model directory, without changing user recordings. */
@RunWith(AndroidJUnit4::class)
class ContestBundledModelsDeviceTest {
    @Test fun bootstrapAndInferUsingOnlyModelsInsideApk() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("contestBundleAudit") == "true")
        assertTrue(BuildConfig.CONTEST_BUILD)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".hackathon"))
        assertEquals("Audit requires airplane mode", 1, android.provider.Settings.Global.getInt(
            ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0))
        val source = File(requireNotNull(args.getString("auditAudioPath")))
        require(source.isFile)
        val root = File(ctx.cacheDir, "contest-clean-model-audit").apply { deleteRecursively(); mkdirs() }
        val prefs = mutableSetOf<String>()
        val clean = object : ContextWrapper(ctx) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("contest_audit_$name".also { prefs.add(it) }, mode)
        }
        var whisper: WhisperHelper? = null
        var llm: LlmHelper? = null
        fun now() = android.os.SystemClock.elapsedRealtime()
        try {
            val expected = setOf(WhisperCppEngine.PODLODKA_TURBO_FILE, LlmModels.GEMMA_TRIM.fileName,
                SpeakerEmbedder.MODEL_NAME, PyannoteSegmenter.MODEL_NAME, SileroVAD.MODEL_NAME)
            assertEquals(expected, BundledModels.assetNames(clean).toSet())
            assertEquals(expected, BundledModels.pending(clean).toSet())
            val installStart = now()
            assertTrue(BundledModels.install(clean) { _, _, _ -> } is BundledModels.Result.Ok)
            val installMs = now() - installStart
            assertTrue(BundledModels.pending(clean).isEmpty())
            expected.forEach { name ->
                assertEquals("Copied asset hash mismatch: $name", name,
                    ModelIntegrity.identify(ModelIntegrity.sha256(File(clean.filesDir, name))))
            }
            AppPrefs.setWhisperModel(clean, AppPrefs.MODEL_TINY)
            assertEquals(AppPrefs.MODEL_ACCURATE, AppPrefs.getWhisperModel(clean))
            AppPrefs.setLlmModelId(clean, LlmModels.QWEN_FT.id)
            assertEquals(listOf(LlmModels.GEMMA_TRIM), LlmModels.SELECTABLE)
            assertEquals(LlmModels.GEMMA_TRIM, LlmModels.selected(clean))
            AppPrefs.setWhisperGpuEnabled(clean, false)
            AppPrefs.setLlmGpuEnabled(clean, false)
            val ready = CountDownLatch(1)
            var failure: String? = null
            val asr = WhisperHelper(clean).also { whisper = it }
            asr.init({ ready.countDown() }, { failure = it; ready.countDown() })
            assertTrue(ready.await(90, TimeUnit.SECONDS)); assertNull(failure)
            assertEquals(WhisperCppEngine.PODLODKA_TURBO_FILE, asr.loadedModelName)
            val audio = AudioDecoder.decode(source)
            val transcriptionStart = now()
            var segments = asr.transcribeSegments(audio, language = AppPrefs.LANG_RU)
            assertTrue("Bundled ASR returned no words", segments.any { it.text.isNotBlank() })
            val embedder = SpeakerEmbedder(clean)
            assertTrue(embedder.load())
            val base = try { SpeakerDiarizer.diarize(segments, emptyMap(), embedder, audio, clean) }
                finally { embedder.release() }
            args.getString("expectedSpeakers")?.toInt()?.let { assertEquals(it, base.centroids.size) }
            val clips = BoundaryWordTiming.plan(segments, base.turns, audio.size / 16L)
            if (base.centroids.size > 1 && clips.isNotEmpty()) asr.withWordTimingSession(WhisperHelper.newCancelToken()) { refine ->
                for (clip in clips) segments = BoundaryWordTiming.apply(segments, clip,
                    refine(audio.copyOfRange((clip.startMs * 16).toInt(), (clip.endMs * 16).toInt()))
                        .map { it.copy(startMs = it.startMs + clip.startMs, endMs = it.endMs + clip.startMs) }, base.turns)
            }
            val result = SpeakerDiarizer.reassignWords(segments, base)
            val document = JSONObject(JsonTranscriptExporter.export(result.text, segments, result.displayNames))
            assertEquals(setOf("transcript"), document.keys().asSequence().toSet())
            val turns = document.getJSONArray("transcript")
            assertTrue(turns.length() > 0)
            for (i in 0 until turns.length()) {
                val turn = turns.getJSONObject(i)
                assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), turn.keys().asSequence().toSet())
                assertTrue(turn.getLong("end_ms") > turn.getLong("start_ms"))
            }
            val transcriptionMs = now() - transcriptionStart
            asr.release(); whisper = null
            val assistant = LlmHelper(clean).also { llm = it }
            assertTrue("Bundled Gemma failed to load", assistant.load())
            assertEquals(LlmModels.GEMMA_TRIM.fileName, assistant.loadedModelName)
            val llmStart = now()
            val answer = assistant.askQuestion("На встрече решили провести запуск в пятницу.",
                "В какой день недели запланирован запуск? Ответь одним словом.")
            assertTrue("Gemma did not answer the question", answer.lowercase().contains("пятниц"))
            val dir = File(ctx.noBackupFilesDir, "record-diarization-debug").apply { mkdirs() }
            File(dir, "contest-bundle.json").writeText(Gson().toJson(mapOf(
                "airplane_mode" to true, "model_files" to expected, "asset_hashes_verified" to true, "clean_model_directory" to true,
                "asr_locked_accurate" to true, "llm_locked_gemma" to true, "install_ms" to installMs,
                "audio_ms" to audio.size / 16L, "asr_diarization_export_ms" to transcriptionMs,
                "speakers" to result.displayNames.size, "unknown_or_overlap_words" to result.speakers.count { it < 0 }, "json_turns" to turns.length(),
                "llm_question_passed" to true, "llm_question_ms" to now() - llmStart)))
        } finally {
            whisper?.release()
            llm?.release()
            val deadline = now() + 10000
            while (LlmHelper.active != null && now() < deadline) android.os.SystemClock.sleep(20)
            root.deleteRecursively()
            prefs.forEach { ctx.deleteSharedPreferences(it) }
        }
    }
}
