package com.guzlik.voice

import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import com.guzlik.voice.db.RecordDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class SpeakerCorrectionUiTest {
    class Host : AppCompatActivity() {
        override fun onCreate(b: Bundle?) {
            setTheme(R.style.Theme_GuzlikVoice)
            super.onCreate(b)
            setContentView(FrameLayout(this))
        }
    }
    private class Mode(private val menu: Menu, private val inflater: MenuInflater) : ActionMode() {
        override fun setTitle(title: CharSequence?) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence?) {}
        override fun setSubtitle(resId: Int) {}
        override fun setCustomView(view: View?) {}
        override fun invalidate() {}
        override fun finish() {}
        override fun getMenu() = menu
        override fun getTitle(): CharSequence = ""
        override fun getSubtitle(): CharSequence = ""
        override fun getCustomView(): View? = null
        override fun getMenuInflater() = inflater
    }
    @Test fun realSheetSelectionMenuSavesOnlySelectedPhrase() = verifyCorrection(false)
    @Test fun selectionMenuCannotOverwriteTextChangedWhileItWasOpen() = verifyCorrection(true)
    @Test fun renamedSpeakerPersistsWithoutAParentCallbackAfterRecreation() {
        val ctx=ApplicationProvider.getApplicationContext<android.content.Context>()
        RecordDatabase.setForTests(RecordDatabase.build(ctx,null))
        val ts=RecordStorage.addToToday(ctx,"[Говорящий А]\nРучная правка!","text",null,linkPendingEvent=false)
        val date=RecordStorage.findEntryByTimestamp(ctx,ts)!!.first
        SpeakerCentroidStorage.put(ctx,SpeakerCentroidStorage.Snapshot(ts,listOf("А"),listOf(floatArrayOf(1f,0f)),listOf(null)))
        val controller=Robolectric.buildActivity(Host::class.java).setup()
        val profilesBefore=VoiceProfileStorage.getAll(ctx).map{it.id}.toSet()
        try {
            TranscriptionDetailSheet.newInstance(LogEntry(ts,"[Говорящий А]\nРучная правка!","",emptyList(),emptyList(),"text"),date)
                .showNow(controller.get().supportFragmentManager,"rename-detail")
            NameSpeakersSheet.newInstance(ts).showNow(controller.get().supportFragmentManager,"name_speakers")
            controller.recreate()
            val host=controller.get()
            val names=host.supportFragmentManager.findFragmentByTag("name_speakers") as NameSpeakersSheet
            val recycler=names.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSpeakers)
            recycler.measure(View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY))
            recycler.layout(0,0,900,1200)
            recycler.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.tvSpeakerAction).performClick()
            val dialog=ShadowDialog.getLatestDialog() as android.app.AlertDialog
            val input=dialog.window!!.decorView.findViewById<android.widget.EditText>(android.R.id.edit)
                ?: findEditText(dialog.window!!.decorView)!!
            input.setText("Анна")
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("[Говорящий Анна]\nРучная правка!",RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text)
            assertEquals("Анна",SpeakerCentroidStorage.get(ctx,ts)!!.labels.single())
            val detail=host.supportFragmentManager.findFragmentByTag("rename-detail") as TranscriptionDetailSheet
            assertEquals("[Говорящий Анна]\nРучная правка!",detail.requireView().findViewById<TextView>(R.id.tvDetailText).text.toString())
        }finally {
            controller.pause().stop().destroy()
            VoiceProfileStorage.getAll(ctx).filter{it.id !in profilesBefore}.forEach{VoiceProfileStorage.delete(ctx,it.id)}
            RecordStorage.deleteByTimestamp(ctx,ts)
            RecordDatabase.get(ctx).close();RecordDatabase.setForTests(null)
        }
    }
    private fun findEditText(view:View):android.widget.EditText? {
        if(view is android.widget.EditText)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findEditText(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun verifyCorrection(changedWhileOpen: Boolean) {
        val ctx=ApplicationProvider.getApplicationContext<android.content.Context>()
        RecordDatabase.setForTests(RecordDatabase.build(ctx,null))
        val text="[Говорящий А]\nПервая фраза. Да. Продолжение."
        val ts=RecordStorage.addToToday(ctx,text,"text",null,linkPendingEvent=false)
        val date=RecordStorage.findEntryByTimestamp(ctx,ts)!!.first
        SpeakerCentroidStorage.put(ctx,SpeakerCentroidStorage.Snapshot(ts,listOf("А","Б"),listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f)),listOf(null,null)))
        val controller=Robolectric.buildActivity(Host::class.java).setup()
        try {
            val host=controller.get()
            val sheet=TranscriptionDetailSheet.newInstance(LogEntry(ts,text,"",emptyList(),emptyList(),"text"),date)
            sheet.showNow(host.supportFragmentManager,"correction-test")
            val tv=sheet.requireView().findViewById<TextView>(R.id.tvDetailText)
            val from=text.indexOf("Да.")
            Selection.setSelection(tv.text as Spannable,from,from+3)
            val callback=tv.customSelectionActionModeCallback!!
            val menu=MenuBuilder(host);val mode=Mode(menu,MenuInflater(host))
            assertTrue(callback.onCreateActionMode(mode,menu))
            assertTrue(callback.onActionItemClicked(mode,menu.findItem(0x564d)))
            val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            if(changedWhileOpen) RecordStorage.updateEntryText(ctx,ts,"Новые ручные правки")
            dialog.listView.performItemClick(null,1,1)
            val saved=RecordStorage.findEntryByTimestamp(ctx,ts)!!.second.text
            if(changedWhileOpen) assertEquals("Новые ручные правки",saved)
            else {
            assertTrue(saved.contains("[Говорящий Б]\nДа."))
            assertTrue(saved.startsWith("[Говорящий А]\nПервая фраза."))
            assertTrue(saved.endsWith("[Говорящий А]\nПродолжение."))
            assertEquals(saved,tv.text.toString())
            }
        } finally {
            controller.pause().stop().destroy()
            RecordStorage.deleteByTimestamp(ctx,ts)
            RecordDatabase.get(ctx).close();RecordDatabase.setForTests(null)
        }
    }
}
