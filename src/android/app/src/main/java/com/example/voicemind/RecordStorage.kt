package com.example.voicemind

import android.content.Context
import android.util.Log
import com.example.voicemind.db.DaySummaryEntity
import com.example.voicemind.db.EntryEntity
import com.example.voicemind.db.RecordDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Хранилище записей поверх Room ([RecordDatabase]). API прежний — экраны не
 * знают, что под ним больше не один большой JSON.
 *
 * При первом запуске новой версии старый voicemind_records.json переносится в
 * базу и удаляется (после сверки количества записей).
 */
object RecordStorage {
    private const val TAG = "RecordStorage"
    private const val LEGACY_JSON = "voicemind_records.json"
    private val gson = Gson()

    // SimpleDateFormat не потокобезопасен.
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
    private fun dateKeyFor(timestamp: Long): String = dateFormat.get()!!.format(Date(timestamp))

    /** Чтение-изменение-запись одной записи — под этим замком (как и раньше). */
    private val lock = Any()
    @Volatile private var migrated = false

    private fun dao(context: Context) = RecordDatabase.get(context).entries().also { migrateLegacyJson(context) }

    /** Cheap start-of-job snapshot; does not deserialize a long recording's word timeline. */
    internal fun textSnapshot(context: Context, timestamp: Long): String? = synchronized(lock) {
        dao(context).textByTimestamp(timestamp)
    }

    // Подписчики на любые изменения записей (например, История перечитывает
    // список, когда текст поправили в карточке записи). Вызываются в UI-потоке.
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun addChangeListener(l: () -> Unit) { changeListeners.addIfAbsent(l) }
    fun removeChangeListener(l: () -> Unit) { changeListeners.remove(l) }
    private fun notifyChanged() { for (l in changeListeners) mainHandler.post(l) }

    // ── mapping ───────────────────────────────────────────────────────────────

    private fun EntryEntity.toEntry() = DailyRecord.Entry(
        timestamp = timestamp, text = text, source = source, structured = structured,
        keyThoughts = keyThoughts, tags = tags, audioPath = audioPath, templateId = templateId,
        templateResult = templateResult, clientId = clientId, isEncrypted = isEncrypted,
        calendarEventId = calendarEventId, segments = segments,
    )

    private fun DailyRecord.Entry.toEntity(date: String) = EntryEntity(
        timestamp = timestamp, date = date, text = text, source = source, structured = structured,
        keyThoughts = keyThoughts, tags = tags, audioPath = audioPath ?: "", templateId = templateId,
        templateResult = templateResult, clientId = clientId, isEncrypted = isEncrypted,
        calendarEventId = calendarEventId, segments = segments,
    )

    /** Изменяет одну запись; false — записи нет. */
    private fun update(context: Context, timestamp: Long, change: (EntryEntity) -> EntryEntity): Boolean {
        val ok = synchronized(lock) {
            val d = dao(context)
            val e = d.byTimestamp(timestamp) ?: return@synchronized false
            d.upsert(change(e))
            true
        }
        if (ok) notifyChanged()
        return ok
    }

    // ── migration ─────────────────────────────────────────────────────────────

    /** Переносит записи из JSON прошлых версий (один раз). */
    private fun migrateLegacyJson(context: Context) {
        if (migrated) return
        synchronized(lock) {
            if (migrated) return
            val file = File(context.filesDir, LEGACY_JSON)
            if (file.exists()) {
                try {
                    val type = object : TypeToken<Map<String, DailyRecord>>() {}.type
                    val records: Map<String, DailyRecord> = gson.fromJson(file.readText(), type) ?: emptyMap()
                    val entities = records.flatMap { (date, rec) ->
                        rec.entries.map { it.normalized().toEntity(date) }
                    }
                    val db = RecordDatabase.get(context)
                    db.runInTransaction {
                        db.entries().insertAllIgnore(entities)
                        records.forEach { (date, rec) ->
                            if (rec.daySummary.isNotBlank()) db.entries().upsertSummary(DaySummaryEntity(date, rec.daySummary))
                        }
                    }
                    val stored = db.entries().count()
                    if (stored >= entities.map { it.timestamp }.distinct().size) {
                        file.delete()
                        File(context.filesDir, "$LEGACY_JSON.tmp").delete()
                        Log.i(TAG, "Migrated ${entities.size} entries from JSON")
                    } else {
                        Log.e(TAG, "Migration count mismatch: json=${entities.size} db=$stored — JSON kept")
                    }
                } catch (e: Exception) {
                    // JSON не удаляем — попробуем при следующем запуске.
                    Log.e(TAG, "JSON migration failed", e)
                    return
                }
            }
            migrated = true
        }
    }

