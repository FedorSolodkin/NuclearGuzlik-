package com.example.guzlikvoice

import java.util.UUID
import kotlin.math.sqrt

/**
 * Голосовой профиль — именованный спикер (Pro-фича).
 * Хранит несколько эмбеддингов-образцов; centroid() усредняет их для матча.
 * Каждый новый диалог с этим спикером может добавить свой sample через addSample.
 */
data class VoiceProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val samples: List<FloatArray> = emptyList(),
    val clientId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Среднее L2-нормализованное представление спикера — используется для cosine-матча. */
    fun centroid(): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val dim = samples[0].size
        val avg = FloatArray(dim)
        for (s in samples) if (s.size == dim) for (i in 0 until dim) avg[i] += s[i]
        val n = samples.size.toFloat()
        for (i in 0 until dim) avg[i] /= n
        var norm = 0f
        for (v in avg) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in avg.indices) avg[i] /= norm
        return avg
    }
}
