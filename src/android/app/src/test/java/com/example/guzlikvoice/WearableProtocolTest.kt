package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WearableProtocolTest {
    private class Rig {
        var time = 0L
        val commands = mutableListOf<ByteArray>()
        val pcm = ByteArrayOutputStream()
        val ends = mutableListOf<String?>()
        val session = WearableSession({ _, p -> commands.add(p); commands.size }, { pcm.write(it) }, { ends.add(it) }, { time })
        fun hello(boot: Long = 3) {
            val p = byteArrayOf(2, 0x3E, 0x80.toByte(), 16, 1, 1, 0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            GuzlikVoiceWire.put32(p, 7, boot); session.accept(GuzlikVoiceWire.Packet(1, GuzlikVoiceWire.HELLO, p))
        }
        fun ack(op: Int, length: Long = 0, id: Long = 42) {
            val p = ByteArray(12); p[2] = op.toByte(); GuzlikVoiceWire.put32(p, 4, id); GuzlikVoiceWire.put32(p, 8, length)
            session.accept(GuzlikVoiceWire.Packet(2, GuzlikVoiceWire.ACK, p))
        }
        fun audio(offset: Long, count: Int = 320, id: Long = 42) {
            val p = ByteArray(8 + count * 2) { 11 }; GuzlikVoiceWire.put32(p, 0, id); GuzlikVoiceWire.put32(p, 4, offset)
            session.accept(GuzlikVoiceWire.Packet(3, GuzlikVoiceWire.AUDIO, p))
        }
        fun start() { hello(); session.start(42); ack(GuzlikVoiceWire.START) }
    }
    @Test fun `arbitrary stream chunk boundaries and sequence wrap preserve audio`() {
        val packets = mutableListOf<GuzlikVoiceWire.Packet>()
        val parser = GuzlikVoicePacketParser(onPacket = { packets.add(it) })
        val stream = GuzlikVoiceWire.encode(65535, 1, byteArrayOf(0, 0xAA.toByte(), -1)) + GuzlikVoiceWire.encode(0, 6)
        stream.forEach { parser.feed(byteArrayOf(it)) }
        assertEquals(listOf(65535, 0), packets.map { it.sequence }); assertEquals(0L, parser.sequenceGaps)
    }
    @Test fun `garbage corrupt CRC and illegal length recover subsequent packets`() {
        val packets = mutableListOf<GuzlikVoiceWire.Packet>()
        val parser = GuzlikVoicePacketParser(onPacket = { packets.add(it) })
        val bad = GuzlikVoiceWire.encode(2, 1, byteArrayOf(1, 2)).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        parser.feed(byteArrayOf(77, 0xAA.toByte(), 0, 0, 0, 0x7F, 0x7F) + bad + GuzlikVoiceWire.encode(4, 6))
        assertEquals(listOf(4), packets.map { it.sequence }); assertTrue(parser.crcErrors > 0); assertTrue(parser.invalidLengths > 0)
    }
    @Test fun `old replay does not move sequence watermark backwards`() {
        val parser = GuzlikVoicePacketParser()
        for (seq in listOf(10, 11, 10, 11, 12, 14)) parser.feed(GuzlikVoiceWire.encode(seq, 6))
        assertEquals(1L, parser.sequenceGaps)
    }
    @Test fun `damaged length rescans embedded valid frame`() {
        val packets = mutableListOf<GuzlikVoiceWire.Packet>()
        val parser = GuzlikVoicePacketParser(onPacket = { packets.add(it) })
        val frame = GuzlikVoiceWire.encode(7, 6)
        parser.feed(byteArrayOf(0xAA.toByte(), 0, 0, 1, 0, 7) + frame + byteArrayOf(0))
        assertEquals(listOf(7), packets.map { it.sequence })
    }
    @Test fun `missing audio is silence while duplicate and overlapping replay are not repeated`() {
        val r = Rig(); r.start(); r.audio(0); r.audio(0); r.audio(640); r.audio(800)
        r.session.stop(); r.ack(GuzlikVoiceWire.STOP, 1440)
        assertEquals(1440L, r.session.samples); assertEquals(640L, r.session.missingSamples)
        assertEquals(800L, r.session.receivedSamples); assertEquals(2880, r.pcm.size())
        assertTrue(r.pcm.toByteArray().sliceArray(640 until 1280).all { it == 0.toByte() })
        assertEquals(listOf<String?>(null), r.ends)
    }
    @Test fun `stop while disconnected remains stop after reconnect`() {
        val r = Rig(); r.start(); r.audio(0); r.session.disconnected(); r.session.stop(); r.hello()
        assertEquals(GuzlikVoiceWire.STOP, r.commands.last()[0].toInt()); r.audio(320)
        r.ack(GuzlikVoiceWire.STOP, 640); assertEquals(640L, r.session.samples); assertNull(r.session.sessionId)
    }
    @Test fun `short outage resumes same session and pads lost audio`() {
        val r = Rig(); r.start(); r.audio(0); r.session.disconnected(); r.time = 2000; r.hello()
        assertEquals(42L, GuzlikVoiceWire.u32(r.commands.last(), 1)); r.ack(GuzlikVoiceWire.START)
        r.audio(960); assertEquals(640L, r.session.missingSamples); assertEquals(1280L, r.session.samples)
    }
    @Test fun `device reboot cannot silently join two recordings`() {
        val r = Rig(); r.start(); r.audio(0); r.session.disconnected(); r.hello(4)
        assertNull(r.session.sessionId); assertTrue(r.ends.single()!!.contains("перезапустилось")); assertEquals(320L, r.session.samples)
    }
    @Test fun `permanent outage finalizes instead of hanging`() {
        val r = Rig(); r.start(); r.session.disconnected(); r.time = 15_000; r.session.tick()
        assertNull(r.session.sessionId); assertTrue(r.ends.single()!!.contains("15 секунд"))
    }
    @Test fun `command retries are bounded and late foreign acknowledgements ignored`() {
        val r = Rig(); r.hello(); r.session.start(42); r.ack(GuzlikVoiceWire.START, id = 77)
        assertEquals(WearableSession.State.STARTING, r.session.state)
        repeat(3) { r.time += 1500; r.session.tick() }
        assertEquals(3, r.commands.size); assertNull(r.session.sessionId); assertEquals(1, r.ends.size)
    }
    @Test(expected = IllegalStateException::class) fun `unsupported format is rejected`() {
        val r = Rig(); r.session.accept(GuzlikVoiceWire.Packet(0, GuzlikVoiceWire.HELLO, ByteArray(11)))
    }
    @Test(expected = IllegalStateException::class) fun `huge sample offset cannot allocate unlimited silence`() {
        val r = Rig(); r.start(); r.audio(0xFFFF_FFFFL)
    }
    @Test fun `audio from a different session cannot enter recording`() {
        val r = Rig(); r.start(); r.audio(0, id = 999); assertEquals(0, r.pcm.size())
    }
    @Test fun `actual Cpp firmware stream interoperates through reconnect and stop`() {
        val r = Rig()
        var greetings = 0
        val parser = GuzlikVoicePacketParser(onPacket = { packet ->
            if (packet.type == GuzlikVoiceWire.HELLO && greetings++ > 0) r.session.disconnected()
            if (packet.type == GuzlikVoiceWire.ACK && packet.payload[2] == GuzlikVoiceWire.STOP.toByte()) r.session.stop()
            r.session.accept(packet)
            if (packet.type == GuzlikVoiceWire.HELLO && r.session.sessionId == null) r.session.start(42)
        })
        val stream = javaClass.getResourceAsStream("/wearable/reconnect.bin")!!.use { it.readBytes() }
        stream.asList().chunked(17).forEach { parser.feed(it.toByteArray()) }
        assertEquals(73600L, r.session.samples)
        assertEquals(16000L, r.session.missingSamples)
        assertEquals(57600L, r.session.receivedSamples)
        assertEquals(147200, r.pcm.size())
        assertEquals(1, r.ends.size); assertNull(r.ends.single())
        assertEquals(0L, parser.crcErrors)
    }
    @Test fun `unexpected microphone stop is finalized with warning`() {
        val r = Rig(); r.start(); r.audio(0)
        val p = ByteArray(14); p[1] = 255.toByte(); GuzlikVoiceWire.put32(p, 2, 42); GuzlikVoiceWire.put32(p, 6, 640)
        r.session.accept(GuzlikVoiceWire.Packet(8, GuzlikVoiceWire.STATUS, p))
        assertNull(r.session.sessionId); assertEquals(640L, r.session.samples); assertEquals(320L, r.session.missingSamples)
        assertTrue(r.ends.single()!!.contains("остановило")); assertNull(r.session.battery)
    }
    @Test fun `orphan capture from a dead phone process is stopped before next start`() {
        val r = Rig()
        val p = byteArrayOf(2, 0x3E, 0x80.toByte(), 16, 1, 1, 0x40, 0, 0, 0, 3, 1, 0, 0, 0, 42)
        r.session.accept(GuzlikVoiceWire.Packet(1, GuzlikVoiceWire.HELLO, p))
        assertFalse(r.session.ready)
        assertEquals(GuzlikVoiceWire.STOP, r.commands.single()[0].toInt())
        r.audio(0) // Old audio has no new local writer and must be discarded.
        assertEquals(0, r.pcm.size())
        r.ack(GuzlikVoiceWire.STOP, 320)
        assertTrue(r.session.ready); assertTrue(r.ends.isEmpty())
        r.session.start(77)
        assertEquals(77L, GuzlikVoiceWire.u32(r.commands.last(), 1))
    }
    @Test fun `WAV header recovers data beyond last checkpoint and removes half sample`() {
        val file = File.createTempFile("wearable-test", ".wav")
        try {
            WearableWavWriter(file).use { it.write(ByteArray(640)) }
            file.appendBytes(ByteArray(321))
            WearableWavWriter.recover(file)
            val bytes = file.readBytes(); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1004L, file.length()); assertEquals(960, b.getInt(40)); assertEquals(16000, b.getInt(24))
            assertEquals(1, b.getShort(22).toInt()); assertEquals(16, b.getShort(34).toInt())
        } finally { file.delete() }
    }
}
