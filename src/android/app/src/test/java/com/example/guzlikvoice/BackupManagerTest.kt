package com.example.guzlikvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.guzlikvoice.db.RecordDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        RecordDatabase.setForTests(RecordDatabase.build(ctx, null))
    }

    @After fun tearDown() {
        RecordDatabase.get(ctx).close()
        RecordDatabase.setForTests(null)
    }

    private fun freshDb() {
        RecordDatabase.get(ctx).close()
        RecordDatabase.setForTests(RecordDatabase.build(ctx, null))
    }

    @Test fun `export then import restores entries, audio and clients`() {
        val audio = File(ctx.filesDir, "rec_1.m4a").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val ts1 = RecordStorage.addToToday(ctx, "Первая запись", "voice", null, audio.absolutePath)
        RecordStorage.updateEntrySegments(ctx, ts1, listOf(WhisperHelper.Segment("Первая запись", 0, 1200)))
        val ts2 = RecordStorage.addToToday(ctx, "Вторая", "text", null)
        val client = ClientStorage.upsert(ctx, Client(name = "Иван"))
        RecordStorage.updateEntryClient(ctx, RecordStorage.findEntryByTimestamp(ctx, ts2)!!.first, ts2, client.id)

        val bytes = ByteArrayOutputStream()
        val exp = BackupManager.export(ctx, bytes, "пароль123".toCharArray(), includeProtected = false)
        assertEquals(2, exp.entries)
        assertEquals(1, exp.audioFiles)

        // «Новый телефон»: пустая база, нет аудио и клиентов.
        freshDb()
        audio.delete()
        ClientStorage.delete(ctx, client.id)

        val imp = BackupManager.import(ctx, ByteArrayInputStream(bytes.toByteArray()), "пароль123".toCharArray())
        assertEquals(2, imp.added)
        assertEquals(1, imp.audioFiles)
        assertEquals(1, imp.clients)

        val (_, e1) = RecordStorage.findEntryByTimestamp(ctx, ts1)!!
        assertEquals("Первая запись", e1.text)
        assertEquals(1, e1.segments!!.size)
        val restored = File(e1.audioPath)
        assertTrue(restored.exists())
        assertEquals(300_000L, restored.length())
        assertEquals(client.id, RecordStorage.findEntryByTimestamp(ctx, ts2)!!.second.clientId)

        // Повторное восстановление ничего не дублирует.
        val again = BackupManager.import(ctx, ByteArrayInputStream(bytes.toByteArray()), "пароль123".toCharArray())
        assertEquals(0, again.added)
        assertEquals(2, again.skippedExisting)
    }

    @Test fun `wrong password is reported as such`() {
        RecordStorage.addToToday(ctx, "секрет", "voice", null)
        val bytes = ByteArrayOutputStream()
        BackupManager.export(ctx, bytes, "верный-пароль".toCharArray(), includeProtected = false)
        try {
            BackupManager.import(ctx, ByteArrayInputStream(bytes.toByteArray()), "неверный".toCharArray())
            fail("must throw")
        } catch (e: BackupManager.WrongPasswordException) {
            // ok
        }
    }

    @Test fun `backup file does not contain plaintext`() {
        RecordStorage.addToToday(ctx, "очень секретный текст встречи", "voice", null)
        val bytes = ByteArrayOutputStream()
        BackupManager.export(ctx, bytes, "pass123".toCharArray(), includeProtected = false)
        val raw = String(bytes.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(!raw.contains("records.json") && !raw.contains("секрет".toByteArray().toString(Charsets.ISO_8859_1)))
    }

    @Test fun `not a backup file is rejected`() {
        try {
            BackupManager.import(ctx, ByteArrayInputStream("hello world, not a backup".toByteArray()), "x".toCharArray())
            fail("must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("не файл"))
        }
    }
}
