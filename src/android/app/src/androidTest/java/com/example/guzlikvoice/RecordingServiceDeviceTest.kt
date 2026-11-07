package com.example.guzlikvoice

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingServiceDeviceTest {
    @Test fun jobsCompletedBeforeStartDeliveryDoNotCrashOrLeakService() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("serviceAudit")=="true")
        val ctx=instrumentation.targetContext
        assertTrue(ctx.packageName.endsWith(".dev"))
        // Commands cannot be delivered on the main thread until this runnable
        // returns, so all three start tokens are already finished on delivery.
        instrumentation.runOnMainSync {
            repeat(3){
                val token=RecordingService.processingStarted(ctx)
                RecordingService.processingFinished(ctx,token)
            }
        }
        // An unacknowledged foreground start fails asynchronously after the
        // command has returned; keep the process alive through that deadline.
        Thread.sleep(12_000)
        instrumentation.waitForIdleSync()
        assertEquals(0,RecordingService.processingActive.get())
        assertEquals(0,RecordingService.recordingActive.get())
        val manager=ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running=manager.getRunningServices(Int.MAX_VALUE)
        assertFalse("Finished jobs left a service running",running.any{it.service.className==RecordingService::class.java.name})
    }
}
