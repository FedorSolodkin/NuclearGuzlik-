package com.guzlik.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.guzlik.voice.db.RecordDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class GenerationFlowTest {
    private fun await(ready:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();if(ready())return;Thread.sleep(10)}
        fail("Generation did not finish")
    }
    @Test fun oldQuestionFailureAndQueuedCallbacksCannotReplaceNewQuestion() {
        val ctx=ApplicationProvider.getApplicationContext<Context>()
        val started=CountDownLatch(1);val oldRelease=CountDownLatch(1);val newRelease=CountDownLatch(1)
        val delivered=mutableListOf<QaJobRunner.State>()
        val listener:(QaJobRunner.State)->Unit={delivered.add(it)}
        QaJobRunner.clear();QaJobRunner.addListener(listener)
        try {
            QaJobRunner.askWith(ctx,"old","Старый вопрос") {
                started.countDown();check(oldRelease.await(10,TimeUnit.SECONDS));throw java.io.IOException("Old request failed")
            }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            QaJobRunner.askWith(ctx,"new","Новый вопрос") {check(newRelease.await(10,TimeUnit.SECONDS));"Новый ответ"}
            oldRelease.countDown()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(QaJobRunner.state is QaJobRunner.State.Running)
            assertEquals("new",(QaJobRunner.state as QaJobRunner.State.Running).recordKey)
            assertTrue(delivered.none{it is QaJobRunner.State.Running && it.recordKey=="old"})
            newRelease.countDown()
            await{QaJobRunner.state is QaJobRunner.State.Done}
            assertEquals("Новый ответ",(QaJobRunner.state as QaJobRunner.State.Done).answer)
        }finally{
            oldRelease.countDown();newRelease.countDown();QaJobRunner.clear()
            QaJobRunner.removeListener(listener);UserDataJobs.exclusiveWipe{}
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun cancelledProtocolCannotOverwriteNewerProtocol()=protocol(false)
    @Test fun editingTranscriptDuringProtocolKeepsEditAndRejectsStaleResult()=protocol(true)
    private fun protocol(edit:Boolean) {
        val ctx=ApplicationProvider.getApplicationContext<Context>()
        RecordDatabase.setForTests(RecordDatabase.build(ctx,null))
        val ts=RecordStorage.addToToday(ctx,"Исходный текст","text",null,linkPendingEvent=false)
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            ProtocolRunner.startWith(ctx,ts,"Исходный текст") {started.countDown();check(release.await(10,TimeUnit.SECONDS));"Старый протокол"}
            assertTrue(started.await(5,TimeUnit.SECONDS))
            if(edit) {
                RecordStorage.updateEntryText(ctx,ts,"Ручная правка")
                release.countDown()
                await{ProtocolRunner.state is ProtocolRunner.State.Failed}
                assertEquals("Ручная правка",RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text)
                assertNull(RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.templateResult)
            }else{
                ProtocolRunner.startWith(ctx,ts,"Исходный текст") {"Новый протокол"}
                await{ProtocolRunner.state is ProtocolRunner.State.Done}
                release.countDown()
                UserDataJobs.exclusiveWipe{}
                assertEquals("Новый протокол",RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.templateResult)
            }
        }finally{
            release.countDown();ProtocolRunner.cancel();UserDataJobs.exclusiveWipe{}
            shadowOf(Looper.getMainLooper()).idle()
            RecordDatabase.get(ctx).close();RecordDatabase.setForTests(null)
        }
    }
}
