package com.guzlik.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in local diagnosis/repair of an explicitly selected recording; never uploads evidence. */
@RunWith(AndroidJUnit4::class)
class StoredDiarizationDiagnosticsDeviceTest {
    @Test fun reprocessExplicitlySelectedRecordingWithCompleteAudio() {
        val timestamp = InstrumentationRegistry.getArguments().getString("repairRecordingTimestamp")?.toLongOrNull()
        assumeTrue(timestamp != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".dev") || ctx.packageName.endsWith(".hackathon"))
        val original = requireNotNull(RecordStorage.findEntryByTimestamp(ctx, timestamp!!)).second
        require(!original.isEncrypted)
        val done = java.util.concurrent.CountDownLatch(1)
        var outcome: DiarizationJobRunner.State? = null
        val listener: (DiarizationJobRunner.State) -> Boolean = { state ->
            if (state.timestamp == timestamp && state !is DiarizationJobRunner.State.Running) {
                outcome = state
                done.countDown()
            }
            true
        }
        DiarizationJobRunner.addListener(listener)
        var aligner: WhisperHelper? = null
        try {
            val args = InstrumentationRegistry.getArguments()
            if (args.getString("refineBoundaries") == "true") {
                val loaded = java.util.concurrent.CountDownLatch(1)
                var error: String? = null
                aligner = WhisperHelper(ctx)
                aligner.init({ loaded.countDown() }, { error = it; loaded.countDown() })
                assertTrue("Whisper init timed out", loaded.await(90, java.util.concurrent.TimeUnit.SECONDS))
                assertNull(error)
            }
            val started = android.os.SystemClock.elapsedRealtime()
            DiarizationJobRunner.start(ctx, File(original.audioPath), timestamp, null, original.segments, aligner, true)
            assertTrue("Diarization timed out", done.await(180, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Diarization failed: $outcome", outcome is DiarizationJobRunner.State.Done)
            val saved = RecordStorage.findEntryByTimestamp(ctx, timestamp)!!.second
            fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(
                text.replace(Regex("\\[(?:Говорящий [^]]+|Говорят одновременно)]"), ""))
                .map { it.value.lowercase() }.toList()
            assertEquals("Speaker repair must preserve the user's words", words(original.text), words(saved.text))
            val key = DiarizationEvidenceCache.keyFor(ctx, File(original.audioPath), timestamp)
            val checkpoint = requireNotNull(DiarizationCheckpointStore.load(ctx, key))
            val lastWord = requireNotNull(original.segments).maxOf { it.endMs }
            assertTrue("Acoustic evidence truncated before the final words",
                checkpoint.evidence.windows.maxOf { it.endMs } + 1000 >= lastWord)
            val expectedWord = args.getString("assertSpeakerWord")
            if (expectedWord != null) {
                val result = SpeakerDiarizer.diarize(saved.segments!!, emptyMap(), null, cachedEvidence = checkpoint.evidence)
                val after = args.getString("assertWordAfterMs")?.toLong() ?: 0L
                val selected = result.units.indices.filter { result.units[it].startMs >= after &&
                    result.units[it].text.filter { c -> c.isLetterOrDigit() }.equals(expectedWord, true) }
                assertTrue("Requested word not found", selected.isNotEmpty())
                for (i in selected) assertEquals("Boundary word still belongs to the wrong speaker",
                    args.getString("assertSpeakerIndex")!!.toInt(), result.speakers[i])
            }
            val firstPassMs = android.os.SystemClock.elapsedRealtime() - started
            var warmMs: Long? = null
            if (args.getString("repeatWarmBoundaryRepair") == "true") {
                val ticket = DiarizationEvidenceCache.ticket(timestamp)
                assertNotNull("Decoded audio must remain reusable", DiarizationEvidenceCache.getPcm(key, ticket))
                var changed = false
                // Invalidate one local pass in the request only; never edit the user's text.
                val edited = saved.segments!!.map { part -> part.copy(words = part.words?.map { word ->
                    if (!changed && word.boundaryPassText != null) {
                        changed = true; word.copy(boundaryPassText = null)
                    } else word
                }) }
                assertTrue(changed)
                assertEquals(1, BoundaryWordTiming.plan(edited,
                    SpeakerDiarizer.diarize(edited, emptyMap(), null, cachedEvidence = checkpoint.evidence).turns,
                    checkpoint.evidence.windows.maxOf { it.endMs }).size)
                val cleanupDeadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (DiarizationJobRunner.hasActiveJob(timestamp) && android.os.SystemClock.elapsedRealtime() < cleanupDeadline)
                    android.os.SystemClock.sleep(10)
                assertFalse(DiarizationJobRunner.hasActiveJob(timestamp))
                val warmDone = java.util.concurrent.CountDownLatch(1)
                var warmOutcome: DiarizationJobRunner.State? = null
                val warmListener: (DiarizationJobRunner.State) -> Boolean = { state ->
                    if (state.timestamp == timestamp && state !is DiarizationJobRunner.State.Running) {
                        warmOutcome = state; warmDone.countDown()
                    }
                    true
                }
                DiarizationJobRunner.addListener(warmListener)
                try {
                    val warmStart = android.os.SystemClock.elapsedRealtime()
                    DiarizationJobRunner.start(ctx, File(saved.audioPath), timestamp, null, edited, aligner, true)
                    assertTrue(warmDone.await(180, java.util.concurrent.TimeUnit.SECONDS))
                    assertTrue("Warm repair failed: $warmOutcome", warmOutcome is DiarizationJobRunner.State.Done)
                    warmMs = android.os.SystemClock.elapsedRealtime() - warmStart
                    val again = RecordStorage.findEntryByTimestamp(ctx, timestamp)!!.second
                    assertEquals("Warm repair changed the transcript or labels", saved.text, again.text)
                    assertEquals(saved.segments, again.segments)
                } finally { DiarizationJobRunner.removeListener(warmListener) }
            }
            val dir = File(ctx.noBackupFilesDir, "record-diarization-debug").apply { mkdirs() }
            File(dir, "repair.json").writeText(Gson().toJson(mapOf(
                "speakers" to (outcome as DiarizationJobRunner.State.Done).speakers,
                "evidence_end_ms" to checkpoint.evidence.windows.maxOf { it.endMs },
                "last_word_ms" to lastWord, "words_preserved" to true,
                "total_ms" to firstPassMs,
                "warm_one_clip_ms" to warmMs,
                "boundary_pass_words" to saved.segments!!.sumOf { s -> s.words.orEmpty().count { it.boundaryPassVersion > 0 } })))
        } finally {
            DiarizationJobRunner.removeListener(listener)
            aligner?.release()
        }
    }

    @Test fun compareReusedContextAgainstIndependentClips() {
        val args = InstrumentationRegistry.getArguments()
        val timestamp = args.getString("benchmarkBoundaryTimestamp")?.toLongOrNull()
        assumeTrue(timestamp != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".dev") || ctx.packageName.endsWith(".hackathon"))
        val original = RecordStorage.findEntryByTimestamp(ctx, timestamp!!)!!.second
        require(!original.isEncrypted)
        val segments = original.segments!!.map { s -> s.copy(words = s.words?.map {
            it.copy(boundaryPassVersion = 0, boundaryPassText = null)
        }) }
        val key = DiarizationEvidenceCache.keyFor(ctx, File(original.audioPath), timestamp)
        val evidence = DiarizationCheckpointStore.load(ctx, key)!!.evidence
        val base = SpeakerDiarizer.diarize(segments, emptyMap(), null, cachedEvidence = evidence)
        val ready = java.util.concurrent.CountDownLatch(1)
        val aligner = WhisperHelper(ctx)
        var error: String? = null
        fun now() = android.os.SystemClock.elapsedRealtime()
        try {
            aligner.init({ ready.countDown() }, { error = it; ready.countDown() })
            assertTrue(ready.await(90, java.util.concurrent.TimeUnit.SECONDS)); assertNull(error)
            val decodeStart = now()
            val audio = AudioDecoder.decode(File(original.audioPath))
            val decodeMs = now() - decodeStart
            val clips = BoundaryWordTiming.plan(segments, base.turns, audio.size / 16L)
            assertTrue("Need multiple boundary clips to compare context reuse", clips.size > 1)
            val token = WhisperHelper.newCancelToken()
            val independentStart = now()
            val independent = clips.map { clip -> aligner.refineWordTiming(
                audio.copyOfRange((clip.startMs * 16).toInt(), (clip.endMs * 16).toInt()), token) }
            val independentMs = now() - independentStart
            val ticket = DiarizationEvidenceCache.ticket(timestamp)
            assertTrue(DiarizationEvidenceCache.putPcm(key, ticket, ArrayPcm(audio)))
            val cached = requireNotNull(DiarizationEvidenceCache.getPcm(key, ticket))
            assertArrayEquals(audio, cached.copyOfRange(0, cached.size))
            val reusedStart = now()
            val reused = aligner.withWordTimingSession(token) { refine ->
                clips.map { clip -> refine(cached.copyOfRange((clip.startMs * 16).toInt(), (clip.endMs * 16).toInt())) }
            }
            val reusedMs = now() - reusedStart
            assertEquals("Context reuse changed recognition, probabilities or word timing", independent, reused)
            fun apply(all: List<List<WhisperHelper.Word>>): List<WhisperHelper.Segment> {
                var result = segments
                for ((i, clip) in clips.withIndex()) result = BoundaryWordTiming.apply(result, clip,
                    all[i].map { it.copy(startMs = it.startMs + clip.startMs, endMs = it.endMs + clip.startMs) }, base.turns)
                return result
            }
            val repaired = apply(reused)
            assertEquals(apply(independent), repaired)
            val result = SpeakerDiarizer.reassignWords(repaired, base)
            assertArrayEquals(SpeakerDiarizer.reassignWords(apply(independent), base).speakers, result.speakers)
            assertTrue("Completed clips must not run again", BoundaryWordTiming.plan(repaired, base.turns, audio.size / 16L).isEmpty())
            assertEquals(original.text, RecordStorage.findEntryByTimestamp(ctx, timestamp)!!.second.text)
            val dir = File(ctx.noBackupFilesDir, "record-diarization-debug").apply { mkdirs() }
            File(dir, "speed.json").writeText(Gson().toJson(mapOf("clips" to clips.size,
                "decode_ms" to decodeMs, "independent_contexts_ms" to independentMs, "reused_context_ms" to reusedMs,
                "pcm_cache_hit" to true, "native_words_exactly_equal" to true, "speakers_exactly_equal" to true,
                "completed_clips_skipped" to true, "remaining_unknown_words" to result.speakers.count { it < 0 })))
        } finally { aligner.release() }
    }