    /** Gson создаёт объекты в обход конструктора — null в не-null полях старого JSON. */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    private fun DailyRecord.Entry.normalized() = copy(
        text = text ?: "", source = source ?: "voice", structured = structured ?: "",
        keyThoughts = keyThoughts ?: emptyList(), tags = tags ?: emptyList(), audioPath = audioPath ?: "",
    )

    // ── queries ───────────────────────────────────────────────────────────────

    /**
     * @param linkPendingEvent забрать явную привязку к событию календаря
     *        ([PendingCalendarLink]). false для импорта файлов и восстановления
     *        после сбоя — иначе они «съедали» привязку, предназначенную записи.
     */
    fun addToToday(context: Context, text: String, source: String,
                   analyzer: TextAnalyzer?, audioPath: String = "",
                   linkPendingEvent: Boolean = true): Long {
        val result = analyzer?.analyze(text)
        val ts = synchronized(lock) {
            val d = dao(context)
            // timestamp — ключ записи; две записи в одну миллисекунду (импорт +
            // запись) раньше молча делили id. Теперь берём следующий свободный.
            var ts = System.currentTimeMillis()
            while (d.byTimestamp(ts) != null) ts++
            // Явный выбор пользователя (клик по карточке события) имеет приоритет
            // над угадыванием встречи по времени.
            val eventId = (if (linkPendingEvent) PendingCalendarLink.consume() else null)
                ?: CalendarLinker.findMatchingEvent(context, ts)?.id
            d.insert(EntryEntity(
                timestamp = ts, date = dateKeyFor(ts), text = text, source = source,
                structured = result?.structured ?: "", keyThoughts = result?.keyThoughts ?: emptyList(),
                tags = result?.tags ?: emptyList(), audioPath = audioPath, templateId = null,
                templateResult = null, clientId = null, isEncrypted = false,
                calendarEventId = eventId, segments = null,
            ))
            ts
        }
        notifyChanged()
        return ts
    }

    fun getEntriesForCalendarEvent(context: Context, eventId: String): List<Pair<String, DailyRecord.Entry>> =
        dao(context).byCalendarEvent(eventId).map { it.date to it.toEntry() }

    fun updateEntryText(context: Context, timestamp: Long, newText: String) {
        update(context, timestamp) { if (it.isEncrypted) it else it.copy(text = newText) }
    }

    /** A completed device recording must not overwrite a user's edit or recreate deleted data. */
    fun saveWearableTranscript(context: Context, timestamp: Long, expectedText: String, text: String,
                               segments: List<WhisperHelper.Segment>): Boolean {
        val analysis = TextAnalyzer().analyze(text)
        val saved = synchronized(lock) {
            val d = dao(context)
            val entry = d.byTimestamp(timestamp) ?: return@synchronized false
            if (entry.isEncrypted || entry.text != expectedText) return@synchronized false
            d.upsert(entry.copy(text = text, segments = segments, structured = analysis.structured,
                keyThoughts = analysis.keyThoughts, tags = analysis.tags))
            true
        }
        if (saved) notifyChanged()
        return saved
    }

    internal fun saveWearableMetrics(context: Context, timestamp: Long, json: String): Boolean = synchronized(lock) {
        val entry = dao(context).byTimestamp(timestamp) ?: return@synchronized false
        if (entry.isEncrypted) return@synchronized false
        File(context.filesDir, "wearable_$timestamp.metrics.json").writeText(json)
        true
    }

