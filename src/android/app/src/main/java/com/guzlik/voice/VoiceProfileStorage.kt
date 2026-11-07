package com.guzlik.voice

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Хранилище голосовых профилей (Pro-фича). JSON в filesDir.
 * matchBest ищет ближайший профиль по косинусной близости эмбеддингов.
 */
object VoiceProfileStorage {
    private const val TAG = "VoiceProfileStorage"
    private const val FILE_NAME = "guzlikvoice_voice_profiles.json"

    /** Косинусный порог УВЕРЕННОГО матча — подставляем имя автоматически, без спроса.
     *  Подобран под L2-нормированные WeSpeaker-эмбеддинги: 0.72 даёт мало ложных срабатываний. */
    const val MATCH_THRESHOLD_CONFIRM = 0.72f

    /** «Серая зона»: 0.62..0.72 — показать пользователю «Похоже на …?», не подставлять молча. */
    const val MATCH_THRESHOLD_SUGGEST = 0.62f

    /** Backward-compat, если где-то ещё используется старое имя. */
    @Deprecated("Use MATCH_THRESHOLD_CONFIRM", ReplaceWith("MATCH_THRESHOLD_CONFIRM"))
    const val MATCH_THRESHOLD = MATCH_THRESHOLD_CONFIRM

    private val gson = Gson()
    private val lock = Any()
    private var cache: MutableList<VoiceProfile>? = null

    private fun storageFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun load(ctx: Context): MutableList<VoiceProfile> {
        cache?.let { return it }
        val loaded: MutableList<VoiceProfile> = try {
            val f = storageFile(ctx)
            if (!f.exists()) mutableListOf()
            else gson.fromJson(f.readText(), object : TypeToken<MutableList<VoiceProfile>>() {}.type)
                ?: mutableListOf()
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            mutableListOf()
        }
        cache = loaded
        return loaded
    }

    private fun persist(ctx: Context, list: List<VoiceProfile>) {
        cache = list.toMutableList()
        // Atomic write: JSON goes to .tmp first, then rename. Prevents a
        // corrupted half-written profile file if the process is killed mid-write
        // (kernel OOM, force-stop). readText on an empty file yields "" which
        // Gson parses to null → we'd silently lose ALL profiles otherwise.
        val target = storageFile(ctx)
        val tmp    = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(gson.toJson(list))
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
            runCatching { tmp.delete() }
        }
    }

    fun getAll(ctx: Context): List<VoiceProfile> = synchronized(lock) {
        load(ctx).sortedBy { it.name.lowercase() }
    }

    fun getById(ctx: Context, id: String): VoiceProfile? = synchronized(lock) {
        load(ctx).firstOrNull { it.id == id }
    }

    fun upsert(ctx: Context, profile: VoiceProfile): VoiceProfile = synchronized(lock) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == profile.id }
        val stamped = profile.copy(updatedAt = System.currentTimeMillis())
        if (idx == -1) list.add(stamped) else list[idx] = stamped
        persist(ctx, list)
        stamped
    }

    /** «Удалить все данные»: и кэш, и файл. */
    fun clearAll(ctx: Context) = synchronized(lock) {
        cache = mutableListOf()
        storageFile(ctx).delete()
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        persist(ctx, list)
    }

    /** Добавляет новый образец эмбеддинга к существующему профилю. */
    fun addSample(ctx: Context, id: String, sample: FloatArray) = synchronized(lock) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == id }
        if (idx == -1) return@synchronized
        val old = list[idx]
        list[idx] = old.copy(
            samples   = old.samples + listOf(sample),
            updatedAt = System.currentTimeMillis()
        )
        persist(ctx, list)
    }

    /**
     * Ищет ближайший профиль по косинусной близости. Возвращает пару (профиль, similarity).
     * null если ни один профиль не превысил порог.
     */
    fun matchBest(
        ctx: Context,
        embedding: FloatArray,
        threshold: Float = MATCH_THRESHOLD_SUGGEST
    ): Pair<VoiceProfile, Float>? = synchronized(lock) {
        if (embedding.isEmpty()) return@synchronized null
        val profiles = load(ctx)
        var best: VoiceProfile? = null
        var bestSim = threshold
        for (p in profiles) {
            val c = p.centroid()
            if (c.size != embedding.size) continue
            var dot = 0f
            for (i in c.indices) dot += c[i] * embedding[i]
            if (dot > bestSim) { best = p; bestSim = dot }
        }
        best?.let { it to bestSim }
    }
}
