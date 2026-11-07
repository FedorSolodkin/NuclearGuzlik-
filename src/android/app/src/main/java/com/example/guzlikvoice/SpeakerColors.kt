package com.example.guzlikvoice

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.View

/**
 * Палитра из 8 цветов для меток спикеров + рендерер, превращающий текстовые
 * маркеры `[Говорящий X]` в цветные округлые чипы через SpannableString.
 *
 * Порядок цветов совпадает с индексом спикера (assignments), поэтому в
 * NameSpeakersSheet аватар «А» и чип «А» в транскрипте одного цвета.
 *
 * Цвета выбраны из Material Palette 500-tier, читаемые и на светлой, и на тёмной
 * теме благодаря 22% alpha на фоне + полный foreground у текста.
 */
object SpeakerColors {

    private val PALETTE = intArrayOf(
        0xFF6C63FF.toInt(), // indigo
        0xFF00A688.toInt(), // teal
        0xFFE17055.toInt(), // coral
        0xFF0984E3.toInt(), // blue
        0xFFD63031.toInt(), // red
        0xFF8E44AD.toInt(), // purple
        0xFF16A085.toInt(), // green
        0xFFE67E22.toInt(), // orange
    )

    fun forIndex(i: Int): Int = PALETTE[i.coerceAtLeast(0) % PALETTE.size]

    /** Полупрозрачная версия того же цвета — для фона чипа/аватара. */
    fun softForIndex(i: Int): Int {
        val c = forIndex(i)
        // ~22% alpha
        return (c and 0x00FFFFFF) or 0x38000000
    }

    /**
     * Преобразует транскрипт с маркерами `[Говорящий X]` в SpannableString,
     * где каждый маркер отрисован как цветной округлый чип.
     *
     * displayNames — карта от буквы (или имени) в скобках → индекс спикера.
     * Индекс нужен для стабильной привязки цвета: если пользователь переименовал
     * «Говорящий А» → «Иван», в тексте появится маркер [Говорящий Иван], но
     * цвет должен остаться тем же, что и у аватара «А».
     */
    fun renderWithChips(text: String, labelToIndex: Map<String, Int>, viewForDp: View): CharSequence {
        if (text.isBlank()) return text
        val regex = Regex("""\[(?:Говорящий ([^\]]+)|(Говорят одновременно))\]""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val sb = SpannableString(text)
        for (m in matches) {
            val label = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val idx = labelToIndex[label] ?: 0
            val special = label == "не определён" || label == "Говорят одновременно"
            val fg = if (special) 0xFF999999.toInt() else forIndex(idx)
            val bg = if (special) 0x33999999 else softForIndex(idx)
            val chipText = label
            sb.setSpan(
                SpeakerChipSpan(chipText, fg, bg, viewForDp),
                m.range.first, m.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }
}

/**
 * Округлый цветной чип с текстом (обычно 1-2 символа: буква спикера или короткое имя).
 * Работает вместо диапазона исходного текста — исходный `[Говорящий X]` не показывается,
 * вместо него рисуется этот чип.
 *
 * Особенности:
 *  - Ширина чипа = ширина текста + горизонтальный паддинг.
 *  - Высота — по метрикам шрифта строки, чтобы вписаться без обрезки.
 *  - Baseline корректирован под центрирование текста внутри капсулы.
 */
private class SpeakerChipSpan(
    private val text: String,
    private val fgColor: Int,
    private val bgColor: Int,
    view: View,
) : ReplacementSpan() {

    private val padH: Float = dpF(view, 8f)
    private val padV: Float = dpF(view, 3f)
    private val radius: Float = dpF(view, 12f)
    private val textSizePx: Float = dpF(view, 12f)

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgColor
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = textSizePx
        textAlign = Paint.Align.LEFT
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val textWidth = textPaint.measureText(this.text)
        if (fm != null) {
            val outerFm = paint.fontMetricsInt
            fm.ascent = outerFm.ascent
            fm.top = outerFm.top
            fm.descent = outerFm.descent
            fm.bottom = outerFm.bottom
        }
        return (textWidth + 2 * padH).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
                      x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val textWidth = textPaint.measureText(this.text)
        val chipWidth = textWidth + 2 * padH

        // Высота чипа — центрируем в пределах строки, чуть уже строки чтобы не «пузыриться».
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val chipHeight = textHeight + 2 * padV
        val lineCenter = (top + bottom) / 2f
        val chipTop = lineCenter - chipHeight / 2f
        val chipBottom = lineCenter + chipHeight / 2f

        val rect = RectF(x, chipTop, x + chipWidth, chipBottom)
        canvas.drawRoundRect(rect, radius, radius, chipPaint)

        val textY = lineCenter - (fm.ascent + fm.descent) / 2f
        canvas.drawText(this.text, x + padH, textY, textPaint)
    }
}

private fun dpF(view: View, dp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, view.resources.displayMetrics)
