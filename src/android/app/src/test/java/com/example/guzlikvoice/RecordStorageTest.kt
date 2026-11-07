package com.example.guzlikvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.guzlikvoice.db.RecordDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RecordStorageTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        RecordDatabase.setForTests(RecordDatabase.build(ctx, null))
    }

    @After fun tearDown() {
        RecordDatabase.get(ctx).close()
        RecordDatabase.setForTests(null)
    }

    private fun dateOf(ts: Long) = RecordStorage.findEntryByTimestamp(ctx, ts)!!.first

    @Test fun `add, read, update and delete an entry`() {
        val ts = RecordStorage.addToToday(ctx, "Привет", "voice", null)
        val date = dateOf(ts)
        assertEquals("Привет", RecordStorage.getEntry(ctx, date, ts)!!.text)

        RecordStorage.updateEntryText(ctx, ts, "Исправлено")
        RecordStorage.updateEntryClient(ctx, date, ts, "client-1")
        val seg = WhisperHelper.Segment("Исправлено", 0, 900)
        RecordStorage.updateEntrySegments(ctx, ts, listOf(seg))

        val e = RecordStorage.getEntry(ctx, date, ts)!!
        assertEquals("Исправлено", e.text)
        assertEquals(listOf(seg), e.segments)
        assertEquals(listOf(date to e), RecordStorage.getEntriesForClient(ctx, "client-1"))

        assertTrue(RecordStorage.deleteEntry(ctx, date, ts))
        assertNull(RecordStorage.findEntryByTimestamp(ctx, ts))
    }

    @Test fun `background diarization cannot overwrite newer text or a deleted record`() {
        val ts=RecordStorage.addToToday(ctx,"исходный текст","voice",null)
        val result=SpeakerDiarizer.Result("разметка",listOf("А"),listOf(floatArrayOf(1f,0f)),listOf(null))
        RecordStorage.updateEntryText(ctx,ts,"ручные правки")
        assertFalse(RecordStorage.saveDiarizationResult(ctx,ts,"исходный текст","разметка",emptyList(),result))
        assertEquals("ручные правки",RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text)
        assertTrue(RecordStorage.saveDiarizationResult(ctx,ts,"ручные правки","разметка",emptyList(),result))
        RecordStorage.deleteByTimestamp(ctx,ts)
        assertFalse(RecordStorage.saveDiarizationResult(ctx,ts,"разметка","новое",emptyList(),result))
        assertNull(SpeakerCentroidStorage.get(ctx,ts))
    }

    @Test fun `entries created in the same millisecond get distinct ids`() {
        val ids = (1..50).map { RecordStorage.addToToday(ctx, "запись $it", "import", null) }
        assertEquals(50, ids.toSet().size)
    }

    @Test fun `deleting an entry removes its audio file`() {
        val audio = File(ctx.filesDir, "rec.m4a").apply { writeBytes(ByteArray(10)) }
        val ts = RecordStorage.addToToday(ctx, "с аудио", "voice", null, audio.absolutePath)
        val key = DiarizationEvidenceCache.keyFor(ctx,audio,ts)
        val ticket = DiarizationEvidenceCache.ticket(ts)
        val evidence = SpeakerDiarizer.EvidenceSnapshot(listOf(floatArrayOf(1f,0f)),
            listOf(SpeakerActivityFusion.Window(0,1000)),listOf(SpeakerDiarizer.EmbeddingRegion(0,1000,0,0)))
        assertTrue(DiarizationEvidenceCache.put(key,ticket,evidence))
        val checkpoint=DiarizationCheckpointCodec.Checkpoint(key,1,1,evidence)
        assertFalse(DiarizationCheckpointStore.save(ctx,checkpoint,DiarizationEvidenceCache.ticket(ts+1)))
        RecordStorage.deleteByTimestamp(ctx, ts)
        assertFalse(audio.exists())
        assertNull(DiarizationEvidenceCache.get(key,ticket))
        assertFalse(DiarizationEvidenceCache.put(key,ticket,evidence))
        assertFalse(DiarizationCheckpointStore.save(ctx,checkpoint,ticket))
    }

    @Test fun `getAllRecords groups by day, newest day first`() {
        RecordStorage.addToToday(ctx, "a", "voice", null)
        RecordStorage.addToToday(ctx, "b", "voice", null)
        val days = RecordStorage.getAllRecords(ctx)
        assertEquals(1, days.size)
        assertEquals(listOf("a", "b"), days[0].entries.map { it.text })
    }

    @Test fun `legacy JSON is migrated once and removed`() {
        RecordDatabase.get(ctx).close()
        RecordDatabase.setForTests(RecordDatabase.build(ctx, null))
        val json = File(ctx.filesDir, "guzlikvoice_records.json")
        json.writeText("""
            {"2026-09-01":{"date":"2026-09-01","daySummary":"итог","entries":[
              {"timestamp":1756700000000,"text":"старая запись","source":"voice","audioPath":"/x.m4a",
               "keyThoughts":["мысль"],"tags":["#тег"]},
              {"timestamp":1756700000001,"text":"вторая","source":"text"}
            ]}}
        """.trimIndent())
        // Сбрасываем флаг миграции, как при первом запуске новой версии.
        RecordStorage::class.java.getDeclaredField("migrated").apply { isAccessible = true }.setBoolean(RecordStorage, false)

        val days = RecordStorage.getAllRecords(ctx)
        assertFalse("JSON must be deleted after migration", json.exists())
        assertEquals("2026-09-01", days.single().date)
        assertEquals("итог", days.single().daySummary)
        val first = days.single().entries.first()
        assertEquals("старая запись", first.text)
        assertEquals(listOf("мысль"), first.keyThoughts)
        val second = days.single().entries[1]
        assertEquals(emptyList<String>(), second.tags)
        assertNotEquals(null, second.audioPath)
    }
}
