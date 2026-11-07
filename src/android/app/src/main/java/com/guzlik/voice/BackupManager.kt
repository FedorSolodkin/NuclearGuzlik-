package com.guzlik.voice

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Резервная копия всех данных в один файл и восстановление из него.
 *
 * Нужна, чтобы перенести записи между телефонами и при переезде на новый пакет
 * приложения (com.guzlik.voice → ru.guzlik.voice): данные старого
 * приложения новому недоступны, а ключи Keystore к другому приложению не переходят.
 *
 * Файл: "VMBK" + версия + соль + число итераций, дальше zip, зашифрованный
 * [ChunkedAead] ключом из пароля (PBKDF2-HMAC-SHA256). Без пароля файл
 * бесполезен, поэтому его можно хранить в облаке или передавать мессенджером.
 *
 * Внутри zip: manifest.json, records.json, audio/<timestamp>.<ext>, клиенты,
 * голосовые профили, отпечатки голосов записей, часть настроек.
 * Модели не входят — их проще скачать заново.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private val MAGIC = "VMBK".toByteArray()
    private const val FORMAT = 1
    private const val ITERATIONS = 200_000
    const val MIN_PASSWORD = 6

    private const val CLIENTS = "guzlikvoice_clients.json"
    private const val PROFILES = "guzlikvoice_voice_profiles.json"
    private const val CENTROIDS = "guzlikvoice_speaker_centroids.json"
    private val PREF_KEYS = listOf("custom_dictionary", "asr_language", "mic_sensitivity_threshold", "mic_mode_auto",
        "diarization_enabled", "selected_template", "whisper_model")

    private val gson: Gson = GsonBuilder().create()

    class WrongPasswordException : Exception("wrong password or damaged file")

    data class ExportResult(val entries: Int, val audioFiles: Int, val skippedProtected: Int)
    data class ImportResult(val added: Int, val skippedExisting: Int, val audioFiles: Int, val clients: Int,
                            val wasProtected: Int)

    private data class Manifest(
        val format: Int, val app: String, val createdAt: Long, val entries: Int,
        val wasProtected: List<Long>,
    )

    internal fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    // ── export ────────────────────────────────────────────────────────────────

    /**
     * Пишет копию в [target]. Защищённые записи попадают в копию расшифрованными
     * только если vault сейчас разблокирован ([includeProtected]); иначе пропускаются.
     */
    fun export(ctx: Context, target: Uri, password: CharArray, includeProtected: Boolean): ExportResult {
        val out = ctx.contentResolver.openOutputStream(target, "wt") ?: error("cannot open target")
        return out.use { export(ctx, it, password, includeProtected) }
    }

    fun export(ctx: Context, out: OutputStream, password: CharArray, includeProtected: Boolean): ExportResult {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)
        DataOutputStream(out).apply {
            write(MAGIC); writeByte(FORMAT); write(salt); writeInt(ITERATIONS); flush()
        }
        var result: ExportResult? = null
        pipe(
            produce = { zipOut -> result = writeZip(ctx, zipOut, includeProtected) },
            consume = { zipIn -> ChunkedAead.encrypt(key, zipIn, out) },
        )
        return result!!
    }

    private fun writeZip(ctx: Context, raw: OutputStream, includeProtected: Boolean): ExportResult {
        val days = RecordStorage.getAllRecords(ctx)
        var audio = 0
        var skipped = 0
        val wasProtected = mutableListOf<Long>()
        val exported = mutableListOf<DailyRecord>()
        ZipOutputStream(raw).use { zip ->
            fun putBytes(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun putFile(name: String, f: File) {
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip, 1 shl 16) }; zip.closeEntry()
            }
            for (day in days) {
                val entries = mutableListOf<DailyRecord.Entry>()
                for (e in day.entries) {
                    var entry = e
                    var audioFile: File? = e.audioPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
                    if (e.isEncrypted) {
                        val payload = if (includeProtected) RecordStorage.readDecryptedPayload(ctx, e.timestamp) else null
                        if (payload == null) { skipped++; continue }
                        entry = decryptedCopy(e, payload)
                        val ext = payload["audioPath"].orEmpty().substringAfterLast('.', "m4a")
                        audioFile = try { VaultManager.playableAudio(ctx, e.timestamp, ext) } catch (_: Exception) { null }
                        wasProtected += e.timestamp
                    }
                    if (audioFile != null) {
                        val name = "audio/${e.timestamp}.${audioFile.extension.ifEmpty { "m4a" }}"
                        putFile(name, audioFile)
                        entry = entry.copy(audioPath = name)
                        audio++
                    } else entry = entry.copy(audioPath = "")
                    entries += entry
                }
                if (entries.isNotEmpty() || day.daySummary.isNotBlank()) {
                    exported += day.copy(entries = entries)
                }
            }
            putBytes("records.json", gson.toJson(exported).toByteArray())
            for (name in listOf(CLIENTS, PROFILES, CENTROIDS)) {
                File(ctx.filesDir, name).takeIf { it.exists() }?.let { putFile("data/$name", it) }
            }
            val prefs = ctx.getSharedPreferences("guzlikvoice_prefs", Context.MODE_PRIVATE).all
                .filterKeys { it in PREF_KEYS }
                .mapValues { it.value.toString() }
            putBytes("prefs.json", gson.toJson(prefs).toByteArray())
            val count = exported.sumOf { it.entries.size }
            putBytes("manifest.json", gson.toJson(Manifest(FORMAT, BuildConfig.VERSION_NAME,
                System.currentTimeMillis(), count, wasProtected)).toByteArray())
            return ExportResult(count, audio, skipped)
        }
    }

    private fun decryptedCopy(e: DailyRecord.Entry, payload: Map<String, String>): DailyRecord.Entry {
        val listType = object : TypeToken<List<String>>() {}.type
        val segType = object : TypeToken<List<WhisperHelper.Segment>>() {}.type
        fun <T> parse(key: String, type: java.lang.reflect.Type): T? =
            payload[key]?.takeIf { it.isNotEmpty() }?.let { runCatching { gson.fromJson<T>(it, type) }.getOrNull() }
        return e.copy(
            text = payload["text"].orEmpty(),
            structured = payload["structured"].orEmpty(),
            templateResult = payload["templateResult"]?.ifEmpty { null },
            segments = parse("segments", segType),
            keyThoughts = parse("keyThoughts", listType) ?: emptyList(),
            tags = parse("tags", listType) ?: emptyList(),
            isEncrypted = false,
        )
    }

    // ── import ────────────────────────────────────────────────────────────────

    fun import(ctx: Context, source: Uri, password: CharArray): ImportResult {
        val inp = ctx.contentResolver.openInputStream(source) ?: error("cannot open source")
        return inp.use { import(ctx, it, password) }
    }

    fun import(ctx: Context, input: InputStream, password: CharArray): ImportResult {
        val din = DataInputStream(input.buffered())
        val magic = ByteArray(4).also { din.readFully(it) }
        require(magic.contentEquals(MAGIC)) { "Это не файл резервной копии GuzlikVoice" }
        val format = din.readUnsignedByte()
        require(format == FORMAT) { "Копия сделана более новой версией приложения — обновите GuzlikVoice" }
        val salt = ByteArray(16).also { din.readFully(it) }
        val iterations = din.readInt()
        require(iterations in 10_000..5_000_000) { "Файл копии повреждён" }
        val key = deriveKey(password, salt, iterations)

        val staging = File(ctx.cacheDir, "backup_import").apply { deleteRecursively(); mkdirs() }
        try {
            pipe(
                produce = { out ->
                    try { ChunkedAead.decrypt(key, din, out) }
                    catch (e: javax.crypto.AEADBadTagException) { throw WrongPasswordException() }
                },
                consume = { zipIn -> unzip(zipIn, staging) },
            )
            return applyImport(ctx, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unzip(input: InputStream, dir: File) {
        val root = dir.canonicalPath + File.separator
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val f = File(dir, e.name)
                // Zip Slip: имена вида ../../x не должны выйти за пределы каталога.
                require(f.canonicalPath.startsWith(root)) { "bad entry ${e.name}" }
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                f.outputStream().use { zip.copyTo(it, 1 shl 16) }
            }
            // Дочитываем хвост, чтобы шифратор на той стороне канала не блокировался.
            // (OutputStream.nullOutputStream() есть только с Android 13.)
            val sink = ByteArray(1 shl 16)
            while (input.read(sink) >= 0) { /* drain */ }
        }
    }

    private fun applyImport(ctx: Context, dir: File): ImportResult {
        val manifest = File(dir, "manifest.json").takeIf { it.exists() }
            ?.let { gson.fromJson(it.readText(), Manifest::class.java) }
            ?: error("Файл копии повреждён")
        val days: List<DailyRecord> = gson.fromJson(File(dir, "records.json").readText(),
            object : TypeToken<List<DailyRecord>>() {}.type) ?: emptyList()

        var added = 0; var existing = 0; var audio = 0
        val audioDir = File(ctx.filesDir, "audio_restored").apply { mkdirs() }
        for (day in days) {
            for (e in day.entries) {
                var path = ""
                if (e.audioPath.startsWith("audio/")) {
                    val src = File(dir, e.audioPath)
                    if (src.exists()) {
                        val dst = File(audioDir, src.name)
                        if (!dst.exists()) src.copyTo(dst)
                        path = dst.absolutePath
                    }
                }
                if (RecordStorage.importEntry(ctx, day.date, e.copy(audioPath = path, isEncrypted = false))) {
                    added++
                    if (path.isNotEmpty()) audio++
                } else {
                    existing++
                }
            }
            if (day.daySummary.isNotBlank()) RecordStorage.saveDaySummary(ctx, day.date, day.daySummary)
        }

        val clients = mergeList<Client>(File(dir, "data/$CLIENTS"), { ClientStorage.getAll(ctx).map { it.id } }) {
            ClientStorage.upsert(ctx, it)
        }
        mergeList<VoiceProfile>(File(dir, "data/$PROFILES"), { VoiceProfileStorage.getAll(ctx).map { it.id } }) {
            VoiceProfileStorage.upsert(ctx, it)
        }
        File(dir, "data/$CENTROIDS").takeIf { it.exists() }?.let { f ->
            val map: Map<Long, SpeakerCentroidStorage.Snapshot> = gson.fromJson(f.readText(),
                object : TypeToken<Map<Long, SpeakerCentroidStorage.Snapshot>>() {}.type) ?: emptyMap()
            for ((ts, snap) in map) if (SpeakerCentroidStorage.get(ctx, ts) == null) SpeakerCentroidStorage.put(ctx, snap)
        }
        File(dir, "prefs.json").takeIf { it.exists() }?.let { f ->
            val stored: Map<String, String> = gson.fromJson(f.readText(),
                object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
            restorePrefs(ctx, stored)
        }
        Log.i(TAG, "import: added=$added existing=$existing audio=$audio")
        return ImportResult(added, existing, audio, clients, manifest.wasProtected.size)
    }

    private inline fun <reified T> mergeList(file: File, existingIds: () -> List<String>, upsert: (T) -> Unit): Int {
        if (!file.exists()) return 0
        val items: List<T> = gson.fromJson(file.readText(), object : TypeToken<List<T>>() {}.type) ?: return 0
        val have = existingIds().toSet()
        var n = 0
        for (item in items) {
            val id = when (item) { is Client -> item.id; is VoiceProfile -> item.id; else -> continue }
            if (id !in have) { upsert(item); n++ }
        }
        return n
    }

    /** Настройки восстанавливаем только те, что пользователь здесь ещё не менял. */
    private fun restorePrefs(ctx: Context, stored: Map<String, String>) {
        val sp = ctx.getSharedPreferences("guzlikvoice_prefs", Context.MODE_PRIVATE)
        val e = sp.edit()
        for ((k, v) in stored) {
            if (k !in PREF_KEYS || sp.contains(k)) continue
            when (k) {
                "diarization_enabled", "mic_mode_auto" -> e.putBoolean(k, v.toBoolean())
                "mic_sensitivity_threshold" -> v.toFloatOrNull()?.let { e.putFloat(k, it) }
                else -> e.putString(k, v)
            }
        }
        if (stored["custom_dictionary"]?.isNotBlank() == true && sp.getString("custom_dictionary", "").isNullOrBlank()) {
            e.putString("custom_dictionary", stored["custom_dictionary"])
        }
        e.apply()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Связывает производителя и потребителя через канал: zip пишется прямо в
     * шифратор (и наоборот), без промежуточного файла с открытыми данными.
     */
    private fun pipe(produce: (OutputStream) -> Unit, consume: (InputStream) -> Unit) {
        val pin = PipedInputStream(1 shl 16)
        val pout = PipedOutputStream(pin)
        var producerError: Throwable? = null
        val producer = Thread({
            try { pout.use(produce) } catch (t: Throwable) { producerError = t; runCatching { pin.close() } }
        }, "backup-pipe")
        producer.start()
        try {
            pin.use(consume)
        } catch (t: Throwable) {
            producer.join()
            throw producerError ?: t
        }
        producer.join()
        producerError?.let { throw it }
    }
}
