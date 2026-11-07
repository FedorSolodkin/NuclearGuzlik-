package com.example.voicemind

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiarizationEvidenceCacheTest {
    private val key = DiarizationEvidenceCache.Key(42,"audio",100,200,"models-v1")
    private fun evidence() = SpeakerDiarizer.EvidenceSnapshot(listOf(floatArrayOf(1f,0f)),
        listOf(SpeakerActivityFusion.Window(0,1000)),listOf(SpeakerDiarizer.EmbeddingRegion(0,1000,0,0)))
    @Before fun reset() { DiarizationEvidenceCache.clearAll() }
    @Test fun onlySameAudioAndModelVersionsHit() {
        val ticket = DiarizationEvidenceCache.ticket(42)
        assertTrue(DiarizationEvidenceCache.put(key,ticket,evidence()))
        assertNotNull(DiarizationEvidenceCache.get(key,ticket))
        assertNull(DiarizationEvidenceCache.get(key.copy(audioModified=201),ticket))
        assertNull(DiarizationEvidenceCache.get(key.copy(models="models-v2"),ticket))
        assertNull(DiarizationEvidenceCache.get(key.copy(audioBytes=101),ticket))
    }
    @Test fun deleteRejectsLateWriterAsWellAsExistingData() {
        val ticket = DiarizationEvidenceCache.ticket(42)
        DiarizationEvidenceCache.put(key,ticket,evidence())
        DiarizationEvidenceCache.remove(42)
        assertNull(DiarizationEvidenceCache.get(key,ticket))
        assertFalse(DiarizationEvidenceCache.put(key,ticket,evidence()))
        assertTrue(DiarizationEvidenceCache.put(key,DiarizationEvidenceCache.ticket(42),evidence()))
    }
    @Test fun wipeInvalidatesAllPreviouslyIssuedTickets() {
        val ticket = DiarizationEvidenceCache.ticket(42)
        DiarizationEvidenceCache.clearAll()
        assertFalse(DiarizationEvidenceCache.put(key,ticket,evidence()))
    }
    @Test fun memoryLimitAndLastRecordingOnly() {
        val ticket = DiarizationEvidenceCache.ticket(42)
        assertFalse(DiarizationEvidenceCache.put(key,ticket,evidence().copy(embeddings=listOf(FloatArray(3_000_000)))))
        DiarizationEvidenceCache.put(key,ticket,evidence())
        val next = key.copy(timestamp=43)
        DiarizationEvidenceCache.put(next,DiarizationEvidenceCache.ticket(43),evidence())
        assertNull(DiarizationEvidenceCache.get(key,ticket))
    }
    @Test fun decodedAudioIsAnImmutableBoundedCopyWithTheSameInvalidationRules() {
        val ticket = DiarizationEvidenceCache.ticket(42)
        val samples = shortArrayOf(1, 2, 3)
        assertTrue(DiarizationEvidenceCache.putPcm(key, ticket, ArrayPcm(samples)))
        samples[0] = 99
        assertArrayEquals(shortArrayOf(1, 2, 3), DiarizationEvidenceCache.getPcm(key, ticket)!!.copyOfRange(0, 3))
        assertNull(DiarizationEvidenceCache.getPcm(key.copy(audioModified = 201), ticket))
        assertNull(DiarizationEvidenceCache.getPcm(key.copy(models = "new"), ticket))
        DiarizationEvidenceCache.remove(42)
        assertNull(DiarizationEvidenceCache.getPcm(key, ticket))
        assertFalse(DiarizationEvidenceCache.putPcm(key, ticket, ArrayPcm(samples)))
        val current = DiarizationEvidenceCache.ticket(42)
        assertFalse(DiarizationEvidenceCache.putPcm(key, current, ArrayPcm(ShortArray(4_194_305))))
        assertTrue(DiarizationEvidenceCache.putPcm(key, current, ArrayPcm(samples)))
        DiarizationEvidenceCache.clearAll()
        assertNull(DiarizationEvidenceCache.getPcm(key, current))
    }

}
