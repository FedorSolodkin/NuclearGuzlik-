package com.guzlik.voice

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Точка входа «Поделиться → GuzlikVoice» / «Открыть с помощью» для аудиофайлов.
 * Без своего экрана: забирает файл, ставит в очередь [AudioImporter] и открывает
 * приложение (экран записи поднимает движок распознавания).
 */
class ImportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccessGate.check(this)) return
        // Повторный onCreate после поворота экрана — файл уже принят, второй раз не импортируем.
        if (savedInstanceState != null) return
        // Только content:// от чужого приложения. file:// пускать нельзя: отправитель
        // может подсунуть путь к нашим же приватным файлам (filesDir), и мы бы
        // прочитали их своими правами. Свой FileProvider тоже отсекаем.
        val uris = extractUris(intent).filter(::isAcceptableUri)
        // Копируем, пока активность жива: право на чтение чужого uri выдаётся
        // на время её жизни и может пропасть сразу после finish().
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                for (uri in uris) AudioImporter.importUri(this@ImportActivity, uri)
            }
            // NEW_TASK: ImportActivity живёт в задаче отправителя (Telegram). Без этого флага MainActivity открывалась
            // внутри задачи Telegram как «второе окно» приложения, а своя уже запущенная оставалась рядом.
            startActivity(Intent(this@ImportActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    private fun isAcceptableUri(uri: Uri): Boolean =
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) &&
            uri.authority != null && !uri.authority!!.startsWith(packageName)

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                 else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
    }
}
