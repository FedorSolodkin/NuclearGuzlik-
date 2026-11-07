package com.example.voicemind

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadsExporterTest {
    class Provider : ContentProvider() {
        var deleted=false
        var publish=true
        var cannotOpen=false
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/pdf"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?)=Uri.parse("content://media/external/downloads/17")
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int {deleted=true;return 1}
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=if(publish)1 else 0
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if(cannotOpen)throw java.io.FileNotFoundException("No output stream")
            return ParcelFileDescriptor.open(File(context!!.cacheDir,"download-provider.pdf"),
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }
    private fun verify(cannotOpen:Boolean,publish:Boolean,success:Boolean) {
        val ctx=ApplicationProvider.getApplicationContext<Context>()
        val provider=Provider().apply {
            this.cannotOpen=cannotOpen;this.publish=publish
            attachInfo(ctx,android.content.pm.ProviderInfo().apply { authority="media";exported=true })
        }
        ShadowContentResolver.registerProviderInternal("media",provider)
        val source=File(ctx.cacheDir,"source.pdf").apply{writeText("export")}
        val result=DownloadsExporter.saveToDownloads(ctx,source,"application/pdf")
        assertEquals(success,result!=null)
        assertEquals(!success,provider.deleted)
        assertEquals("export",source.readText())
    }
    @Test fun failedOutputDoesNotLeaveAnUnfinishedDownload()=verify(true,true,false)
    @Test fun failedPublishCleansUpTheInsertedDownload()=verify(false,false,false)
    @Test fun successfulCopyPublishesDownload()=verify(false,true,true)
}
