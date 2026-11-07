package com.guzlik.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guzlik.voice.db.RecordDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class WearableStorageTest {
    private lateinit var ctx: Context
    private val files = mutableListOf<File>()
    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        RecordDatabase.setForTests(RecordDatabase.build(ctx, null))
        ctx.getSharedPreferences("wearable", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() {
        files.forEach { it.delete() }
        ctx.filesDir.listFiles { f -> f.name.startsWith("wearable_") }?.forEach { it.delete() }
        RecordDatabase.get(ctx).close(); RecordDatabase.setForTests(null)
    }
    @Test fun `recovery fixes partial WAV and preserves existing note edits`() {
        val file = File(ctx.filesDir, "wearable_recovery.wav").also { files.add(it) }
        WearableWavWriter(file).use { it.write(ByteArray(640)) }; file.appendBytes(ByteArray(321))
        val id = RecordStorage.addToToday(ctx, "Моя исправленная заметка", "voice", null, file.absolutePath)
        ctx.getSharedPreferences("wearable", Context.MODE_PRIVATE).edit().putString("pending_path", file.absolutePath).putLong("pending_entry", id).commit()
        WearableRecovery.recover(ctx, force = true)
        assertEquals(960, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertEquals("Моя исправленная заметка", RecordStorage.findEntryByTimestamp(ctx, id)!!.second.text)
        assertFalse(ctx.getSharedPreferences("wearable", Context.MODE_PRIVATE).contains("pending_path"))
    }
    @Test fun `transcript and metrics cannot restore deleted or protected entries`() {
        val id = RecordStorage.addToToday(ctx, "pending", "voice", null)
        val dao = RecordDatabase.get(ctx).entries()
        dao.upsert(dao.byTimestamp(id)!!.copy(isEncrypted = true))
        assertFalse(RecordStorage.saveWearableTranscript(ctx, id, "pending", "private", emptyList()))
        assertFalse(RecordStorage.saveWearableMetrics(ctx, id, "{}"))
        dao.upsert(dao.byTimestamp(id)!!.copy(isEncrypted = false))
        assertTrue(RecordStorage.saveWearableMetrics(ctx, id, "{}"))
        RecordStorage.deleteByTimestamp(ctx, id)
        assertFalse(File(ctx.filesDir, "wearable_$id.metrics.json").exists())
        assertFalse(RecordStorage.saveWearableMetrics(ctx, id, "{}"))
        assertFalse(RecordStorage.saveWearableTranscript(ctx, id, "pending", "private", emptyList()))
    }
}
