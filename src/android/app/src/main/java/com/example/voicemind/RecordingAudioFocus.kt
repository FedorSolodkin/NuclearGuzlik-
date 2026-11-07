package com.example.voicemind

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Аудиофокус на время записи: чтобы заметить входящий звонок или другое
 * приложение, перехватившее микрофон. USAGE_VOICE_COMMUNICATION даёт высокий
 * приоритет — система сначала присылает LOSS_TRANSIENT, и мы предупреждаем.
 * minSdk 26 — всегда AudioFocusRequest.
 */
class RecordingAudioFocus(
    private val onLost: () -> Unit,
    private val onRegained: () -> Unit,
) {
    private var request: AudioFocusRequest? = null
    @Volatile var interrupted = false
        private set

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { interrupted = true; onLost() }
            AudioManager.AUDIOFOCUS_GAIN -> if (interrupted) { interrupted = false; onRegained() }
        }
    }

    fun request(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .build()
        request = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun release(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        request?.let { am.abandonAudioFocusRequest(it) }
        request = null
        interrupted = false
    }
}
