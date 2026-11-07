package com.example.voicemind

import android.Manifest
import android.app.Application
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class WearableActivityTest {
    private fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    @Test fun `permission denial explanation survives periodic rendering`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildActivity(WearableActivity::class.java).setup()
        try {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(texts(controller.get().window.decorView).any { it.contains("Разрешите доступ") })
        } finally { controller.pause().stop().destroy() }
    }
}