    /** Persist the name and transcript together, independently of a sheet callback. */
    fun renameSpeaker(context:Context,timestamp:Long,index:Int,name:String,profileId:String?):Boolean {
        if(name.isBlank() || name.any{it=='[' || it==']' || it=='\n' || it=='\r'})return false
        val saved=synchronized(lock) {
            val d=dao(context)
            val entry=d.byTimestamp(timestamp) ?: return@synchronized false
            if(entry.isEncrypted)return@synchronized false
            val snapshot=SpeakerCentroidStorage.get(context,timestamp) ?: return@synchronized false
            val old=snapshot.labels.getOrNull(index) ?: return@synchronized false
            val text=entry.text.replace("[Говорящий $old]","[Говорящий $name]")
            d.upsert(entry.copy(text=text))
            SpeakerCentroidStorage.updateLabel(context,timestamp,index,name,profileId)
            true
        }
        if(saved)notifyChanged()
        return saved
    }

    /** Compare-and-set: a background result cannot overwrite edits, deletion or protection. */
    fun saveDiarizationResult(context: Context, timestamp: Long, expectedText: String, text: String?,
                              segments: List<WhisperHelper.Segment>, result: SpeakerDiarizer.Result): Boolean {
        val saved = synchronized(lock) {
            val d = dao(context)
            val entry = d.byTimestamp(timestamp) ?: return@synchronized false
            if (entry.isEncrypted || entry.text != expectedText) return@synchronized false
            d.upsert(entry.copy(text = text ?: entry.text, segments = segments))
            SpeakerCentroidStorage.putFromResult(context, timestamp, result)
            true
        }
        if (saved) notifyChanged()
        return saved
    }

    /** Сохраняет фразы с таймкодами, посчитанные во время записи (см. TranscriptionSession). */
    fun updateEntrySegments(context: Context, timestamp: Long, segments: List<WhisperHelper.Segment>) {
        update(context, timestamp) { if (it.isEncrypted) it else it.copy(segments = segments) }
    }

    /** Результат шаблона (протокол совещания и т.п.) — попадает в экспорт PDF/Word. */
    fun updateEntryTemplate(context: Context, timestamp: Long, templateId: String, result: String) {
        update(context, timestamp) { if (it.isEncrypted) it else it.copy(templateId = templateId, templateResult = result) }
    }

    fun saveTemplateResult(context: Context, timestamp: Long, expectedText: String, templateId: String, result: String): Boolean {
        val saved=synchronized(lock) {
            val d=dao(context)
            val entry=d.byTimestamp(timestamp) ?: return@synchronized false
            if(entry.isEncrypted || entry.text!=expectedText)return@synchronized false
            d.upsert(entry.copy(templateId=templateId,templateResult=result))
            true
        }
        if(saved)notifyChanged()
        return saved
    }

    fun getEntry(context: Context, date: String, timestamp: Long): DailyRecord.Entry? =
        dao(context).byTimestamp(timestamp)?.takeIf { it.date == date }?.toEntry()

    fun updateEntryClient(context: Context, date: String, timestamp: Long, clientId: String?) {
        update(context, timestamp) { it.copy(clientId = clientId) }
    }

    /** Все записи, привязанные к клиенту, по убыванию времени. */
    fun getEntriesForClient(context: Context, clientId: String): List<Pair<String, DailyRecord.Entry>> =
        dao(context).byClient(clientId).map { it.date to it.toEntry() }

    /** Найти запись по timestamp (deep-link из уведомления): (date, entry) или null. */
    fun findEntryByTimestamp(context: Context, timestamp: Long): Pair<String, DailyRecord.Entry>? =
        dao(context).byTimestamp(timestamp)?.let { it.date to it.toEntry() }

    /** Удаляет запись по timestamp (draft cleanup после отмены обработки). */
    fun deleteByTimestamp(context: Context, timestamp: Long) {
        val date = dao(context).byTimestamp(timestamp)?.date ?: return
        deleteEntry(context, date, timestamp)
    }

