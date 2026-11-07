package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test

class DiarizationCheckpointTest {
    private fun checkpoint() = DiarizationCheckpointCodec.Checkpoint(
        DiarizationEvidenceCache.Key(42,"/record.mp3",1000,2000,"v3"),1,2,
        SpeakerDiarizer.EvidenceSnapshot(listOf(floatArrayOf(1f,0.1234567f)),
            listOf(SpeakerActivityFusion.Window(0,10000),SpeakerActivityFusion.Window(5000,15000)),
            listOf(SpeakerDiarizer.EmbeddingRegion(100,200,0,0,100))),3,true)
    @Test fun losslessPartialRoundTrip() {
        val c=checkpoint(); val d=DiarizationCheckpointCodec.decode(DiarizationCheckpointCodec.encode(c))
        assertEquals(c.key,d.key);assertEquals(1,d.processed);assertEquals(2,d.total)
        assertEquals(3,d.requested);assertTrue(d.hadLabels);assertFalse(d.finished)
        assertEquals(c.evidence.regions,d.evidence.regions);assertEquals(c.evidence.windows,d.evidence.windows)
        assertArrayEquals(c.evidence.embeddings[0],d.evidence.embeddings[0],0f)
    }
    @Test fun subMillisecondTailCanHaveAnEmptyRoundedWindow() {
        val c=checkpoint().copy(processed=2,evidence=checkpoint().evidence.copy(windows=listOf(
            SpeakerActivityFusion.Window(0,5000),SpeakerActivityFusion.Window(5000,5000))))
        val restored=DiarizationCheckpointCodec.decode(DiarizationCheckpointCodec.encode(c))
        assertEquals(c.evidence.windows,restored.evidence.windows)
    }
    @Test fun binaryV3RemainsCompatibleWithExistingCheckpoints() {
        val legacy=java.util.Base64.getDecoder().decode("AAAAAwAAAAAAAAAqAAsvcmVjb3JkLm1wMwAAAAAAAAPoAAAAAAAAB9AAAnYzAAAAAQAAAAIAAAADAQAAAAACAAAAAAAAAAAAAAAAAAAnEAAAAAAAABOIAAAAAAAAOpgAAAABAAAAAj+AAAA9/NbeAAAAAQAAAAAAAABkAAAAAAAAAMgAAAAAAAAAAAAAAAAAAABk")
        assertArrayEquals(legacy,DiarizationCheckpointCodec.encode(checkpoint()))
        assertEquals(checkpoint().key,DiarizationCheckpointCodec.decode(legacy).key)
    }
    @Test fun incompleteAndCorruptFormatsAreRejected() {
        val bytes=DiarizationCheckpointCodec.encode(checkpoint())
        for (cut in listOf(0,1,10,bytes.size-1)) {
            assertThrows(Exception::class.java) { DiarizationCheckpointCodec.decode(bytes.copyOf(cut)) }
        }
        assertThrows(Exception::class.java) { DiarizationCheckpointCodec.decode(bytes + byteArrayOf(1)) }
        assertThrows(Exception::class.java) { DiarizationCheckpointCodec.decode(bytes.copyOf().apply { this[3]=99 }) }
    }
    @Test fun invalidReferencesAndNonFiniteVectorsAreRejected() {
        val c=checkpoint()
        assertThrows(Exception::class.java) { DiarizationCheckpointCodec.decode(DiarizationCheckpointCodec.encode(c.copy(processed=0))) }
        val bad=c.copy(evidence=c.evidence.copy(embeddings=listOf(floatArrayOf(Float.NaN,0f))))
        assertThrows(Exception::class.java) { DiarizationCheckpointCodec.decode(DiarizationCheckpointCodec.encode(bad)) }
    }
    @Test fun refinedEvidenceRoundTripsWithoutLosingPartialProgress() {
        val base=checkpoint()
        val observations=listOf(SpeakerBoundaryRefiner.Observation(1000,3000,floatArrayOf(.5f,.866f)))
        for(completed in listOf(false,true)){
            val c=base.copy(processed=base.total,evidence=base.evidence.copy(boundaryRefinement=SpeakerBoundaryRefiner.Evidence(observations,completed)))
            val bytes=DiarizationCheckpointCodec.encode(c)
            assertEquals(4,java.nio.ByteBuffer.wrap(bytes).int)
            val restored=DiarizationCheckpointCodec.decode(bytes).evidence.boundaryRefinement!!
            assertEquals(completed,restored.completed);assertEquals(1000L,restored.observations.single().startMs)
            assertArrayEquals(observations[0].embedding,restored.observations[0].embedding,0f)
        }
    }
    @Test fun invalidRefinementCannotBeRestoredOrDeclaredFinished() {
        val base=checkpoint().copy(processed=2)
        val observation=SpeakerBoundaryRefiner.Observation(1000,3000,floatArrayOf(1f,0f))
        val partial=base.copy(evidence=base.evidence.copy(boundaryRefinement=SpeakerBoundaryRefiner.Evidence(listOf(observation),false)))
        assertThrows(Exception::class.java){DiarizationCheckpointCodec.encode(partial.copy(finished=true))}
        for(bad in listOf(observation.copy(startMs=-1),observation.copy(endMs=20000),observation.copy(embedding=floatArrayOf(Float.NaN,0f)))){
            val c=base.copy(evidence=base.evidence.copy(boundaryRefinement=SpeakerBoundaryRefiner.Evidence(listOf(bad),true)))
            assertThrows(Exception::class.java){DiarizationCheckpointCodec.decode(DiarizationCheckpointCodec.encode(c))}
        }
    }

    @Test fun textFingerprintSurvivesAProcessCheckpointWithoutStoringTheText() {
        val hash=DiarizationCheckpointCodec.textFingerprint("Ручная правка.")
        val c=checkpoint().copy(expectedTextHash=hash)
        val bytes=DiarizationCheckpointCodec.encode(c)
        assertEquals(5,java.nio.ByteBuffer.wrap(bytes).int)
        assertEquals(hash,DiarizationCheckpointCodec.decode(bytes).expectedTextHash)
        assertFalse(String(bytes,Charsets.UTF_8).contains("Ручная правка."))
        assertNotEquals(hash,DiarizationCheckpointCodec.textFingerprint("Другая правка."))
    }

}
