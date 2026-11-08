package com.guzlik.voice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Автоматическое разделение по голосам сразу после расшифровки (запись с микрофона и импорт файла).
 * Число людей не задаётся: определяется автоматически, как в «Не знаю — определить автоматически».
 * Работает в фоне ([DiarizationJobRunner]); если модель голосов недоступна, запись остаётся с обычным текстом.
 */
object AutoDiarization {
    private const val TAG = "AutoDiarization"

    fun start(ctx: Context, audioFile: File?, timestamp: Long, segments: List<WhisperHelper.Segment>) {
        if (timestamp == 0L || segments.isEmpty() || audioFile == null || !audioFile.isFile) return
        try {
            DiarizationJobRunner.start(ctx.applicationContext, audioFile, timestamp, null, segments,
                WhisperHelper.active?.takeIf { it.isReady }, false)
        } catch (e: Throwable) {
            Log.w(TAG, "auto diarization not started: ${e.message}")
        }
    }
}
