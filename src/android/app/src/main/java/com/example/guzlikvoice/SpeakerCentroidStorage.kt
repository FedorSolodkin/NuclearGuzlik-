package com.example.guzlikvoice

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Хранит per-запись центроиды спикеров, найденные последней диаризацией.
 * Позволяет пользователю именовать спикеров позже, не пере-обсчитывая эмбеддинги.
 * Файл guzlikvoice_speaker_centroids.json в filesDir; ключ — timestamp записи.
 */
object SpeakerCentroidStorage {
    private const val TAG = "SpeakerCentroidStorage"
    private const val FILE_NAME = "guzlikvoice_speaker_centroids.json"

    /** Запись про одну диаризацию — сколько спикеров, их центроиды и текущие лейблы.
     *  previewOffsetsMs — миллисекунда в аудио, откуда играть 2-3 сек preview голоса
     *  (для NameSpeakersSheet). Default для миграции старых JSON-снимков. */
    data class Snapshot(
        val timestamp: Long,
        val labels: List<String>,
        val centroids: List<FloatArray>,
        val matchedProfileIds: List<String?>,
        val previewOffsetsMs: LongArray = LongArray(0),
    )

    private val gson = Gson()
    private val lock = Any()
    private var cache: MutableMap<Long, Snapshot>? = null

    private fun storageFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun load(ctx: Context): MutableMap<Long, Snapshot> {
        cache?.let { return it }
        val loaded: MutableMap<Long, Snapshot> = try {
            val f = storageFile(ctx)
            if (!f.exists()) mutableMapOf()
            else gson.fromJson(f.readText(), object : TypeToken<MutableMap<Long, Snapshot>>() {}.type)
                ?: mutableMapOf()
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            mutableMapOf()
        }
        cache = loaded
        return loaded
    }

    private fun persist(ctx: Context, map: Map<Long, Snapshot>) {
        cache = map.toMutableMap()
        val target = storageFile(ctx)
        val tmp = File(target.parentFile ?: ctx.filesDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(gson.toJson(map))
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeText(gson.toJson(map))
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
            tmp.delete()
        }
    }

    /** «Удалить все данные»: и кэш, и файл. */
    fun clearAll(ctx: Context) = synchronized(lock) {
        cache = mutableMapOf()
        storageFile(ctx).delete()
    }

    fun remove(ctx: Context, timestamp: Long) = synchronized(lock) {
        val map = load(ctx)
        if (map.remove(timestamp) != null) persist(ctx, map)
    }

    fun put(ctx: Context, snapshot: Snapshot) = synchronized(lock) {
        val map = load(ctx)
        map[snapshot.timestamp] = snapshot
        persist(ctx, map)
    }

    /** Сохраняет снапшот из результата диаризации, если там реально есть центроиды
     *  (не пустой fallback по паузам) — иначе именовать голоса всё равно нечего. */
    fun putFromResult(ctx: Context, timestamp: Long, result: SpeakerDiarizer.Result?) {
        if (result == null || result.centroids.isEmpty()) return
        put(ctx, Snapshot(
            timestamp         = timestamp,
            labels            = result.displayNames,
            centroids         = result.centroids,
            matchedProfileIds = result.matchedProfileIds,
            previewOffsetsMs  = result.previewOffsetsMs,
        ))
    }

    fun get(ctx: Context, timestamp: Long): Snapshot? = synchronized(lock) {
        load(ctx)[timestamp]
    }

    fun updateLabel(ctx: Context, timestamp: Long, speakerIndex: Int, newLabel: String, profileId: String?) = synchronized(lock) {
        val map = load(ctx)
        val snap = map[timestamp] ?: return@synchronized
        if (speakerIndex !in snap.labels.indices) return@synchronized
        val newLabels = snap.labels.toMutableList().apply { this[speakerIndex] = newLabel }
        val newIds    = snap.matchedProfileIds.toMutableList().apply { this[speakerIndex] = profileId }
        map[timestamp] = snap.copy(labels = newLabels, matchedProfileIds = newIds)
        persist(ctx, map)
    }
}