    @Test fun inspectBoundaryAlignmentWithoutChangingText() {
        val args = InstrumentationRegistry.getArguments()
        val timestamp = args.getString("inspectBoundaryTimestamp")?.toLongOrNull()
        assumeTrue(timestamp != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".dev") || ctx.packageName.endsWith(".hackathon"))
        val entry = RecordStorage.findEntryByTimestamp(ctx, timestamp!!)!!.second
        require(!entry.isEncrypted)
        val ready = java.util.concurrent.CountDownLatch(1)
        val aligner = WhisperHelper(ctx)
        var error: String? = null
        try {
            aligner.init({ ready.countDown() }, { error = it; ready.countDown() })
            assertTrue(ready.await(90, java.util.concurrent.TimeUnit.SECONDS)); assertNull(error)
            val key = DiarizationEvidenceCache.keyFor(ctx, File(entry.audioPath), timestamp)
            val evidence = DiarizationCheckpointStore.load(ctx, key)!!.evidence
            val base = SpeakerDiarizer.diarize(entry.segments!!, emptyMap(), null, cachedEvidence = evidence)
            val audio = AudioDecoder.decode(File(entry.audioPath))
            val clip = BoundaryWordTiming.Clip(args.getString("clipStartMs")!!.toLong(), audio.size / 16L)
            val fresh = aligner.refineWordTiming(audio.copyOfRange((clip.startMs * 16).toInt(), audio.size), WhisperHelper.newCancelToken())
                .map { it.copy(startMs = it.startMs + clip.startMs, endMs = it.endMs + clip.startMs) }
            val after = BoundaryWordTiming.apply(entry.segments!!, clip, fresh, base.turns)
            val dir = File(ctx.noBackupFilesDir, "record-diarization-debug").apply { mkdirs() }
            File(dir, "boundary.json").writeText(Gson().toJson(mapOf("before" to entry.segments,
                "fresh" to fresh, "after" to after, "turns" to base.turns)))
        } finally { aligner.release() }
    }

    @Test fun replaySavedRecordingWithoutChangingIt() {
        val args = InstrumentationRegistry.getArguments()
        val timestamp = args.getString("diagnoseRecordingTimestamp")?.toLongOrNull()
        assumeTrue(timestamp != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        require(ctx.packageName.endsWith(".dev") || ctx.packageName.endsWith(".hackathon"))
        val original = requireNotNull(RecordStorage.findEntryByTimestamp(ctx, timestamp!!)).second
        require(!original.isEncrypted)
        val key = DiarizationEvidenceCache.keyFor(ctx, File(original.audioPath), timestamp)
        val checkpoint = requireNotNull(DiarizationCheckpointStore.load(ctx, key))
        val result = SpeakerDiarizer.diarize(requireNotNull(original.segments), emptyMap(), null,
            context = ctx, cachedEvidence = checkpoint.evidence)
        val dir = File(ctx.noBackupFilesDir, "record-diarization-debug").apply { mkdirs() }
        val gson = Gson()
        File(dir, "evidence.json").writeText(gson.toJson(checkpoint.evidence))
        File(dir, "result.json").writeText(gson.toJson(mapOf(
            "raw_text" to result.text,
            "applied_text" to DiarizedTranscript.apply(original.text, result),
            "stored_text" to original.text,
            "units" to result.units,
            "speakers" to result.speakers,
            "turns" to result.turns)))
        if (args.getString("diagnoseDecode") == "true") {
            val source = File(original.audioPath)
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(source.absolutePath)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0).toString()
            var packets = 0
            var lastTime = -1L
            val packet = java.nio.ByteBuffer.allocate(1024 * 1024)
            while (extractor.readSampleData(packet, 0) >= 0) {
                packets++
                lastTime = extractor.sampleTime
                if (!extractor.advance()) break
            }
            extractor.release()
            val array = AudioDecoder.decode(source)
            AudioDecoder.decodeToFile(source, ctx.cacheDir) { false }.use { disk ->
                File(dir, "decode.json").writeText(gson.toJson(mapOf(
                    "array_samples" to array.size, "file_samples" to disk.size,
                    "packets" to packets, "last_packet_us" to lastTime, "format" to format,
                    "evidence_end_ms" to checkpoint.evidence.windows.maxOf { it.endMs })))
                assertEquals("Streaming decode truncated audio", array.size, disk.size)
            }
        }
        assertEquals(original.text, RecordStorage.findEntryByTimestamp(ctx, timestamp)!!.second.text)
        assertEquals(result.units.size, result.speakers.size)
    }
}
