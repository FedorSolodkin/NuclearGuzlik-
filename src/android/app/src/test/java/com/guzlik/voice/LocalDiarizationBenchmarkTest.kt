package com.guzlik.voice

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** External labels stay in the scorer: inference receives audio evidence only. */
@RunWith(RobolectricTestRunner::class)
class LocalDiarizationBenchmarkTest {
    @Test fun replayFrozenCorpusWithoutReferenceLabels() {
        val location=System.getenv("GUZLIKVOICE_DIAR_BENCHMARK")
        assumeTrue(location!=null)
        val root=File(location!!)
        val gson=Gson()
        // Deliberately do not read the manifest/reference: even the number of
        // speakers and gold turn boundaries must not leak into clustering.
        val cases=root.listFiles { f -> f.isDirectory && File(f,"full-evidence.json").isFile }!!.sortedBy{it.name}
        assertTrue("No captured corpus evidence",cases.isNotEmpty())
        for(dir in cases) {
            val original=File(dir,"full-evidence.json").bufferedReader().use {
                gson.fromJson(it,SpeakerDiarizer.EvidenceSnapshot::class.java)
            }
            val short=File(dir,"refinement-evidence.json")
            val evidence=if(short.exists())original.copy(boundaryRefinement=SpeakerBoundaryRefiner.Evidence(
                short.bufferedReader().use { gson.fromJson(it,Array<SpeakerBoundaryRefiner.Observation>::class.java).toList() },true)) else original
            val duration=evidence.windows.maxOf{it.endMs}
            val probes=(0L until duration step 100L).map{WhisperHelper.Segment("probe",it,minOf(it+100,duration))}
            val start=System.nanoTime()
            val result=SpeakerDiarizer.diarize(probes,emptyMap(),null,cachedEvidence=evidence)
            val elapsed=(System.nanoTime()-start)/1e6
            assertEquals(probes.size,result.speakers.size)
            assertTrue(result.turns.all{it.startMs>=0 && it.endMs>it.startMs && it.endMs<=duration})
            val plan=SpeakerBoundaryRefiner.windows(SpeakerBoundaryRefiner.plan(original,result.centroids))
            File(dir,"refinement-plan.json").writeText(gson.toJson(plan))
            File(dir,if(short.exists())"result.json" else "baseline-result.json").writeText(gson.toJson(mapOf(
                "audio_ms" to duration,"speakers" to result.centroids.size,"requested_speakers" to null,
                "turns" to result.turns,"probe_assignments" to result.speakers,"probe_step_ms" to 100,
                "short_windows" to (evidence.boundaryRefinement?.observations?.size ?: 0),
                "host_replay_ms" to elapsed,"runtime" to "host JVM; not Android timing")))
            val words=File(dir,"words.json")
            if(words.exists()) {
                val segments=words.bufferedReader().use{gson.fromJson(it,Array<WhisperHelper.Segment>::class.java).toList()}
                val spoken=SpeakerDiarizer.diarize(segments,emptyMap(),null,cachedEvidence=evidence)
                assertEquals(segments.map{it.text},spoken.units.map{it.text})
                File(dir,"word-assignments.json").writeText(gson.toJson(spoken.units.mapIndexed { i,u ->
                    listOf(u.startMs,u.endMs,u.text,spoken.speakers[i])
                }))
            }
            println("${dir.name}: ${result.centroids.size} automatic voices, ${result.turns.size} turns, ${elapsed}ms host replay")
        }
    }
}
