package com.guzlik.voice

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.EditText
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Opt-in real UI + engines; owns only its imported clip, exports and database entry. */
@RunWith(AndroidJUnit4::class)
class FullUserFlowDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val ctx=instrumentation.targetContext
    private val args=InstrumentationRegistry.getArguments()
    private fun optIn() {
        assumeTrue(args.getString("fullFlow")=="true")
        assertTrue(ctx.packageName.endsWith(".dev"))
    }
    private fun await(label:String,timeoutMs:Long=60_000,ready:()->Boolean) {
        android.util.Log.i("FullUserFlow","Waiting: $label")
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<deadline){if(ready()){android.util.Log.i("FullUserFlow","Passed: $label");return};Thread.sleep(100)}
        fail("Timed out: $label")
    }
    private fun openFromLauncher() {
        for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard")) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        assertFalse("Unlock the phone before running UI audit",(ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked)
        instrumentation.uiAutomation.executeShellCommand("am start -n ${ctx.packageName}/com.guzlik.voice.OnboardingActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000").use{descriptor->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{it.readBytes()}
        }
        await("Main activity through launcher",20_000){
            var ready=false
            instrumentation.runOnMainSync{
                ready=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().any{
                        it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);true
                    }
            }
            ready
        }
    }
    @Test fun prepareAssistantForFullFlow() {
        assumeTrue(args.getString("downloadAssistant")=="true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val spec=LlmModels.GEMMA
        if(LlmModels.isDownloaded(ctx,spec))return
        val done=CountDownLatch(1);var success=false
        val listener=object:ModelDownloader.Listener {
            override fun onProgress(modelName:String,pct:Int,downloadedMb:Long,totalMb:Long) {}
            override fun onFinished(modelName:String,ok:Boolean){if(modelName==spec.fileName){success=ok;done.countDown()}}
        }
        ModelDownloader.addListener(listener)
        try {
            assertTrue(ModelDownloader.start(ctx,spec.fileName,spec.urls,spec.minSize))
            assertTrue("Assistant download timed out",done.await(30,TimeUnit.MINUTES))
            assertTrue("Assistant download failed",success)
            assertTrue(LlmModels.isDownloaded(ctx,spec))
        }finally{ModelDownloader.removeListener(listener)}
    }
    private fun clip():File {
        val source=File(ctx.getExternalFilesDir(null),"eval/acoustic/interview-2026-11-04/source.s16")
        assertTrue("Authorized PCM fixture missing",source.isFile)
        val pcm=ByteArray(32*16000*2)
        RandomAccessFile(source,"r").use{it.seek(218_000L*32);it.readFully(pcm)}
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return File(ctx.filesDir,"flow-audit-${System.nanoTime()}.wav").apply{
            outputStream().use{it.write(header.array());it.write(pcm)}
        }
    }
    private inner class MainUi : java.io.Closeable {
        private fun main():MainActivity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().first()
        fun onActivity(action:(MainActivity)->Unit) {
            var resumed=false
            instrumentation.runOnMainSync {
                resumed=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any{it is MainActivity}
            }
            if(!resumed)openFromLauncher()
            instrumentation.runOnMainSync { action(main()) }
        }
        fun recreate() {
            var previous:MainActivity?=null
            onActivity { previous=it;it.recreate() }
            await("Activity recreated",30_000) {
                var ready=false
                instrumentation.runOnMainSync {
                    ready=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<MainActivity>().any{
                            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it!==previous
                        }
                }
                ready
            }
        }
        override fun close() {
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().forEach{it.finish()}
            }
        }
    }
    private fun sheet(activity:MainActivity):TranscriptionDetailSheet =
        activity.supportFragmentManager.fragments.filterIsInstance<TranscriptionDetailSheet>().first()
    private fun downloaded(name:String):Uri? = ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID),"${MediaStore.Downloads.DISPLAY_NAME}=?",arrayOf(name),"${MediaStore.Downloads._ID} DESC")?.use{
        if(it.moveToFirst())Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,it.getLong(0).toString())else null
    }
    @Test fun importEditTimeLinksQuestionExportRotationAndDiarization() {
        optIn()
        val input=clip();var ts=0L;val downloads=mutableListOf<Uri>()
        SetupChoices.skipLlm(ctx)
        try {
            openFromLauncher()
            MainUi().use{scenario->
                await("ASR ready",120_000){WhisperHelper.active?.isReady==true}
                AudioImporter.enqueueFile(ctx,input)
                await("Imported transcript",180_000){
                    val entry=RecordStorage.getAllRecords(ctx).flatMap{it.entries}.firstOrNull{it.audioPath==input.absolutePath}
                    if(entry!=null && entry.text.isNotBlank() && !entry.segments.isNullOrEmpty()){ts=entry.timestamp;true}else false
                }
                fun open() {
                    val (date,e)=RecordStorage.findEntryByTimestamp(ctx,ts)!!
                    scenario.onActivity{a->TranscriptionDetailSheet.newInstance(LogEntry(ts,e.text,e.structured,e.keyThoughts,e.tags,e.source,e.audioPath),date)
                        .showNow(a.supportFragmentManager,"flow-detail")}
                }
                open()
                await("Detail window focused") {
                    var focused=false
                    scenario.onActivity { focused=sheet(it).dialog?.window?.decorView?.hasWindowFocus()==true }
                    focused
                }
                val raw=RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text
                val edited=raw+"\nПроверка сохранения ручной правки."
                onView(withId(R.id.btnDetailEdit)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                // Both the detail sheet and editor are dialogs. Select the
                // editor's decor rather than searching the sheet underneath it.
                val editorRoot=androidx.test.espresso.matcher.RootMatchers.withDecorView(
                    hasDescendant(withText("Изменить текст")))
                onView(isAssignableFrom(EditText::class.java)).inRoot(editorRoot).perform(replaceText(edited))
                onView(withText("Сохранить")).inRoot(editorRoot).perform(click())
                await("Edited transcript saved"){RecordStorage.findEntryByTimestamp(ctx,ts)?.second?.text==edited}
                scenario.onActivity{a->
                    val v=sheet(a).requireView()
                    v.findViewById<android.view.View>(R.id.rowActionsToggle).performClick()
                    v.findViewById<android.view.View>(R.id.btnSyncAudio).performClick()
                    assertEquals(edited,v.findViewById<TextView>(R.id.tvDetailText).text.toString())
                    v.findViewById<android.view.View>(R.id.btnAskQuestion).performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity{a->
                    val qa=a.supportFragmentManager.fragments.filterIsInstance<QaSheet>().single()
                    assertEquals(edited,TextHandoff.get(qa.requireArguments().getString("transcript")))
                    qa.dismissNow()
                }
                // Close the source sheet in the same main-thread turn as choosing
                // Word: its asynchronous completion must still save successfully.
                scenario.onActivity{a->
                    val detail=sheet(a)
                    detail.requireView().findViewById<android.view.View>(R.id.btnExportFormats).performClick()
                    a.supportFragmentManager.executePendingTransactions()
                    val picker=a.supportFragmentManager.findFragmentByTag("export_picker") as ExportPickerSheet
                    picker.requireView().findViewById<android.view.View>(R.id.rowExportDocx).performClick()
                    detail.dismissNow()
                }
                val docName=DocxExporter.defaultFilename("guzlikvoice",ts)
                await("Word after closing detail"){downloaded(docName)!=null}
                val docUri=downloaded(docName)!!;downloads+=docUri
                val xml=ctx.contentResolver.openInputStream(docUri)!!.use{inputStream->ZipInputStream(inputStream).use{zip->
                    var result="";var entry=zip.nextEntry
                    while(entry!=null){if(entry.name=="word/document.xml")result=zip.readBytes().toString(Charsets.UTF_8);entry=zip.nextEntry};result
                }}
                assertTrue(xml.contains("Проверка сохранения ручной правки."))
                val chooser=java.util.concurrent.atomic.AtomicReference<android.content.Intent>()
                val monitor=object:android.app.Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? {
                        if(intent.action==android.content.Intent.ACTION_CHOOSER) {
                            chooser.set(intent);return android.app.Instrumentation.ActivityResult(0,null)
                        }
                        return null
                    }
                }
                instrumentation.addMonitor(monitor)
                try {
                    await("Share action after closing detail") {
                        var shown=false
                        scenario.onActivity{shown=it.findViewById<android.view.View>(com.google.android.material.R.id.snackbar_action)?.isShown==true}
                        shown
                    }
                    onView(withId(com.google.android.material.R.id.snackbar_action)).perform(click())
                    assertNotNull("Share action did not launch chooser",chooser.get())
                    @Suppress("DEPRECATION")
                    val share=chooser.get().getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
                    assertTrue(share.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
                    @Suppress("DEPRECATION")
                    val stream=share.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)!!
                    assertEquals("content",stream.scheme)
                    assertTrue(ctx.contentResolver.openInputStream(stream)!!.use{it.read()>0})
                }finally{instrumentation.removeMonitor(monitor)}
                open()
                scenario.onActivity{a->
                    val detail=sheet(a);val v=detail.requireView()
                    if(v.findViewById<android.view.View>(R.id.layoutRecordActions).visibility!=android.view.View.VISIBLE)
                        v.findViewById<android.view.View>(R.id.rowActionsToggle).performClick()
                    v.findViewById<android.view.View>(R.id.btnExportFormats).performClick()
                    a.supportFragmentManager.executePendingTransactions()
                }
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity{a->
                    val picker=a.supportFragmentManager.findFragmentByTag("export_picker") as ExportPickerSheet
                    picker.requireView().findViewById<android.view.View>(R.id.rowExportAudio).performClick()
                }
                val audioName=ExportFileNames.forRecord("guzlikvoice",ts,"wav")
                await("Audio export after recreation"){downloaded(audioName)!=null}
                val audioUri=downloaded(audioName)!!;downloads+=audioUri
                val exported=ctx.contentResolver.openInputStream(audioUri)!!.use{it.readBytes()}
                assertArrayEquals(input.readBytes(),exported)
                scenario.onActivity{a->
                    sheet(a).requireView().findViewById<android.view.View>(R.id.btnDiarize).performClick()
                }
                onView(withText("Разделить")).perform(click())
                await("Diarization done",120_000){SpeakerCentroidStorage.get(ctx,ts)?.centroids?.isNotEmpty()==true && !DiarizationJobRunner.hasActiveJob(ts)}
                assertFalse(RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.segments.isNullOrEmpty())
                assertTrue(SpeakerCentroidStorage.get(ctx,ts)!!.centroids.isNotEmpty())
                assertTrue("Diarization discarded a pre-existing manual edit",RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text
                    .contains("Проверка сохранения ручной правки."))
                scenario.onActivity{a->
                    val v=sheet(a).requireView();val transcript=v.findViewById<TextView>(R.id.tvDetailText).text.toString()
                    assertEquals("${transcript.length} симв.",v.findViewById<TextView>(R.id.tvDetailChars).text.toString())
                    assertEquals(android.view.View.VISIBLE,v.findViewById<android.view.View>(R.id.btnSyncAudio).visibility)
                    v.findViewById<android.view.View>(R.id.btnSyncAudio).performClick()
                    assertEquals(transcript,v.findViewById<TextView>(R.id.tvDetailText).text.toString())
                }
                val report=org.json.JSONObject().put("import_asr",true).put("edit_then_time_links_preserved",true)
                    .put("question_uses_current_text",true).put("export_after_dismiss",true).put("word_contains_edit",true)
                    .put("audio_export_after_recreation",true).put("audio_bytes_match",true).put("diarization",true)
                    .put("share_after_dismiss_has_read_grant",true)
                File(ctx.getExternalFilesDir(null),"eval/out/full-user-flow.json").apply{parentFile!!.mkdirs();writeText(report.toString(2))}
            }
        }finally{
            downloads.forEach{ctx.contentResolver.delete(it,null,null)}
            if(ts!=0L)RecordStorage.deleteByTimestamp(ctx,ts) else input.delete()
        }
    }

    @Test fun assistantNoteQuestionProtocolAndPdf() {
        assumeTrue(args.getString("assistantFlow")=="true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        assertTrue("Verified assistant model required",LlmModels.isDownloaded(ctx,LlmModels.GEMMA))
        val previousModel=AppPrefs.getLlmModelId(ctx)
        AppPrefs.setLlmModelId(ctx,LlmModels.GEMMA.id)
        val text="Аудит генерации. Завтра в 15:00 позвонить врачу и записаться на приём."
        val ts=RecordStorage.addToToday(ctx,text,"text",null,linkPendingEvent=false)
        val date=RecordStorage.findEntryByTimestamp(ctx,ts)!!.first
        val previousSummary=RecordStorage.getAllRecords(ctx).first{it.date==date}.daySummary
        var later=0L;var pdf:Uri?=null
        try {
            openFromLauncher()
            MainUi().use{ui->
                val loaded=CountDownLatch(1)
                ui.onActivity{it.ensureLlmLoaded{loaded.countDown()}}
                assertTrue("Assistant load timed out",loaded.await(5,TimeUnit.MINUTES))
                ui.onActivity{assertTrue(it.llmHelper?.isReady==true);it.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager).setCurrentItem(1,false)}
                await("History ready"){
                    var ready=false
                    ui.onActivity{a->ready=a.supportFragmentManager.fragments.filterIsInstance<HistoryFragment>().firstOrNull()
                        ?.view?.findViewById<android.view.View>(R.id.btnAnalyzeDay)?.isEnabled==true}
                    ready
                }
                ui.onActivity{a->a.supportFragmentManager.fragments.filterIsInstance<HistoryFragment>().first().requireView()
                    .findViewById<android.view.View>(R.id.btnAnalyzeDay).performClick()}
                onView(isAssignableFrom(android.widget.ListView::class.java))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(object:androidx.test.espresso.ViewAction {
                        override fun getDescription()="Select only the synthetic audit entry"
                        override fun getConstraints()=isAssignableFrom(android.widget.ListView::class.java)
                        override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View){
                            val list=view as android.widget.ListView
                            for(i in 0 until list.count)if(!list.adapter.getItem(i).toString().contains("Аудит генерации"))
                                list.performItemClick(null,i,list.adapter.getItemId(i))
                        }
                    })
                // A new import can reorder History while the selection dialog is open.
                later=RecordStorage.addToToday(ctx,"Аудит фоновой записи. Купить велосипед.","text",null,linkPendingEvent=false)
                await("History updated behind selection"){
                    var ready=false
                    ui.onActivity{a->
                        val history=a.supportFragmentManager.fragments.filterIsInstance<HistoryFragment>().first()
                        val entries=HistoryFragment::class.java.getDeclaredField("currentEntries").apply{isAccessible=true}.get(history) as List<*>
                        ready=entries.filterIsInstance<DailyRecord.Entry>().any{it.timestamp==later}
                    };ready
                }
                onView(withText("Анализировать")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                await("Real assistant note",600_000){RecordStorage.getAllRecords(ctx).firstOrNull{it.date==date}?.daySummary?.let{it.isNotBlank() && it!=previousSummary}==true}
                val summary=RecordStorage.getAllRecords(ctx).first{it.date==date}.daySummary
                assertTrue("Explicit task missing",summary.lowercase().contains("позвон"))
                assertFalse("Unchecked new entry leaked into note",summary.lowercase().contains("велосипед"))
                ui.onActivity{a->TranscriptionDetailSheet.newInstance(LogEntry(ts,text,"",emptyList(),emptyList(),"text"),date)
                    .showNow(a.supportFragmentManager,"assistant-detail")}
                ui.onActivity{a->sheet(a).requireView().findViewById<android.view.View>(R.id.btnProtocol).performClick()}
                await("Protocol started") {ProtocolRunner.state !is ProtocolRunner.State.Idle}
                ui.onActivity{a->a.supportFragmentManager.fragments.filterIsInstance<ProtocolSheet>().firstOrNull()?.dismissNow()}
                await("Protocol saved after closing",600_000){RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.templateResult?.isNotBlank()==true}
                assertTrue(ProtocolRunner.state is ProtocolRunner.State.Done)
                ui.onActivity{a->sheet(a).requireView().findViewById<android.view.View>(R.id.btnAskQuestion).performClick()}
                onView(withId(R.id.etQaQuestion)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(replaceText("Во сколько нужно позвонить врачу?"))
                onView(withId(R.id.btnAsk)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                await("Question running before rotation"){QaJobRunner.state is QaJobRunner.State.Running || QaJobRunner.state is QaJobRunner.State.Done}
                ui.recreate()
                await("Real question answered",600_000){QaJobRunner.state is QaJobRunner.State.Done}
                val answer=(QaJobRunner.state as QaJobRunner.State.Done).answer
                assertTrue("Answer missed explicit time",answer.contains("15"))
                ui.onActivity{a->
                    a.supportFragmentManager.fragments.filterIsInstance<QaSheet>().first().dismissNow()
                    val detail=sheet(a)
                    detail.requireView().findViewById<android.view.View>(R.id.btnExportFormats).performClick()
                    a.supportFragmentManager.executePendingTransactions()
                    (a.supportFragmentManager.findFragmentByTag("export_picker") as ExportPickerSheet).requireView()
                        .findViewById<android.view.View>(R.id.rowExportPdf).performClick()
                }
                val name=PdfExporter.defaultFilename("guzlikvoice",ts)
                await("PDF with saved protocol"){downloaded(name)!=null}
                pdf=downloaded(name)!!
                val bytes=ctx.contentResolver.openInputStream(pdf!!)!!.use{it.readBytes()}
                assertTrue(bytes.size>1000)
                assertEquals("%PDF",bytes.take(4).toByteArray().toString(Charsets.US_ASCII))
                val report=org.json.JSONObject().put("real_note",summary).put("question_answer",answer)
                    .put("protocol_saved_after_dismiss",true).put("pdf_valid",true).put("selection_snapshot_survives_import",true)
                    .put("question_survives_rotation",true)
                File(ctx.getExternalFilesDir(null),"eval/out/assistant-user-flow.json").writeText(report.toString(2))
            }
        }finally{
            QaJobRunner.clear();ProtocolRunner.cancel()
            pdf?.let{ctx.contentResolver.delete(it,null,null)}
            RecordStorage.deleteByTimestamp(ctx,ts)
            if(later!=0L)RecordStorage.deleteByTimestamp(ctx,later)
            RecordStorage.saveDaySummary(ctx,date,previousSummary)
            AppPrefs.setLlmModelId(ctx,previousModel ?: LlmModels.SELECTABLE.first().id)
        }
    }

    @Test fun legacySrtUsesBoundedAsrAndCachesTimestamps() {
        assumeTrue(args.getString("legacyFlow")=="true")
        assertTrue(ctx.packageName.endsWith(".dev"))
        val input=clip()
        val ts=RecordStorage.addToToday(ctx,"Ручной текст старой записи.","import",null,input.absolutePath,linkPendingEvent=false)
        var exported:Uri?=null
        try {
            openFromLauncher()
            MainUi().use{ui->
                await("ASR ready for legacy export",120_000){WhisperHelper.active?.isReady==true}
                val date=RecordStorage.findEntryByTimestamp(ctx,ts)!!.first
                ui.onActivity{a->
                    val detail=TranscriptionDetailSheet.newInstance(LogEntry(ts,"Ручной текст старой записи.","",emptyList(),emptyList(),"import",input.absolutePath),date)
                    detail.showNow(a.supportFragmentManager,"legacy-detail")
                    detail.requireView().findViewById<android.view.View>(R.id.btnExportFormats).performClick()
                    a.supportFragmentManager.executePendingTransactions()
                    (a.supportFragmentManager.findFragmentByTag("export_picker") as ExportPickerSheet).requireView()
                        .findViewById<android.view.View>(R.id.rowExportSrt).performClick()
                    detail.dismissNow()
                }
                await("Legacy SRT exported",300_000){downloaded("guzlikvoice_${ts}.srt")!=null}
                exported=downloaded("guzlikvoice_${ts}.srt")!!
                val srt=ctx.contentResolver.openInputStream(exported!!)!!.bufferedReader().use{it.readText()}
                assertTrue(srt.startsWith("1\n"));assertTrue(srt.contains(" --> "))
                val saved=RecordStorage.findEntryByTimestamp(ctx,ts)!!.second
                assertEquals("Ручной текст старой записи.",saved.text)
                assertFalse(saved.segments.isNullOrEmpty())
                File(ctx.getExternalFilesDir(null),"eval/out/legacy-srt-flow.json").writeText(org.json.JSONObject()
                    .put("real_asr",true).put("srt_valid",true).put("manual_text_preserved",true)
                    .put("timestamps_cached",saved.segments!!.size).toString(2))
            }
        }finally{
            exported?.let{ctx.contentResolver.delete(it,null,null)}
            RecordStorage.deleteByTimestamp(ctx,ts)
        }
    }
}
