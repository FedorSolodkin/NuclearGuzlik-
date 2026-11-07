package com.guzlik.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Radio-independent phone acceptance. RF/range/battery still require an assembled device. */
@RunWith(AndroidJUnit4::class)
class WearableAcceptanceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun prototypeHasNoInternetPermissionOrAccountGate() {
        assumeTrue("Use the prototype build", BuildConfig.OFFLINE_PROTOTYPE)
        assertFalse(BuildConfig.AUTH_REQUIRED)
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName))
    }
    @Test fun realFirmwareReplayDecodesWavWithCorrectTimeline() {
        val audio = File(context.cacheDir, "wearable-acceptance.wav")
        val writer = WearableWavWriter(audio)
        var ended = 0
        var greetings = 0
        val session = WearableSession({ _, _ -> 0 }, { writer.write(it) }, { assertNull(it); ended++ }, { 0L })
        val parser = GuzlikVoicePacketParser(onPacket = { packet ->
            if (packet.type == GuzlikVoiceWire.HELLO && greetings++ > 0) session.disconnected()
            if (packet.type == GuzlikVoiceWire.ACK && packet.payload[2] == GuzlikVoiceWire.STOP.toByte()) session.stop()
            session.accept(packet)
            if (packet.type == GuzlikVoiceWire.HELLO && session.sessionId == null) session.start(42)
        })
        try {
            instrumentation.context.assets.open("reconnect.bin").use { input ->
                val bytes = ByteArray(17)
                while (true) { val count = input.read(bytes); if (count < 0) break; parser.feed(bytes, count) }
            }
            writer.close()
            assertEquals(1, ended)
            assertEquals(73600L, session.samples)
            assertEquals(16000L, session.missingSamples)
            assertEquals(73600L, AudioDecoder.decodeStreaming(audio) { })
        } finally { runCatching { writer.close() }; audio.delete() }
    }
}
