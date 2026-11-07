package com.example.voicemind

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Копирование и «Поделиться» для текста записи/анализа. */
object TextShare {
    fun copy(ctx: Context, label: String, text: String) {
        if (text.isBlank()) return
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun share(ctx: Context, subject: String, chooserTitle: String, text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        ctx.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
