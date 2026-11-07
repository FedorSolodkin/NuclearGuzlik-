package com.guzlik.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs

/**
 * Сверка с эталоном torchaudio.compliance.kaldi.fbank (настройки WeSpeaker:
 * 80 фильтров, 25/10 мс, dither=0, шкала int16). Фикстура — 0.5 с реальной речи
 * из датасета SberDevices Golos, сгенерирована torchaudio 2.11.
 */
class FbankExtractorTest {

    private fun fixture(): Pair<ShortArray, List<FloatArray>> {
        val lines = javaClass.classLoader!!.getResourceAsStream("fbank_fixture.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val pcm = lines[0].split(',').map { it.trim().toInt().toShort() }.toShortArray()
        val frames = lines.drop(1).map { l -> l.split(',').map { it.toFloat() }.toFloatArray() }
        return pcm to frames
    }

    @Test fun matchesTorchaudioKaldiFbank() {
        val (pcm, expected) = fixture()
        val actual = FbankExtractor.extractRaw(pcm)
        assertEquals(expected.size, actual.size)
        var maxErr = 0f
        for (t in expected.indices) for (m in 0 until FbankExtractor.NUM_FILTERS) {
            maxErr = maxOf(maxErr, abs(expected[t][m] - actual[t][m]))
        }
        // float32 FFT против float32 torch: расхождение в сотых долях при значениях 4..24
        assert(maxErr < 0.02f) { "max abs error $maxErr" }
    }

    @Test fun noNanOnSilenceAndShortInput() {
        val silence = FbankExtractor.extract(ShortArray(16_000))
        assertFalse(silence.any { row -> row.any { it.isNaN() || it.isInfinite() } })
        assertEquals(0, FbankExtractor.extract(ShortArray(100)).size)
    }

    @Test fun cmnMakesZeroMean() {
        val (pcm, _) = fixture()
        val f = FbankExtractor.extract(pcm)
        for (m in 0 until FbankExtractor.NUM_FILTERS) {
            val mean = f.sumOf { it[m].toDouble() } / f.size
            assertEquals(0.0, mean, 1e-4)
        }
    }
}