    fun deleteEntry(context: Context, date: String, timestamp: Long): Boolean {
        val victim = synchronized(lock) {
            val d = dao(context)
            val e = d.byTimestamp(timestamp)?.takeIf { it.date == date } ?: return false
            d.delete(timestamp)
            e
        }
        DiarizationJobRunner.cancel(timestamp)
        // Аудиофайл тоже удаляем — иначе папка распухала.
        victim.audioPath.takeIf { it.isNotBlank() }?.let { path ->
            try { File(path).takeIf { it.exists() }?.delete() }
            catch (e: Exception) { Log.w(TAG, "audio delete failed: ${e.message}") }
        }
        File(context.filesDir, "wearable_$timestamp.metrics.json").delete()
        VaultManager.deleteEncrypted(context, timestamp)
        SpeakerCentroidStorage.remove(context, timestamp)
        DiarizationEvidenceCache.remove(timestamp)
        DiarizationCheckpointStore.remove(context, timestamp)
        notifyChanged()
        return true
    }

    const val ENCRYPTED_PLACEHOLDER = "Защищённая запись — разблокируйте, чтобы прочитать"

    /**
     * Защищает запись: текст, фразы с таймкодами, итоги, теги и аудио уходят в
     * vault (AES-GCM, ключ в Keystore), в базе остаётся placeholder.
     * Аудио шифруется, исходный файл удаляется.
     * Блокирующий (аудио может быть большим) — не из UI-потока.
     * Бросает [VaultManager.NeedAuthException], если аутентификация протухла.
     */
    fun encryptEntry(context: Context, date: String, timestamp: Long): Boolean {
        check(WearableService.recordingEntryId != timestamp) { "Сначала остановите запись с устройства" }
        DiarizationJobRunner.cancel(timestamp)
        val ok = synchronized(lock) {
            val d = dao(context)
            val entry = d.byTimestamp(timestamp)?.takeIf { it.date == date } ?: return false
            if (entry.isEncrypted) return true

            val audio = entry.audioPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
            val payload = gson.toJson(mapOf(
                "text"           to entry.text,
                "structured"     to entry.structured,
                "templateResult" to (entry.templateResult ?: ""),
                "segments"       to (entry.segments?.let { gson.toJson(it) } ?: ""),
                "keyThoughts"    to gson.toJson(entry.keyThoughts),
                "tags"           to gson.toJson(entry.tags),
                "audioPath"      to (audio?.absolutePath ?: "")
            ))
            try {
                VaultManager.encryptText(context, timestamp, payload)
                if (audio != null) VaultManager.encryptAudio(context, timestamp, audio)
            } catch (e: Exception) {
                VaultManager.deleteEncrypted(context, timestamp)
                throw e
            }
            d.upsert(entry.copy(
                text           = ENCRYPTED_PLACEHOLDER,
                structured     = "",
                templateResult = entry.templateResult?.let { "" },
                segments       = null,
                keyThoughts    = emptyList(),
                tags           = emptyList(),
                audioPath      = "",
                isEncrypted    = true
            ))
            // Открытый оригинал удаляем только когда шифрованная копия и строка в базе записаны.
            audio?.delete()
            File(context.filesDir, "wearable_$timestamp.metrics.json").delete()
            true
        }
        if (ok) {
            DiarizationEvidenceCache.remove(timestamp)
            DiarizationCheckpointStore.remove(context, timestamp)
            notifyChanged()
        }
        return ok
    }

