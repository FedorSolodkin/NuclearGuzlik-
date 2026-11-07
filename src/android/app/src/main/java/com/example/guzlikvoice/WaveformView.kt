package com.example.guzlikvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Простой rolling waveform: хранит последние N значений амплитуды (0..1)
 * и рисует их как вертикальные полосы справа-налево.
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val CAPACITY  = 64
        private const val BAR_GAP   = 3f
    }

    private val values = FloatArray(CAPACITY)
    private var head = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4E7BFF.toInt()
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    /** value 0..1 */
    fun pushAmplitude(value: Float) {
        values[head] = value.coerceIn(0f, 1f)
        head = (head + 1) % CAPACITY
        postInvalidateOnAnimation()
    }

    fun clear() {
        for (i in values.indices) values[i] = 0f
        head = 0
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val barWidth = (w - BAR_GAP * (CAPACITY - 1)) / CAPACITY
        val cy = h / 2f
        val maxHalf = h / 2f - 2f

        for (i in 0 until CAPACITY) {
            // Oldest → newest, left → right
            val idx = (head + i) % CAPACITY
            val v = values[idx]
            val halfBar = (v * maxHalf).coerceAtLeast(1f)
            val left = i * (barWidth + BAR_GAP)
            val right = left + barWidth
            canvas.drawRoundRect(left, cy - halfBar, right, cy + halfBar, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }
}
