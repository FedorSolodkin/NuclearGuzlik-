package com.example.guzlikvoice

import android.content.Intent
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingServiceTest {
    @Test fun completedJobStillAcknowledgesItsDelayedForegroundStart() {
        val controller=Robolectric.buildService(RecordingService::class.java).create()
        val service=controller.get()
        val token=RecordingService.processingStarted(service)
        RecordingService.processingFinished(service,token)
        try {
            service.onStartCommand(Intent(service,RecordingService::class.java)
                .setAction(RecordingService.ACTION_PROCESSING_START).putExtra("processing_token",token),0,17)
            val shadow=shadowOf(service)
            assertTrue("Android requires foreground promotion even when the job already ended",shadow.lastForegroundNotificationId>0)
            assertNull("Finished jobs must remove the notification",shadow.lastForegroundNotification)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,service.foregroundServiceType)
            assertTrue(shadow.isForegroundStopped)
            assertEquals(17,shadow.stopSelfId)
            assertEquals(0,RecordingService.processingActive.get())
        }finally{controller.destroy()}
    }

    @Test fun expiredStartCannotLeaveANewerJobWithoutForegroundProtection() {
        val controller=Robolectric.buildService(RecordingService::class.java).create()
        val service=controller.get()
        val old=RecordingService.processingStarted(service)
        RecordingService.processingFinished(service,old)
        val current=RecordingService.processingStarted(service)
        try {
            service.onStartCommand(Intent(service,RecordingService::class.java)
                .setAction(RecordingService.ACTION_PROCESSING_START).putExtra("processing_token",old),0,19)
            val shadow=shadowOf(service)
            assertNotNull(shadow.lastForegroundNotification)
            assertFalse(shadow.isStoppedBySelf)
            assertFalse(shadow.isForegroundStopped)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,service.foregroundServiceType)
            assertEquals(1,RecordingService.processingActive.get())
        }finally{RecordingService.processingFinished(service,current);controller.destroy()}
    }
}
