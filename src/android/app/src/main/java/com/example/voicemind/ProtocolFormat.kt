package com.example.voicemind

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** Markdown протокола (заголовки «## …», пункты «- …», задачи «- [ ] …») → текст для TextView. */
object ProtocolFormat {
    private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
    private val TASK = Regex("""^\s*[-*]\s*\[( |x|X)]\s*(.*)$""")
    private val BULLET = Regex("""^\s*[-*•]\s+(.*)$""")

    fun render(md: String): CharSequence {
        val out = SpannableStringBuilder()
        for (raw in md.trim().lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue          // отступы между разделами задаём сами
            HEADING.matchEntire(line)?.let { m ->
                if (out.isNotEmpty()) out.append("\n")
                val start = out.length
                out.append(m.groupValues[1].replace("**", ""))
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.12f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append("\n")
                return@let
            } ?: run {
                val text = TASK.matchEntire(line)?.let { (if (it.groupValues[1].isBlank()) "☐ " else "☑ ") + it.groupValues[2] }
                    ?: BULLET.matchEntire(line)?.let { "•  " + it.groupValues[1] }
                    ?: line
                out.append(text.replace("**", "")).append("\n")
            }
        }
        return out.trimEnd()
    }

    /** Простой текст без разметки — для «Копировать»/«Поделиться». */
    fun plain(md: String): String = render(md).toString()
}
