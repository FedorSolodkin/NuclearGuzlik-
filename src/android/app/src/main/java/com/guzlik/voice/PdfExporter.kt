package com.guzlik.voice

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Рисует PDF на страницы A4 с помощью встроенного android.graphics.pdf.PdfDocument.
 * Никаких внешних библиотек — экономим ~9 МБ APK по сравнению с PdfBox-Android.
 * Кириллица покрывается системным шрифтом Android (Roboto / Noto).
 */
object PdfExporter {

    // A4 в точках при 72 DPI: 595 × 842
    private const val PAGE_WIDTH  = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN      = 48

    private val contentWidth  = PAGE_WIDTH - MARGIN * 2
    private val bottomLimit   = PAGE_HEIGHT - MARGIN

    fun export(
        outFile: File,
        title: String,
        subtitle: String,
        transcript: String,
        templateTitle: String? = null,
        templateBody: String? = null
    ) {
        val doc = PdfDocument()

        val paintTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            isFakeBoldText = true
            color = 0xFF111111.toInt()
        }
        val paintSubtitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = 0xFF888888.toInt()
        }
        val paintSection = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            isFakeBoldText = true
            color = 0xFF666666.toInt()
            letterSpacing = 0.08f
        }
        val paintBody = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = 0xFF222222.toInt()
        }

        val ctx = RenderCtx(doc)

        // Заголовок
        ctx.drawText(title, paintTitle, lineHeight = 26f)
        ctx.drawText(subtitle, paintSubtitle, lineHeight = 15f)
        ctx.advance(16f)

        if (!templateBody.isNullOrBlank() && !templateTitle.isNullOrBlank()) {
            ctx.drawText(templateTitle.uppercase(Locale.getDefault()), paintSection, lineHeight = 16f)
            ctx.advance(4f)
            drawMultiline(ctx, templateBody, paintBody, 15f)
            ctx.advance(20f)
        }

        ctx.drawText("РАСШИФРОВКА", paintSection, lineHeight = 16f)
        ctx.advance(4f)
        drawMultiline(ctx, transcript, paintBody, 15f)

        ctx.finish()

        outFile.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun drawMultiline(ctx: RenderCtx, text: String, paint: TextPaint, lineHeight: Float) {
        for (rawLine in text.split('\n')) {
            if (rawLine.isBlank()) {
                ctx.advance(lineHeight)
                continue
            }
            for (wrapped in wrap(rawLine, paint, ctx.contentWidth.toFloat())) {
                ctx.drawText(wrapped, paint, lineHeight)
            }
        }
    }

    /** Простой word-wrap по ширине. Слишком длинные слова режем посимвольно. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(' ')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current = StringBuilder()
                }
                if (paint.measureText(word) <= maxWidth) {
                    current.append(word)
                } else {
                    // Слово длиннее строки — режем посимвольно
                    val chunks = breakWord(word, paint, maxWidth)
                    for ((i, c) in chunks.withIndex()) {
                        if (i < chunks.lastIndex) out.add(c) else current.append(c)
                    }
                }
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun breakWord(word: String, paint: Paint, maxWidth: Float): List<String> {
        val parts = mutableListOf<String>()
        var buf = StringBuilder()
        for (ch in word) {
            val next = buf.toString() + ch
            if (paint.measureText(next) <= maxWidth) {
                buf.append(ch)
            } else {
                if (buf.isNotEmpty()) parts.add(buf.toString())
                buf = StringBuilder().append(ch)
            }
        }
        if (buf.isNotEmpty()) parts.add(buf.toString())
        return parts
    }

    /** Инкапсулирует курсор и создание страниц. */
    private class RenderCtx(private val doc: PdfDocument) {
        val contentWidth = PAGE_WIDTH - MARGIN * 2
        private var pageNum = 0
        private var page: PdfDocument.Page = newPage()
        private var y = MARGIN.toFloat()

        fun drawText(line: String, paint: TextPaint, lineHeight: Float) {
            if (y + lineHeight > bottomLimit) rollPage()
            page.canvas.drawText(line, MARGIN.toFloat(), y + paint.textSize, paint)
            y += lineHeight
        }

        fun advance(dy: Float) {
            y += dy
            if (y > bottomLimit) rollPage()
        }

        fun finish() {
            doc.finishPage(page)
        }

        private fun newPage(): PdfDocument.Page {
            pageNum++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
            return doc.startPage(info).also { y = MARGIN.toFloat() }
        }

        private fun rollPage() {
            doc.finishPage(page)
            page = newPage()
        }
    }

    fun defaultFilename(prefix: String, timestamp: Long): String {
        return ExportFileNames.forRecord(prefix,timestamp,"pdf")
    }
}
