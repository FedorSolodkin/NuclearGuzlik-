package com.example.voicemind

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * JSON-хранилище клиентов (Pro-фича). Файл voicemind_clients.json в filesDir.
 * Тред-safe через synchronized(lock), кэш в памяти для быстрого чтения.
 */
object ClientStorage {
    private const val TAG = "ClientStorage"
    private const val FILE_NAME = "voicemind_clients.json"
    private val gson = Gson()

    private val lock = Any()
    private var cache: MutableList<Client>? = null

    private fun storageFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun load(ctx: Context): MutableList<Client> {
        cache?.let { return it }
        val loaded: MutableList<Client> = try {
            val f = storageFile(ctx)
            if (!f.exists()) mutableListOf()
            else gson.fromJson(f.readText(), object : TypeToken<MutableList<Client>>() {}.type)
                ?: mutableListOf()
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            mutableListOf()
        }
        cache = loaded
        return loaded
    }

    private fun persist(ctx: Context, list: List<Client>) {
        cache = list.toMutableList()
        val target = storageFile(ctx)
        val tmp = File(target.parentFile ?: ctx.filesDir, "$FILE_NAME.tmp")
        try {
            val json = gson.toJson(list)
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeText(json)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
            tmp.delete()
        }
    }

    fun getAll(ctx: Context): List<Client> = synchronized(lock) {
        load(ctx).sortedBy { it.name.lowercase() }
    }

    fun getById(ctx: Context, id: String): Client? = synchronized(lock) {
        load(ctx).firstOrNull { it.id == id }
    }

    fun upsert(ctx: Context, client: Client): Client = synchronized(lock) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == client.id }
        val stamped = client.copy(updatedAt = System.currentTimeMillis())
        if (idx == -1) list.add(stamped) else list[idx] = stamped
        persist(ctx, list)
        stamped
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        persist(ctx, list)
    }

    /** «Удалить все данные»: и кэш, и файл. */
    fun clearAll(ctx: Context) = synchronized(lock) {
        cache = mutableListOf()
        storageFile(ctx).delete()
    }
}
