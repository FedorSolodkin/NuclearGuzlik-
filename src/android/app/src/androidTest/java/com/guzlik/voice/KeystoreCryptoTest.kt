package com.guzlik.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator

/** Шифрование на реальном Android Keystore (Robolectric его не умеет). */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun chunkedAeadRoundTripAcrossChunks() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        for (size in listOf(0, 1, ChunkedAead.CHUNK - 1, ChunkedAead.CHUNK, ChunkedAead.CHUNK * 3 + 17)) {
            val data = ByteArray(size) { (it * 31 % 251).toByte() }
            val enc = ByteArrayOutputStream().also { ChunkedAead.encrypt(key, ByteArrayInputStream(data), it) }
            val dec = ByteArrayOutputStream().also { ChunkedAead.decrypt(key, ByteArrayInputStream(enc.toByteArray()), it) }
            assertArrayEquals("size=$size", data, dec.toByteArray())
        }
    }

    @Test fun truncatedStreamIsRejected() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val data = ByteArray(ChunkedAead.CHUNK * 2 + 5) { it.toByte() }
        val enc = ByteArrayOutputStream().also { ChunkedAead.encrypt(key, ByteArrayInputStream(data), it) }.toByteArray()
        // Отрезаем последний блок: предпоследний помечен «не последним» в AAD → ошибка.
        val cut = enc.copyOf(4 + 12 + ChunkedAead.CHUNK + 16 + 4 + 12 + ChunkedAead.CHUNK + 16)
        try {
            ChunkedAead.decrypt(key, ByteArrayInputStream(cut), ByteArrayOutputStream())
            fail("truncation must be detected")
        } catch (_: Exception) { }
    }

    @Test fun securePrefsRoundTripAndNoPlaintextOnDisk() {
        val p = SecurePrefs.get(ctx, "test_secure_prefs")
        p.clear()
        p.edit("token" to "секретный-токен-123", "n" to 42L, "flag" to true)
        assertEquals("секретный-токен-123", p.getString("token"))
        assertEquals(42L, p.getLong("n"))
        assertEquals(true, p.getBoolean("flag"))
        val raw = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/test_secure_prefs_v2.xml").readText()
        assertFalse(raw.contains("секретный-токен-123"))
        p.remove("token")
        assertNull(p.getString("token"))
        p.clear()
    }
}
