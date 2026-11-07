package com.example.guzlikvoice

import android.content.Context
import android.os.Build

/** Device-specific measured setting; a profile never changes model precision or hop. */
object DiarizationRuntimePolicy {
    private fun signature() = "${Build.FINGERPRINT}:cpu-v3:ort"
    fun workers(ctx: Context?): Int {
        val fallback = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1,3)
        if (ctx == null) return fallback
        val prefs = ctx.getSharedPreferences("diarization_runtime",0)
        return if (prefs.getString("signature",null) == signature()) prefs.getInt("workers",fallback).coerceIn(1,3) else fallback
    }
    fun spinning(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("diarization_runtime",0)
        return if (prefs.getString("signature",null) == signature()) prefs.getBoolean("spinning",true) else true
    }
    fun threads(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences("diarization_runtime",0)
        return if (prefs.getString("signature",null) == signature()) prefs.getInt("threads",2).coerceIn(1,4) else 2
    }
    fun saveMeasured(ctx: Context, workers: Int, allowSpinning: Boolean, onnxThreads: Int) {
        require(workers in 1..3 && onnxThreads in 1..4)
        ctx.getSharedPreferences("diarization_runtime",0).edit().putString("signature",signature()).putInt("workers",workers).putBoolean("spinning",allowSpinning).putInt("threads",onnxThreads).apply()
    }
}
