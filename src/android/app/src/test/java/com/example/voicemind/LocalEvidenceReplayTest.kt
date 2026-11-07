package com.example.voicemind

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/** Explicit private fixture opt-in. CI never downloads or contains personal recordings. */
@RunWith(RobolectricTestRunner::class)
class LocalEvidenceReplayTest {
    @Test fun replayActualCoreAndPreserveAllWords() {
        val location=System.getenv("VOICEMIND_LOCAL_EVIDENCE")
        assumeTrue(location != null)
        val dir=File(location!!);val gson=Gson()
        val original=File(dir,"full-evidence.json").bufferedReader().use{gson.fromJson(it,SpeakerDiarizer.EvidenceSnapshot::class.java)}
        val extra=File(dir,"refinement-evidence.json")
        val e=if(extra.exists())original.copy(boundaryRefinement=SpeakerBoundaryRefiner.Evidence(
            extra.bufferedReader().use{gson.fromJson(it,Array<SpeakerBoundaryRefiner.Observation>::class.java).toList()},true))else original
        if(e.boundaryRefinement!=null)File(dir,"full-evidence-with-boundaries.json").bufferedWriter().use{gson.toJson(e,it)}
        val segments=File(dir,"full-segments.json").bufferedReader().use{gson.fromJson(it,Array<WhisperHelper.Segment>::class.java).toList()}
        val reference=com.google.gson.JsonParser.parseString(File(dir,"expected.json").readText()).asJsonObject
        val start=System.nanoTime()
        val result=SpeakerDiarizer.diarize(segments,emptyMap(),null,cachedEvidence=e)
        assertEquals(reference.get("speakers").asInt,result.centroids.size)
        assertEquals(DiarizationUnits.expand(segments)!!.map{it.text},result.units.map{it.text})
        assertEquals(reference.get("units").asInt,result.units.size)
        val normalized=result.text.lineSequence().map{it.trimEnd()}.joinToString("\n").trim()
        val sha=MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString(""){"%02x".format(it)}
        File(dir,"expected-v2.json").writeText(gson.toJson(mapOf("speakers" to result.centroids.size,"units" to result.units.size,"text_sha256" to sha)))
        File(dir,"full-actual-v2.json").writeText(gson.toJson(mapOf("text" to result.text,"units" to result.units,"assignments" to result.speakers,
            "turns" to result.turns,"counts" to result.speakers.toList().groupingBy{it}.eachCount(),"host_ms" to (System.nanoTime()-start)/1e6)))
        println("Private replay: ${result.centroids.size} voices, ${result.units.size} units; text preserved")
    }
}
