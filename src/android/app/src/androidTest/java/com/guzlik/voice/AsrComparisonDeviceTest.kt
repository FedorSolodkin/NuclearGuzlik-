package com.guzlik.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in paired benchmark on public fixtures, in a separate development package. */
@RunWith(AndroidJUnit4::class)
class AsrComparisonDeviceTest {
    @Test fun compareSameAudioOnDevice() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("compareAsr") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".dev"))
        AppPrefs.setWhisperModel(ctx, AppPrefs.MODEL_ACCURATE)
        AppPrefs.setWhisperGpuEnabled(ctx, false)
        val cases = args.getString("caseIds", "ru_meeting_2spk,ru_meeting_3spk,real_ru_2spk").split(',')
        val models = args.getString("benchModels", "giga,whisper").split(',')
        val out = File(ctx.filesDir, "comparison/out").apply { mkdirs() }
        val failures = mutableListOf<String>()
        val gson = Gson()
        for ((index, id) in cases.withIndex()) {
            require(id.matches(Regex("[a-z0-9_]+")))
            val pcm = AudioDecoder.decode(File(ctx.filesDir, "comparison/$id.wav"))
            val audio = PcmNormalizer.normalize(pcm)
            for (model in if (index % 2 == 0) models else models.reversed()) {
                val engine: AsrEngine = when (model) {
                    "giga" -> GigaAmEngine(ctx)
                    "whisper" -> WhisperCppEngine(ctx)
                    else -> error("Unknown benchmark model")
                }
                try {
                    val loaded = CountDownLatch(1)
                    var error: String? = null
                    val initStart = android.os.SystemClock.elapsedRealtime()
                    engine.init({ loaded.countDown() }, { error = it; loaded.countDown() })
                    assertTrue(loaded.await(120, TimeUnit.SECONDS)); assertNull(error)
                    val initMs = android.os.SystemClock.elapsedRealtime() - initStart
                    val started = android.os.SystemClock.elapsedRealtime()
                    val segments = HallucinationFilter.clean(engine.transcribeSegments(audio,
                        useVad = true, language = AppPrefs.LANG_RU, cancelToken = WhisperHelper.newCancelToken()))
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    assertTrue("Empty recognition: $id/$model", segments.isNotEmpty())
                    if (model == "giga") {
                        assertEquals("gigaam-v3-transducer-punct-int8", engine.loadedModelName)
                        val words = segments.flatMap { it.words.orEmpty() }
                        assertTrue("Missing native word times: $id", words.size > segments.size)
                        assertTrue(words.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= pcm.size / 16L })
                    }
                    File(out, "$id.$model.json").writeText(gson.toJson(mapOf(
                        "case" to id, "model" to engine.loadedModelName, "audio_ms" to pcm.size / 16L,
                        "init_ms" to initMs, "asr_ms" to elapsed,
                        "text" to segments.joinToString(" ") { it.text }, "segments" to segments)))
                    android.util.Log.i("AsrComparison", "$id/$model: $elapsed ms, ${segments.size} segments")
                } catch (e: Throwable) {
                    val message = "$id/$model: ${e.javaClass.simpleName}: ${e.message}"
                    failures.add(message)
                    File(out, "$id.$model.error.txt").writeText(message)
                } finally { engine.release() }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
