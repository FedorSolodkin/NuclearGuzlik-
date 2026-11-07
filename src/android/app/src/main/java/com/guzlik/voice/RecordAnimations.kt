package com.guzlik.voice

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView

/** Пульсация кнопки записи и мигающий статус на экране записи. */
class RecordAnimations(
    private val pulseRing: View,
    private val recordButton: View,
    private val status: TextView,
) {
    private var pulse: ValueAnimator? = null
    private var buttonPulse: ValueAnimator? = null
    private var statusPulse: ValueAnimator? = null

    private fun loop(from: Float, to: Float, ms: Long, onValue: (Float) -> Unit) =
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { onValue(it.animatedValue as Float) }
            start()
        }

    fun startPulse() {
        stopPulse()
        pulseRing.alpha = 0.5f
        pulse = loop(1f, 1.4f, 1200) { v ->
            pulseRing.scaleX = v; pulseRing.scaleY = v; pulseRing.alpha = (1.4f - v) * 1.2f
        }
        // Едва заметный вдох-выдох самой кнопки: синхронизирует с ритмом записи, не отвлекая.
        buttonPulse = loop(1f, 1.06f, 1400) { v -> recordButton.scaleX = v; recordButton.scaleY = v }
    }

    fun stopPulse() {
        pulse?.cancel(); pulse = null
        pulseRing.scaleX = 1f; pulseRing.scaleY = 1f; pulseRing.alpha = 0f
        buttonPulse?.cancel(); buttonPulse = null
        recordButton.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    fun startStatusPulse(text: String) {
        status.text = text
        status.alpha = 1f
        statusPulse?.cancel()
        statusPulse = loop(1f, 0.35f, 750) { status.alpha = it }
    }

    fun stopStatusPulse(finalText: String = "") {
        statusPulse?.cancel(); statusPulse = null
        status.alpha = 1f
        if (finalText.isNotEmpty()) status.text = finalText
    }

    fun cancelAll() {
        pulse?.cancel(); buttonPulse?.cancel(); statusPulse?.cancel()
        pulse = null; buttonPulse = null; statusPulse = null
    }
}
