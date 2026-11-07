package com.example.guzlikvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit local diagnosis only. Does not change the recording or upload evidence. */
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
        try {
            DiarizationJobRunner.start(ctx, File(original.audioPath), timestamp, null, original.segments, null, true)
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
            val dir = File(ctx.noBackupFilesDir, "record-diarization-debug").apply { mkdirs() }
            File(dir, "repair.json").writeText(Gson().toJson(mapOf(
                "speakers" to (outcome as DiarizationJobRunner.State.Done).speakers,
                "evidence_end_ms" to checkpoint.evidence.windows.maxOf { it.endMs },
                "last_word_ms" to lastWord, "words_preserved" to true)))
        } finally {
            DiarizationJobRunner.removeListener(listener)
        }
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
