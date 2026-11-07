package com.example.guzlikvoice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.guzlikvoice.db.RecordDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HistoryAnalysisFlowTest {
    private fun await(ready:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();if(ready())return;Thread.sleep(10)}
        fail("Analysis did not finish")
    }
    @Test fun changingSelectedDateDoesNotMoveTheAnalysisResult()=verify(false)
    @Test fun wipingDuringAnalysisCannotResurrectItsResult()=verify(true)
    private fun verify(wipe:Boolean) {
        val ctx=ApplicationProvider.getApplicationContext<Context>()
        RecordDatabase.setForTests(RecordDatabase.build(ctx,null))
        val ts=RecordStorage.addToToday(ctx,"Контрольная запись","text",null,linkPendingEvent=false)
        val date=RecordStorage.findEntryByTimestamp(ctx,ts)!!.first
        val controller=Robolectric.buildActivity(SpeakerCorrectionUiTest.Host::class.java).setup()
        val release=CountDownLatch(1);val started=CountDownLatch(1)
        try {
            val fragment=HistoryFragment()
            controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content,fragment).commitNow()
            fragment.runAnalysis({started.countDown();check(release.await(10,TimeUnit.SECONDS));"Контрольный итог"},{release.countDown()})
            assertTrue(started.await(5,TimeUnit.SECONDS))
            if(wipe) {
                var failure:Throwable?=null
                val thread=Thread {
                    try{UserDataJobs.exclusiveWipe{RecordStorage.deleteByTimestamp(ctx,ts)}}catch(e:Throwable){failure=e}
                }.apply{start()}
                await{!thread.isAlive}
                assertNull(failure)
                assertNull(RecordStorage.findEntryByTimestamp(ctx,ts))
                assertTrue(RecordStorage.getAllRecords(ctx).none{it.daySummary=="Контрольный итог"})
            }else{
                HistoryFragment::class.java.getDeclaredField("selectedDateStr").apply{isAccessible=true}.set(fragment,"2040-01-01")
                release.countDown()
                await{RecordStorage.getAllRecords(ctx).any{it.date==date && it.daySummary=="Контрольный итог"}}
                assertTrue(RecordStorage.getAllRecords(ctx).none{it.date=="2040-01-01" && it.daySummary.isNotBlank()})
            }
            await{RecordingService.processingActive.get()==0}
        }finally{
            release.countDown()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            RecordDatabase.get(ctx).close();RecordDatabase.setForTests(null)
        }
    }
}
