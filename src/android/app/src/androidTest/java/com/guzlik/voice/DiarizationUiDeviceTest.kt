package com.guzlik.voice

import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiarizationUiDeviceTest {
    private fun optedIn() = InstrumentationRegistry.getArguments().getString("qualityUi") == "true"
    private fun openFromLauncher() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val ctx=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("am start -n ${ctx.packageName}/com.guzlik.voice.OnboardingActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        waitForMain()
    }
    private fun waitForMain() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val deadline=android.os.SystemClock.elapsedRealtime()+15_000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            instrumentation.waitForIdleSync()
            var ready=false
            instrumentation.runOnMainSync {
                ready=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any{it is MainActivity}
            }
            if(ready)return
            Thread.sleep(100)
        }
        fail("Main activity did not resume through launcher")
    }
    @Test fun selectedPhraseCanBeRelabeledWithoutChangingOtherWords() {
        assumeTrue(optedIn())
        val instrumentation=InstrumentationRegistry.getInstrumentation();val ctx=instrumentation.targetContext
        assertTrue(ctx.packageName.endsWith(".dev"))
        SetupChoices.skipLlm(ctx)
        openFromLauncher()
        val text="[Говорящий А]\nПервое предложение. Короткий ответ. Продолжение."
        val ts=RecordStorage.addToToday(ctx,text,"text",null,linkPendingEvent=false)
        SpeakerCentroidStorage.put(ctx,SpeakerCentroidStorage.Snapshot(ts,listOf("А","Б"),listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f)),listOf(null,null)))
        val date=RecordStorage.findEntryByTimestamp(ctx,ts)!!.first
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val sheet=TranscriptionDetailSheet.newInstance(LogEntry(ts,text,"",emptyList(),emptyList(),"text"),date)
                    sheet.showNow(activity.supportFragmentManager,"quality-ui")
                    val tv=sheet.requireView().findViewById<TextView>(R.id.tvDetailText)
                    val start=text.indexOf("Короткий ответ.")
                    Selection.setSelection(tv.text as Spannable,start,start+"Короткий ответ.".length)
                    val mode=tv.startActionMode(tv.customSelectionActionModeCallback,ActionMode.TYPE_FLOATING)!!
                    assertTrue(mode.menu.performIdentifierAction(0x564d,0))
                }
                onView(withText("Говорящий Б")).perform(click())
                instrumentation.waitForIdleSync()
                val changed=RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text
                assertTrue(changed.contains("[Говорящий Б]\nКороткий ответ."))
                assertTrue(changed.endsWith("[Говорящий А]\nПродолжение."))
                assertTrue(changed.startsWith("[Говорящий А]\nПервое предложение."))
                assertNull(DiarizationJobRunner.stateOf(ts))
            }
        } finally { RecordStorage.deleteByTimestamp(ctx,ts) }
    }

    @Test fun skippedAssistantDoesNotReopenSetupOnNextLaunch() {
        assumeTrue(optedIn())
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ctx.packageName.endsWith(".dev"))
        SetupChoices.skipLlm(ctx)
        openFromLauncher()
        repeat(2) {
            // Installed speech models are used; this test never downloads an assistant.
            ctx.startActivity(android.content.Intent(ctx,SetupActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForMain()
            onView(withId(R.id.tvSetupStatus)).check(doesNotExist())
        }
        assertTrue(SetupChoices.llmSkipped(ctx))
    }
    @Test fun removeOnlySyntheticUiFixtures() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qualityCleanup")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ctx.packageName.endsWith(".dev"))
        val text="[Говорящий А]\nПервое предложение. Короткий ответ. Продолжение."
        val at=text.indexOf("Короткий ответ.")
        val corrected=SpeakerTextEdits.relabel(text,at,at+"Короткий ответ.".length,"[Говорящий Б]")
        for (day in RecordStorage.getAllRecords(ctx)) for (entry in day.entries) {
            if(entry.audioPath.isEmpty() && entry.source=="text" && entry.text in listOf(text,corrected))
                RecordStorage.deleteByTimestamp(ctx,entry.timestamp)
        }
    }

}
