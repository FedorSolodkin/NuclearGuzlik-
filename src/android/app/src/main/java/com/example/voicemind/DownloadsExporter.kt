package com.example.voicemind

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

// Сохраняет уже сгенерированный файл в системную папку "Загрузки".
// На API 29+ через MediaStore (без разрешения WRITE_EXTERNAL_STORAGE),
// на более ранних — напрямую в Environment.DIRECTORY_DOWNLOADS.
//
// Возвращает пару (uri для просмотра/шаринга через ACTION_VIEW, отображаемое имя)
// или null при ошибке.
object DownloadsExporter {

    fun saveToDownloads(ctx: Context, source: File, mimeType: String): Uri? {
        if (!source.exists()) return null
        var pending: Uri? = null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                pending = uri
                checkNotNull(resolver.openOutputStream(uri)) { "Не удалось открыть файл в Загрузках" }.use { out ->
                    FileInputStream(source).use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) > 0) { "Не удалось завершить сохранение" }
                pending = null
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val dest = File(downloadsDir, source.name)
                source.copyTo(dest, overwrite = true)
                // На API < 29 возвращаем FileProvider Uri — на этих версиях всё равно
                // нужно временное разрешение для шаринга.
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest)
            }
        } catch (_: Exception) {
            pending?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            null
        }
    }

    // Создаёт intent для шаринга ранее сохранённого файла (из cacheDir через FileProvider).
    fun buildShareIntent(fileUri: Uri, mimeType: String, subject: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
