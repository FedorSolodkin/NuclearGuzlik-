package com.guzlik.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Прогон движков на телефоне на эталонных данных. Сам тест только гоняет и
 * сохраняет результаты (JSON в eval/out во внешней папке приложения); качество
 * (WER, найденные задачи) считает tools/device_test/run.py на компьютере.
 *
 * Входные данные кладёт run.py в <externalFilesDir>/eval:
 *   asr/<имя>.s16   — PCM 16 кГц mono int16
 *   llm/<имя>.txt   — тексты для «Выделить главное»
 * Тесты без входных данных или моделей пропускаются (assume).
 */
@RunWith(AndroidJUnit4::class)
class EngineEvalDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val evalDir = File(ctx.getExternalFilesDir(null), "eval")
    private val outDir = File(evalDir, "out").apply { mkdirs() }
    private val args = InstrumentationRegistry.getArguments()

    private fun readS16(f: File): ShortArray {
        val bytes = f.readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun initWhisper(): WhisperHelper {
        val w = WhisperHelper(ctx)
        val latch = CountDownLatch(1)
        var err: String? = null
        w.init({ latch.countDown() }, { err = it; latch.countDown() })
        assertTrue("whisper init timeout", latch.await(10, TimeUnit.MINUTES))
        assertTrue("whisper init: $err", err == null && w.isReady)
        return w
    }

    /** Explicit opt-in test: download only the two public diarization models into the debug app. */
    @Test fun prepareDiarizationModels() {
        assumeTrue("Explicit -e downloadModels true required", args.getString("downloadModels") == "true")
        val models = listOf(
            SpeakerEmbedder.MODEL_NAME to "https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM/resolve/main/onnx/model.onnx",
            PyannoteSegmenter.MODEL_NAME to "https://huggingface.co/onnx-community/pyannote-segmentation-3.0/resolve/main/onnx/model.onnx")
        for ((name, url) in models) {
            val file = File(ctx.filesDir, name)
            if (file.exists() && ModelIntegrity.verify(file, name, url)) continue
            val tmp = File(ctx.filesDir, "$name.download")
            val connection = java.net.URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
            try {
                connection.getInputStream().use { input -> tmp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= 40_000_000) { "Unexpected model size" }
                        output.write(buffer, 0, n)
                    }
                } }
                assertTrue("SHA-256 $name", ModelIntegrity.verify(tmp, name, url))
                assertTrue("rename $name", tmp.renameTo(file))
                Log.i("EngineEval", "Downloaded verified $name: ${file.length()} bytes")
            } finally { tmp.delete(); (connection as? java.net.HttpURLConnection)?.disconnect() }
        }
    }

    /** Isolate diarization from ASR. Uniform 100 ms probes contain no reference speaker labels. */
    @Test fun diarizationAcoustics() {
        val files = File(evalDir, "diar").listFiles { f -> f.name.endsWith(".s16") &&
            (args.getString("only") == null || f.name.contains(args.getString("only")!!)) }?.sortedBy { it.name }.orEmpty()
        assumeTrue("No eval/diar fixtures", files.isNotEmpty())
        val segmenter = PyannoteSegmenter(ctx)
        assertTrue("segmentation model missing", segmenter.load()); segmenter.release()
        val embedder = SpeakerEmbedder(ctx)
        assertTrue("embedding model missing", embedder.load())
        val results = JSONObject()
        try {
            for (f in files) {
                val pcm = readS16(f)
                val audioMs = pcm.size * 1000L / 16000
                val sidecar = File(f.parentFile, f.nameWithoutExtension + ".segments.json")
                val stored = if (sidecar.exists()) com.google.gson.Gson().fromJson(sidecar.readText(), Array<WhisperHelper.Segment>::class.java).toList() else null
                val units = stored ?: (0L until audioMs step 100L).map { WhisperHelper.Segment("probe", it, minOf(it + 100, audioMs)) }
                val t0 = android.os.SystemClock.elapsedRealtime()
                val result = SpeakerDiarizer.diarize(units, emptyMap(), embedder, pcm, ctx,
                    numSpeakers = args.getString("numSpeakers")?.toInt(),
                    onProgress = { Log.i("EngineEval", "${f.name}: $it%") },
                    onEvidenceReady = if (args.getString("captureEvidence") == "true") { evidence ->
                        File(outDir, f.nameWithoutExtension + ".evidence.json").writeText(com.google.gson.Gson().toJson(evidence))
                    } else null)
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                val turns = JSONArray()
                result.turns.forEach { turns.put(JSONArray().put(it.startMs).put(it.endMs).put(it.speaker)) }
                assertTrue("No acoustic evidence", result.centroids.isNotEmpty() && result.turns.isNotEmpty())
                val record = JSONObject().put("turns", turns)
                    .put("audio_ms", audioMs).put("diar_ms", ms).put("rtf", ms.toDouble() / audioMs)
                    .put("speakers", result.displayNames.size).put("requested_speakers", args.getString("numSpeakers"))
                if (stored != null) {
                    record.put("text", result.text)
                    val words = JSONArray()
                    result.units.forEachIndexed { i,u -> words.put(JSONArray().put(u.startMs).put(u.endMs).put(u.text).put(result.speakers[i])) }
                    record.put("units", words)
                }
                results.put(f.name.removeSuffix(".s16"), record)
                File(outDir, "diar-acoustics.json").writeText(results.toString(1))
                Log.i("EngineEval", "${f.name}: $audioMs ms audio, $ms ms processing, ${result.displayNames.size} speakers")
            }
        } finally { embedder.release() }
    }

    /** Full unseen-audio core parity; extraction timing is deliberately measured separately. */
    @Test fun cachedAcousticsParity() {
        assumeTrue(args.getString("acousticReplay") == "true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val name = args.getString("fixture") ?: error("fixture required")
        require(name.matches(Regex("[A-Za-z0-9_-]+")))
        val dir = File(evalDir,"acoustic/$name")
        val evidenceFile=args.getString("evidenceFile") ?: "full-evidence.json"
        val expectedFile=args.getString("expectedFile") ?: "expected-acoustics.json"
        val jsonName=Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.json")
        require(evidenceFile.matches(jsonName) && expectedFile.matches(jsonName))
        val gson = com.google.gson.Gson()
        val evidence = File(dir,evidenceFile).bufferedReader().use {
            gson.fromJson(it,SpeakerDiarizer.EvidenceSnapshot::class.java)
        }
        val expected = JSONObject(File(dir,expectedFile).readText())
        val duration = evidence.windows.maxOf { it.endMs }
        val probes = (0L until duration step 100L).map {
            WhisperHelper.Segment("probe",it,minOf(it+100,duration))
        }
        val start = android.os.SystemClock.elapsedRealtime()
        val result = SpeakerDiarizer.diarize(probes,emptyMap(),null,context=ctx,cachedEvidence=evidence)
        val elapsed = android.os.SystemClock.elapsedRealtime()-start
        fun digest(block: (java.io.DataOutputStream) -> Unit): String {
            val bytes = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(bytes).use(block)
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        assertEquals(expected.getInt("speakers"),result.centroids.size)
        assertEquals(expected.getInt("probes"),result.units.size)
        assertEquals(expected.getString("assignments_sha256"),digest { out -> result.speakers.forEach { out.writeInt(it) } })
        assertEquals(expected.getString("turns_sha256"),digest { out -> result.turns.forEach { out.writeLong(it.startMs);out.writeLong(it.endMs);out.writeInt(it.speaker) } })
        File(dir,"phone-validation.json").writeText(JSONObject().put("speakers",result.centroids.size)
            .put("probes",result.units.size).put("core_replay_ms",elapsed).put("audio_ms",duration)
            .put("turns_and_assignments_match",true).put("full_phone_inference",false).toString(2))
    }

    /** Полный конвейер записи: TranscriptionSession режет поток на куски, как при диктовке. */
    @Test fun asrPipeline() {
        val files = File(evalDir, "asr").listFiles { f -> f.name.endsWith(".s16") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("нет eval/asr", files.isNotEmpty())
        val whisper = initWhisper()
        val results = JSONObject()
        try {
            for (f in files) {
                val pcm = readS16(f)
                val t0 = System.currentTimeMillis()
                var raw = ""
                var final = ""
                val session = TranscriptionSession(
                    whisper = whisper, embedder = null, context = ctx,
                    onChunkReady = { _, _ -> }, onProcessingChanged = { },
                    persistWal = false,
                )
                // Подаём «как с микрофона» блоками по 100 мс, но без ожидания реального времени.
                var pos = 0
                while (pos < pcm.size) {
                    val n = minOf(1600, pcm.size - pos)
                    session.pushAudio(pcm.copyOfRange(pos, pos + n)); pos += n
                }
                session.flushAndWait(onRawReady = { raw = it }) { text, _, _ -> final = text }
                session.release()
                val ms = System.currentTimeMillis() - t0
                val sec = pcm.size / 16000.0
                Log.i("EngineEval", "ASR ${f.name}: ${"%.1f".format(sec)} s audio in $ms ms (RTF ${"%.2f".format(ms / 1000.0 / sec)})")
                results.put(f.name.removeSuffix(".s16"), JSONObject()
                    .put("hyp", raw).put("final", final).put("audio_s", sec).put("ms", ms)
                    .put("model", whisper.loadedModelName))
                File(outDir, "asr.json").writeText(results.toString(1))   // промежуточно — на случай падения
            }
        } finally {
            whisper.release()
        }
    }

    /** Разделение по голосам на диалогах из фраз Golos: пословная разметка до и после доводки. */
    @Test fun diarization() {
        val files = File(evalDir, "diar").listFiles { f -> f.name.endsWith(".s16") && (args.getString("only") == null || f.name.contains(args.getString("only")!!)) }?.sortedBy { it.name }.orEmpty()
        assumeTrue("нет eval/diar", files.isNotEmpty())
        val whisper = initWhisper()
        val embedder = SpeakerEmbedder(ctx)
        assertTrue("модель голосов не загрузилась", embedder.load())
        val results = JSONObject()
        try {
            for (f in files) {
                val pcm = readS16(f)
                var diar: SpeakerDiarizer.Result? = null
                val t0 = System.currentTimeMillis()
                val session = TranscriptionSession(whisper = whisper, embedder = embedder, context = ctx,
                    onChunkReady = { _, _ -> }, onProcessingChanged = { }, persistWal = false)
                var pos = 0
                while (pos < pcm.size) {
                    val n = minOf(1600, pcm.size - pos)
                    session.pushAudio(pcm.copyOfRange(pos, pos + n)); pos += n
                }
                session.flushAndWait { _, _, d -> diar = d }
                session.release()
                val d = diar
                assertTrue("No diarization result for ${f.name}", d != null && d.units.isNotEmpty())
                val units = JSONArray()
                if (d != null) for ((i, u) in d.units.withIndex()) {
                    units.put(JSONArray().put(u.startMs).put(u.endMs).put(u.text)
                        .put(d.speakers.getOrElse(i) { -1 }).put(d.rawSpeakers.getOrElse(i) { -1 }))
                }
                val turns = JSONArray()
                d?.turns?.forEach { turns.put(JSONArray().put(it.startMs).put(it.endMs).put(it.speaker)) }
                results.put(f.name.removeSuffix(".s16"), JSONObject().put("turns", turns).put("units", units)
                    .put("text", d?.text ?: "").put("ms", System.currentTimeMillis() - t0))
                File(outDir, "diar.json").writeText(results.toString(1))
            }
        } finally {
            embedder.release(); whisper.release()
        }
    }

    /** «Выделить главное» и вопросы к записи на каждой скачанной модели. */
    @Test fun llmModels() {
        val texts = File(evalDir, "llm").listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("нет eval/llm", texts.isNotEmpty())
        val only = args.getString("llm")   // -e llm vm-qwen06-ft — только одна модель
        val specs = LlmModels.ALL.filter { LlmModels.isDownloaded(ctx, it) && (only == null || it.id == only) }
        assumeTrue("нет скачанных LLM", specs.isNotEmpty())
        val results = JSONObject()
        for (spec in specs) {
            val llm = LlmHelper(ctx, spec)
            val tl = System.currentTimeMillis()
            assertTrue("${spec.id}: load", llm.load())
            val m = JSONObject().put("load_ms", System.currentTimeMillis() - tl).put("gpu", llm.isGpuActive)
            val items = JSONObject()
            for (t in texts) {
                val text = t.readText()
                val t0 = System.currentTimeMillis()
                val out = llm.structure(text)
                val ms = System.currentTimeMillis() - t0
                Log.i("EngineEval", "LLM ${spec.id} ${t.name}: $ms ms\n$out")
                items.put(t.name.removeSuffix(".txt"), JSONObject().put("out", out).put("ms", ms).put("chars", text.length))
                results.put(spec.id, m.put("structure", items))
                File(outDir, "llm.json").writeText(results.toString(1))
            }
            // Вопрос к записи: Gemma отвечает по тексту, дообученная Qwen — честно отказывается.
            val qaText = texts.first().readText()
            val t0 = System.currentTimeMillis()
            val answer = llm.askQuestion(qaText, "Кому нужно отправить счёт и на какую сумму?")
            m.put("qa", JSONObject().put("answer", answer).put("ms", System.currentTimeMillis() - t0))
            if (!spec.supportsQa) assertEquals(LlmHelper.SPECIALIST_NO_QA, answer)
            results.put(spec.id, m)
            File(outDir, "llm.json").writeText(results.toString(1))
            llm.release()
        }
    }

}
