package com.guzlik.voice

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Opt-in local audio evaluation. Probe labels are never presented as a transcript. */
@RunWith(RobolectricTestRunner::class)
class LocalAcousticReplayTest {
    @Test fun replayUnseenAudioWithoutSpeakerCountHint() {
        val location = System.getenv("GUZLIKVOICE_ACOUSTIC_EVIDENCE")
        assumeTrue(location != null)
        val dir = File(location!!)
        val gson = Gson()
        val file = File(dir, "full-evidence.json")
        val original = file.bufferedReader().use { gson.fromJson(it, SpeakerDiarizer.EvidenceSnapshot::class.java) }
        val extra=File(dir,"refinement-evidence.json")
        val evidence=if(extra.exists()) original.copy(boundaryRefinement=SpeakerBoundaryRefiner.Evidence(
            extra.bufferedReader().use { gson.fromJson(it,Array<SpeakerBoundaryRefiner.Observation>::class.java).toList() },true)) else original
        val duration = evidence.windows.maxOf { it.endMs }
        val probes = (0L until duration step 100L).map {
            WhisperHelper.Segment("probe", it, minOf(it + 100, duration))
        }
        val start = System.nanoTime()
        val result = SpeakerDiarizer.diarize(probes, emptyMap(), null, cachedEvidence = evidence)
        val replayMs=(System.nanoTime()-start)/1e6
        val baseline=if(evidence.boundaryRefinement!=null)
            SpeakerDiarizer.diarize(probes,emptyMap(),null,cachedEvidence=evidence.copy(boundaryRefinement=null)) else result
        assertTrue(result.centroids.isNotEmpty())
        assertTrue(result.turns.isNotEmpty())
        assertEquals(probes.size, result.units.size)
        assertEquals(probes.size, result.speakers.size)
        fun digest(block: (DataOutputStream) -> Unit): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use(block)
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        val plan=SpeakerBoundaryRefiner.windows(SpeakerBoundaryRefiner.plan(evidence,result.centroids))
        File(dir,"refinement-plan.json").writeText(gson.toJson(plan))
        if(evidence.boundaryRefinement!=null){
            File(dir,"full-evidence-with-boundaries.json").bufferedWriter().use{gson.toJson(evidence,it)}
            File(dir,"refined-turns.json").writeText(gson.toJson(result.turns))
        }
        val expected = mapOf("speakers" to result.centroids.size, "probes" to result.units.size,
            "assignments_sha256" to digest { out -> result.speakers.forEach { out.writeInt(it) } },
            "turns_sha256" to digest { out -> result.turns.forEach { out.writeLong(it.startMs); out.writeLong(it.endMs); out.writeInt(it.speaker) } })
        File(dir,"expected-acoustics.json").writeText(gson.toJson(expected))
        File(dir,"full-result.json").writeText(gson.toJson(mapOf(
            "audio_ms" to duration, "speakers" to result.centroids.size, "requested_speakers" to null,
            "turns" to result.turns, "centroids" to result.centroids, "preview_ms" to result.previewOffsetsMs,
            "probe_counts" to result.speakers.toList().groupingBy { it }.eachCount(),
            "probe_assignments" to result.speakers, "probe_step_ms" to 100,
            "baseline_turns" to baseline.turns,"baseline_probe_assignments" to baseline.speakers,
            "host_replay_ms" to replayMs, "manual_ground_truth" to false)))
        println("Local unseen-audio replay: ${result.centroids.size} voices; ${probes.size} probes; no fixed count")
    }
}
