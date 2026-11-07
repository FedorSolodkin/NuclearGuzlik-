package com.example.guzlikvoice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportFileNames {
    fun forRecord(prefix:String,timestamp:Long,extension:String):String =
        prefix+"_"+SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS",Locale.US).format(Date(timestamp))+"."+extension
}
