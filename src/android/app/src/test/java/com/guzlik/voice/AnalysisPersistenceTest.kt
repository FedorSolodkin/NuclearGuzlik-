package com.guzlik.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guzlik.voice.db.RecordDatabase
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalysisPersistenceTest {
    private lateinit var ctx:Context
    private var ts=0L
    private lateinit var date:String
    @Before fun prepare() {
        ctx=ApplicationProvider.getApplicationContext()
        RecordDatabase.setForTests(RecordDatabase.build(ctx,null))
        ts=RecordStorage.addToToday(ctx,"Исходный текст","text",null,linkPendingEvent=false)
        date=RecordStorage.findEntryByTimestamp(ctx,ts)!!.first
    }
    @After fun close(){RecordDatabase.get(ctx).close();RecordDatabase.setForTests(null)}
    @Test fun changedSourceCannotReplaceAnExistingDayNote() {
        RecordStorage.saveDaySummary(ctx,date,"Сохранённый итог")
        RecordStorage.updateEntryText(ctx,ts,"Ручная правка")
        assertFalse(RecordStorage.saveDaySummaryIfUnchanged(ctx,date,mapOf(ts to "Исходный текст"),"Устаревший итог"))
        assertEquals("Сохранённый итог",RecordStorage.getAllRecords(ctx).first{it.date==date}.daySummary)
    }
    @Test fun deletedSourceCannotReappearAsANewDayNote() {
        RecordStorage.deleteByTimestamp(ctx,ts)
        assertFalse(RecordStorage.saveDaySummaryIfUnchanged(ctx,date,mapOf(ts to "Исходный текст"),"Устаревший итог"))
        assertTrue(RecordStorage.getAllRecords(ctx).none{it.daySummary=="Устаревший итог"})
    }
    @Test fun protectionDuringGenerationRejectsPlaintextResults() {
        val db=RecordDatabase.get(ctx)
        val entry=db.entries().byTimestamp(ts)!!
        db.entries().upsert(entry.copy(isEncrypted=true,text=RecordStorage.ENCRYPTED_PLACEHOLDER))
        RecordStorage.updateEntryTemplate(ctx,ts,"meeting","Открытый протокол")
        assertFalse(RecordStorage.saveTemplateResult(ctx,ts,"Исходный текст","meeting","Открытый протокол"))
        assertFalse(RecordStorage.saveDaySummaryIfUnchanged(ctx,date,mapOf(ts to "Исходный текст"),"Открытый итог"))
        assertNull(RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.templateResult)
    }
    @Test fun unchangedSourcesSaveOnlyToTheirOriginalDay() {
        val expected=mapOf(ts to "Исходный текст")
        assertFalse(RecordStorage.saveDaySummaryIfUnchanged(ctx,"2040-01-01",expected,"Чужой день"))
        assertTrue(RecordStorage.saveDaySummaryIfUnchanged(ctx,date,expected,"Верный итог"))
        assertEquals("Верный итог",RecordStorage.getAllRecords(ctx).first{it.date==date}.daySummary)
    }
    @Test fun speakerRenameCannotWriteThroughProtectionOrADeletedRecord() {
        RecordStorage.updateEntryText(ctx,ts,"[Говорящий А]\nТекст")
        SpeakerCentroidStorage.put(ctx,SpeakerCentroidStorage.Snapshot(ts,listOf("А"),listOf(floatArrayOf(1f,0f)),listOf(null)))
        val d=RecordDatabase.get(ctx).entries()
        d.upsert(d.byTimestamp(ts)!!.copy(isEncrypted=true,text=RecordStorage.ENCRYPTED_PLACEHOLDER))
        assertFalse(RecordStorage.renameSpeaker(ctx,ts,0,"Анна",null))
        assertEquals("А",SpeakerCentroidStorage.get(ctx,ts)!!.labels.single())
        assertEquals(RecordStorage.ENCRYPTED_PLACEHOLDER,d.byTimestamp(ts)!!.text)
        RecordStorage.deleteByTimestamp(ctx,ts)
        assertFalse(RecordStorage.renameSpeaker(ctx,ts,0,"Анна",null))
    }
}
