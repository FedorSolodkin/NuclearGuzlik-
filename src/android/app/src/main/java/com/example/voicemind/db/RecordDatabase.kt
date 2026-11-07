package com.example.voicemind.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.example.voicemind.WhisperHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Записи в SQLite (Room) вместо одного JSON на всё: раньше любое изменение
 * (правка слова, привязка к клиенту) переписывало весь файл со всеми записями,
 * и время росло вместе с историей. Теперь меняется одна строка.
 *
 * Первичный ключ — timestamp записи (мс): он и раньше был её идентификатором
 * во всём приложении (deep-link из уведомлений, vault, центроиды голосов).
 */
@Entity(
    tableName = "entries",
    indices = [Index("date"), Index("client_id"), Index("calendar_event_id")]
)
data class EntryEntity(
    @PrimaryKey val timestamp: Long,
    val date: String,
    val text: String,
    val source: String,
    val structured: String,
    @ColumnInfo(name = "key_thoughts") val keyThoughts: List<String>,
    val tags: List<String>,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "template_result") val templateResult: String?,
    @ColumnInfo(name = "client_id") val clientId: String?,
    @ColumnInfo(name = "is_encrypted") val isEncrypted: Boolean,
    @ColumnInfo(name = "calendar_event_id") val calendarEventId: String?,
    val segments: List<WhisperHelper.Segment>?,
)

@Entity(tableName = "day_summaries")
data class DaySummaryEntity(
    @PrimaryKey val date: String,
    val summary: String,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY timestamp DESC")
    fun all(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE timestamp = :ts")
    fun byTimestamp(ts: Long): EntryEntity?

    @Query("SELECT text FROM entries WHERE timestamp = :ts")
    fun textByTimestamp(ts: Long): String?

    @Query("SELECT * FROM entries WHERE client_id = :clientId ORDER BY timestamp DESC")
    fun byClient(clientId: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE calendar_event_id = :eventId ORDER BY timestamp DESC")
    fun byCalendarEvent(eventId: String): List<EntryEntity>

    @Query("SELECT timestamp FROM entries WHERE is_encrypted = 1")
    fun encryptedTimestamps(): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(e: EntryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllIgnore(e: List<EntryEntity>): List<Long>

    @Upsert
    fun upsert(e: EntryEntity)

    @Query("DELETE FROM entries WHERE timestamp = :ts")
    fun delete(ts: Long): Int

    @Query("SELECT COUNT(*) FROM entries")
    fun count(): Int

    @Query("SELECT * FROM day_summaries")
    fun allSummaries(): List<DaySummaryEntity>

    @Upsert
    fun upsertSummary(s: DaySummaryEntity)
}

class Converters {
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type
    private val segType = object : TypeToken<List<WhisperHelper.Segment>>() {}.type

    @TypeConverter fun fromList(v: List<String>): String = gson.toJson(v)
    @TypeConverter fun toList(v: String): List<String> = gson.fromJson(v, listType) ?: emptyList()
    @TypeConverter fun fromSegments(v: List<WhisperHelper.Segment>?): String? = v?.let(gson::toJson)
    @TypeConverter fun toSegments(v: String?): List<WhisperHelper.Segment>? = v?.let { gson.fromJson(it, segType) }
}

@Database(entities = [EntryEntity::class, DaySummaryEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class RecordDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao

    companion object {
        const val NAME = "voicemind_records.db"
        @Volatile private var instance: RecordDatabase? = null

        fun get(ctx: Context): RecordDatabase = instance ?: synchronized(this) {
            instance ?: build(ctx.applicationContext, NAME).also { instance = it }
        }

        fun build(ctx: Context, name: String?): RecordDatabase {
            val b = if (name == null) Room.inMemoryDatabaseBuilder(ctx, RecordDatabase::class.java)
                    else Room.databaseBuilder(ctx, RecordDatabase::class.java, name)
            // Чтение одной записи по ключу — доли миллисекунды; часть старых экранов
            // читает запись прямо в обработчике нажатия (как раньше из JSON-кэша).
            // Тяжёлые выборки (вся история) вызываются из фоновых потоков.
            return b.allowMainThreadQueries().build()
        }

        /** Только для тестов. */
        fun setForTests(db: RecordDatabase?) { instance = db }
    }
}
