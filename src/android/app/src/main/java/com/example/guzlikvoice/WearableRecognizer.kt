package com.example.guzlikvoice

import android.content.Context
import java.io.File

/** Expensive native boundary, so service lifecycle can be tested without loading a model. */
interface WearableRecognizer {
    val modelName: String
    fun retain(): Boolean
    fun release()
    fun transcribe(file: File, cancelled: () -> Boolean): List<WhisperHelper.Segment>
}

internal class LocalWearableRecognizer(private val context: Context, private val helper: WhisperHelper) : WearableRecognizer {
    override val modelName get() = helper.loadedModelName
    override fun retain() = helper.retain()
    override fun release() = helper.releaseUse()
    override fun transcribe(file: File, cancelled: () -> Boolean) = StoredAudioTranscriber.transcribe(context, file, helper, cancelled)
}
