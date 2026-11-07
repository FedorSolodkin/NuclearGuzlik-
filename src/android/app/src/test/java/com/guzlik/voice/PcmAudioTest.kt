package com.guzlik.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class PcmAudioTest {
    @Test fun diskWindowsMatchOriginalAcrossWorkersAndCloseDeletesTemporaryFile() {
        val samples = ShortArray(100_000) { (it % 32000).toShort() }
        val file = File.createTempFile("pcm-test", ".pcm")
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples); file.writeBytes(bytes.array())
        FilePcm(file).use { pcm ->
            assertEquals(samples.size, pcm.size)
            val pool = Executors.newFixedThreadPool(3)
            try {
                (0..2).map { worker -> pool.submit {
                    for (i in worker until samples.size step 3) assertEquals(samples[i], pcm[i])
                    assertArrayEquals(samples.copyOfRange(8190, 8200), pcm.copyOfRange(8190, 8200))
                } }.forEach { it.get() }
            } finally { pool.shutdownNow() }
        }
        assertFalse(file.exists())
    }
    @Test fun clusteringMatrixIsBoundedButEveryWindowGetsALabel() {
        val embeddings = List(10000) { if (it % 2 == 0) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        val labels = BoundedSpeakerClustering.cluster(embeddings, 99) { sample ->
            assertEquals(99, sample.size)
            IntArray(sample.size) { if (sample[it][0] > .5f) 0 else 1 }
        }
        assertEquals(10000, labels.size)
        assertTrue(labels.indices.all { labels[it] == it % 2 })
    }
}
