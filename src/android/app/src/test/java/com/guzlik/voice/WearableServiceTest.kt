package com.guzlik.voice

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.guzlik.voice.db.RecordDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearableServiceTest {
    class FakeRecognizer : WearableRecognizer {
        val users = AtomicInteger(1)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var fail = false
        override val modelName = "lifecycle-test"
        override fun retain(): Boolean { users.incrementAndGet(); return true }
        override fun release() { check(users.decrementAndGet() >= 0) }
        override fun transcribe(file: File, cancelled: () -> Boolean): List<WhisperHelper.Segment> {
            entered.countDown()
            while (!proceed.await(10, TimeUnit.MILLISECONDS)) if (cancelled()) throw java.util.concurrent.CancellationException()
            if (cancelled()) throw java.util.concurrent.CancellationException()
            if (fail) error("model failure")
            assertTrue(file.length() > 44)
            return listOf(WhisperHelper.Segment("Нужно отправить отчёт завтра.", 0, 20))
        }
    }
    class TestService : WearableService() {
        val recognizer = FakeRecognizer()
        lateinit var deliver: (GuzlikVoiceWire.Packet) -> Unit
        var id = 0L
        var closed = false
        override fun acquireRecognizer() = recognizer
        override fun createLink(address: String, onConnected: () -> Unit, onDisconnected: (String) -> Unit,
                                onPacket: (GuzlikVoiceWire.Packet) -> Unit): WearableLink {
            deliver = onPacket
            return object : WearableLink {
                override fun connect() { onConnected(); deliver(GuzlikVoiceWire.Packet(0, GuzlikVoiceWire.HELLO,
                    byteArrayOf(2, 0x3E, 0x80.toByte(), 16, 1, 1, 0x40, 0, 0, 0, 3, 0, 0, 0, 0, 0))) }
                override fun send(sequence: Int, type: Int, payload: ByteArray) {
                    id = GuzlikVoiceWire.u32(payload, 1)
                    val ack = ByteArray(12); ack[2] = payload[0]
                    GuzlikVoiceWire.put32(ack, 4, id)
                    if (payload[0] == GuzlikVoiceWire.STOP.toByte()) GuzlikVoiceWire.put32(ack, 8, 320)
                    deliver(GuzlikVoiceWire.Packet(sequence, GuzlikVoiceWire.ACK, ack))
                }
                override fun close() { closed = true }
            }
        }
        fun audio() {
            val data = ByteArray(648); GuzlikVoiceWire.put32(data, 0, id)
            deliver(GuzlikVoiceWire.Packet(4, GuzlikVoiceWire.AUDIO, data))
        }
    }
    private lateinit var ctx: Context
    private lateinit var controller: ServiceController<TestService>
    private lateinit var service: TestService
    private var destroyed = false
    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        RecordDatabase.setForTests(RecordDatabase.build(ctx, null))
        ctx.getSharedPreferences("wearable", Context.MODE_PRIVATE).edit().clear().commit()
        controller = Robolectric.buildService(TestService::class.java).create()
        service = controller.get()
        action(WearableService.CONNECT)
        await { WearableService.snapshot.connected }
    }
    private fun action(action: String) { service.onStartCommand(Intent(ctx, TestService::class.java)
        .setAction(action).putExtra("address", "00:11:22:33:44:55"), 0, 1) }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            check(System.nanoTime() < until) { "Timed out: ${WearableService.snapshot}" }
            ShadowSystemClock.advanceBy(Duration.ofMillis(100)); ShadowLooper.idleMainLooper(); Thread.sleep(20)
        }
    }
    private fun capture(): Long {
        action(WearableService.START); await { WearableService.snapshot.recording && service.id != 0L }
        service.audio(); await { WearableService.snapshot.samples == 320L }
        return RecordStorage.getAllRecords(ctx).flatMap { it.entries }.single().timestamp
    }
    @After fun cleanUp() {
        service.recognizer.proceed.countDown()
        if (!destroyed) controller.destroy()
        await { service.recognizer.users.get() == 0 }
        UserDataJobs.exclusiveWipe { }
        RecordStorage.getAllRecords(ctx).forEach { day -> day.entries.forEach { RecordStorage.deleteByTimestamp(ctx, it.timestamp) } }
        ctx.filesDir.listFiles { f -> f.name.startsWith("wearable_") }?.forEach { it.delete() }
        RecordDatabase.get(ctx).close(); RecordDatabase.setForTests(null)
    }
    @Test fun `record stop transcribe writes timestamped note and keeps edits`() {
        val id = capture(); action(WearableService.STOP)
        assertTrue(service.recognizer.entered.await(5, TimeUnit.SECONDS))
        RecordStorage.updateEntryText(ctx, id, "Мои правки")
        service.recognizer.proceed.countDown(); await { service.recognizer.users.get() == 1 && !WearableService.snapshot.recording }
        assertEquals("Мои правки", RecordStorage.findEntryByTimestamp(ctx, id)!!.second.text)
    }
    @Test fun `normal completion writes text segments and local analysis`() {
        val id = capture(); service.recognizer.proceed.countDown(); action(WearableService.STOP)
        await { RecordStorage.findEntryByTimestamp(ctx, id)?.second?.segments?.isNotEmpty() == true }
        val entry = RecordStorage.findEntryByTimestamp(ctx, id)!!.second
        assertTrue(entry.text.contains("отчёт")); assertTrue(entry.keyThoughts.isNotEmpty())
        assertEquals(684L, File(entry.audioPath).length())
    }
    @Test fun `destroy during ASR cancels without freed model or resurrected result`() {
        val id = capture(); action(WearableService.STOP)
        assertTrue(service.recognizer.entered.await(5, TimeUnit.SECONDS))
        controller.destroy(); destroyed = true
        await { service.recognizer.users.get() == 0 }
        assertFalse(RecordStorage.findEntryByTimestamp(ctx, id)!!.second.text.contains("отчёт"))
        assertTrue(service.closed)
    }
    @Test fun `delete while ASR runs does not recreate record or metrics`() {
        val id = capture(); action(WearableService.STOP)
        assertTrue(service.recognizer.entered.await(5, TimeUnit.SECONDS))
        RecordStorage.deleteByTimestamp(ctx, id); service.recognizer.proceed.countDown()
        await { service.recognizer.users.get() == 1 && !WearableService.snapshot.recording }
        assertNull(RecordStorage.findEntryByTimestamp(ctx, id))
        assertFalse(File(ctx.filesDir, "wearable_$id.metrics.json").exists())
    }
    @Test fun `wipe while capturing joins writer before removing all user data`() {
        capture()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val wipe = pool.submit<DataWipe.Result> { DataWipe.wipeAll(ctx) }
            await { wipe.isDone }; assertEquals(1, wipe.get().records)
            assertTrue(RecordStorage.getAllRecords(ctx).isEmpty())
            assertTrue(ctx.filesDir.listFiles { f -> f.name.startsWith("wearable_") }!!.isEmpty())
        } finally { pool.shutdownNow() }
    }
    @Test fun `recognizer failure preserves playable audio and releases job use`() {
        val id = capture(); service.recognizer.fail = true; service.recognizer.proceed.countDown(); action(WearableService.STOP)
        await { RecordStorage.findEntryByTimestamp(ctx, id)?.second?.text?.contains("model failure") == true }
        assertEquals(684L, File(RecordStorage.findEntryByTimestamp(ctx, id)!!.second.audioPath).length())
        await { service.recognizer.users.get() == 1 }
    }
    @Test fun `active growing WAV cannot be encrypted as an incomplete snapshot`() {
        val id = capture()
        try {
            RecordStorage.encryptEntry(ctx, RecordStorage.findEntryByTimestamp(ctx, id)!!.first, id)
            fail("Must stop capture first")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("остановите")) }
    }
    @Test fun `system processing timeout cancels ASR without deleting audio`() {
        val id = capture(); action(WearableService.STOP)
        assertTrue(service.recognizer.entered.await(5, TimeUnit.SECONDS))
        action(WearableService.CANCEL_PROCESSING)
        await { service.recognizer.users.get() == 0 }
        assertTrue(File(RecordStorage.findEntryByTimestamp(ctx, id)!!.second.audioPath).exists())
        assertTrue(service.closed)
    }
}
