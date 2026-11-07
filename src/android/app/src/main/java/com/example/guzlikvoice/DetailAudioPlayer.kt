package com.example.guzlikvoice

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView

/**
 * Плеер в карточке записи: кнопка, ползунок, время, переход к фразе по тапу.
 * Вынесен из TranscriptionDetailSheet; жизненный цикл — [bind] / [release].
 */
class DetailAudioPlayer {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var root: View? = null

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!isSeeking) sync(p)
            handler.postDelayed(this, 250)
        }
    }

    /** Показывает плеер для [audioPath] или прячет его, если файла нет. */
    fun bind(view: View, audioPath: String) {
        release()
        root = view
        val audioFile = audioPath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        val container = view.findViewById<View>(R.id.playerContainer)
        val divider   = view.findViewById<View>(R.id.playerDivider)
        fun hide() { container.visibility = View.GONE; divider.visibility = View.GONE }
        if (audioFile == null || !audioFile.exists()) { hide(); return }

        val btnPlayPause = view.findViewById<ImageButton>(R.id.btnDetailPlayPause)
        val seekBar      = view.findViewById<SeekBar>(R.id.seekDetailAudio)
        val tvPlayed     = view.findViewById<TextView>(R.id.tvDetailPlayedTime)
        val tvTotal      = view.findViewById<TextView>(R.id.tvDetailTotalTime)

        val mp = try {
            MediaPlayer().apply { setDataSource(audioFile.absolutePath); prepare() }
        } catch (e: Exception) {
            Log.e("DetailAudioPlayer", "MediaPlayer init failed: ${e.javaClass.simpleName}")
            hide(); return
        }
        container.visibility = View.VISIBLE
        divider.visibility   = View.VISIBLE
        player = mp
        tvTotal.text = formatMs(mp.duration)

        mp.setOnCompletionListener {
            btnPlayPause.setImageResource(R.drawable.ic_play)
            seekBar.progress = seekBar.max
            tvPlayed.text = formatMs(mp.duration)
        }
        btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) { p.pause(); btnPlayPause.setImageResource(R.drawable.ic_play) }
            else { p.start(); btnPlayPause.setImageResource(R.drawable.ic_pause) }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) tvPlayed.text = formatMs(msFromProgress(progress, mp.duration))
            }
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeeking = false
                player?.seekTo(msFromProgress(sb.progress, mp.duration))
            }
        })
        handler.post(tick)
    }

    /** Перейти к моменту записи и играть (тап по фразе / сомнительному слову). */
    fun seekAndPlay(startMs: Long) {
        val view = root ?: return
        val p = player ?: return
        p.seekTo(startMs.toInt())
        if (!p.isPlaying) {
            p.start()
            view.findViewById<ImageButton>(R.id.btnDetailPlayPause).setImageResource(R.drawable.ic_pause)
        }
        sync(p)
    }

    fun release() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
    }

    private fun sync(p: MediaPlayer) {
        val view = root ?: return
        val seekBar  = view.findViewById<SeekBar>(R.id.seekDetailAudio)
        val tvPlayed = view.findViewById<TextView>(R.id.tvDetailPlayedTime)
        val duration = p.duration.coerceAtLeast(1)
        seekBar.progress = (p.currentPosition.toLong() * seekBar.max / duration).toInt()
        tvPlayed.text = formatMs(p.currentPosition)
    }

    private fun msFromProgress(progress: Int, duration: Int) = (progress.toLong() * duration / 1000).toInt()

    private fun formatMs(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