    /** Снимает защиту: возвращает текст и аудио на место, удаляет vault-файлы. */
    fun decryptEntry(context: Context, date: String, timestamp: Long): Boolean {
        val ok = synchronized(lock) {
            val d = dao(context)
            val entry = d.byTimestamp(timestamp)?.takeIf { it.date == date } ?: return false
            if (!entry.isEncrypted) return true

            val payload = parsePayload(VaultManager.decryptText(context, timestamp) ?: return false)
            val audioPath = payload["audioPath"].orEmpty()
            if (audioPath.isNotEmpty() && VaultManager.audioFileFor(context, timestamp).exists()) {
                VaultManager.decryptAudioTo(context, timestamp, File(audioPath))
            }
            val listType = object : TypeToken<List<String>>() {}.type
            val segType  = object : TypeToken<List<WhisperHelper.Segment>>() {}.type
            fun <T> parse(key: String, type: java.lang.reflect.Type): T? =
                payload[key]?.takeIf { it.isNotEmpty() }?.let {
                    try { gson.fromJson<T>(it, type) } catch (_: Exception) { null }
                }
            d.upsert(entry.copy(
                text           = payload["text"].orEmpty(),
                structured     = payload["structured"].orEmpty(),
                templateResult = payload["templateResult"].orEmpty().ifEmpty { entry.templateResult },
                segments       = parse<List<WhisperHelper.Segment>>("segments", segType) ?: entry.segments,
                keyThoughts    = parse<List<String>>("keyThoughts", listType) ?: entry.keyThoughts,
                tags           = parse<List<String>>("tags", listType) ?: entry.tags,
                audioPath      = if (audioPath.isNotEmpty() && File(audioPath).exists()) audioPath else entry.audioPath,
                isEncrypted    = false
            ))
            VaultManager.deleteEncrypted(context, timestamp)
            true
        }
        if (ok) notifyChanged()
        return ok
    }

    private fun parsePayload(json: String): Map<String, String> = try {
        gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    /**
     * Расшифрованное содержимое защищённой записи (без изменения хранилища).
     * null — vault заперт, аутентификация протухла или ключ потерян.
     */
    fun readDecryptedPayload(context: Context, timestamp: Long): Map<String, String>? = try {
        VaultManager.decryptText(context, timestamp)?.let(::parsePayload)
    } catch (e: Exception) {
        VaultManager.logFailure("readDecryptedPayload", e)
        null
    }

    /**
     * Удаляет vault-файлы, для которых нет соответствующей защищённой записи.
     * Иначе приватные данные, которые пользователь считает удалёнными, лежат на диске.
     */
    fun pruneOrphanVaultFiles(context: Context) {
        val known = dao(context).encryptedTimestamps().toSet()
        for ((ts, files) in VaultManager.storedTimestamps(context)) {
            if (ts in known) continue
            files.forEach { f -> if (f.delete()) Log.i(TAG, "Removed orphan vault file: ${f.name}") }
        }
    }

    /**
     * Вставляет запись из резервной копии с её исходным timestamp.
     * false — запись с таким timestamp уже есть (повторное восстановление ничего не дублирует).
     */
    fun importEntry(context: Context, date: String, entry: DailyRecord.Entry): Boolean {
        val inserted = synchronized(lock) {
            dao(context).insertAllIgnore(listOf(entry.normalized().toEntity(date))).first() != -1L
        }
        if (inserted) notifyChanged()
        return inserted
    }

    fun hasProtectedEntries(context: Context): Boolean = dao(context).encryptedTimestamps().isNotEmpty()

    fun saveDaySummary(context: Context, date: String, summary: String) {
        dao(context).upsertSummary(DaySummaryEntity(date, summary))
        notifyChanged()
    }

    fun saveDaySummaryIfUnchanged(context:Context,date:String,expected:Map<Long,String>,summary:String):Boolean {
        val saved=synchronized(lock) {
            val d=dao(context)
            if(expected.isEmpty() || expected.any{(ts,text)->
                val e=d.byTimestamp(ts)
                e==null || e.isEncrypted || e.text!=text || e.date!=date
            })return@synchronized false
            d.upsertSummary(DaySummaryEntity(date,summary))
            true
        }
        if(saved)notifyChanged()
        return saved
    }

    /** Все дни с записями, от новых к старым. Для всей истории — не из UI-потока. */
    fun getAllRecords(context: Context): List<DailyRecord> {
        val d = dao(context)
        val summaries = d.allSummaries().associate { it.date to it.summary }
        val byDate = d.all().groupBy { it.date }
        return (byDate.keys + summaries.keys).distinct().sortedDescending().map { date ->
            DailyRecord(
                date = date,
                // Внутри дня — по возрастанию времени, как раньше в JSON.
                entries = byDate[date].orEmpty().sortedBy { it.timestamp }.map { it.toEntry() }.toMutableList(),
                daySummary = summaries[date].orEmpty(),
            )
        }.filter { it.entries.isNotEmpty() || it.daySummary.isNotBlank() }
    }
}
