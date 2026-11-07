package com.example.voicemind

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MicModeTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("voicemind_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `new install defaults to auto`() {
        assertTrue(AppPrefs.isMicAuto(ctx))
        assertEquals(AppPrefs.VAD_THRESHOLD_AUTO, AppPrefs.getVadThreshold(ctx))
    }

    @Test fun `user who kept Normal moves to auto`() {
        AppPrefs.setMicSensitivity(ctx, AppPrefs.MIC_MODE_NORMAL)
        assertTrue(AppPrefs.isMicAuto(ctx))
    }

    @Test fun `user who chose Quiet or Noisy keeps manual mode`() {
        AppPrefs.setMicSensitivity(ctx, AppPrefs.MIC_MODE_QUIET)
        assertFalse(AppPrefs.isMicAuto(ctx))
        assertEquals(0.35f, AppPrefs.getVadThreshold(ctx), 0.02f)
        AppPrefs.setMicSensitivity(ctx, AppPrefs.MIC_MODE_NOISY)
        assertFalse(AppPrefs.isMicAuto(ctx))
    }

    @Test fun `explicit choice wins over legacy value`() {
        AppPrefs.setMicSensitivity(ctx, AppPrefs.MIC_MODE_NOISY)
        AppPrefs.setMicAuto(ctx, true)
        assertTrue(AppPrefs.isMicAuto(ctx))
        AppPrefs.setMicAuto(ctx, false)
        AppPrefs.setMicSensitivity(ctx, AppPrefs.MIC_MODE_NORMAL)
        assertFalse(AppPrefs.isMicAuto(ctx))
        assertEquals(0.5f, AppPrefs.getVadThreshold(ctx), 0.001f)
    }
}
