package com.guzlik.voice

import android.content.Context
import java.io.File

// Durable journal removal happens off the UI thread.
@android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
object WearableRecovery {
    private var recovered = false
    /** Only invoked at process startup, before a new recording can own the pending slot. */
    @Synchronized fun recover(context: Context, force: Boolean = false) {
        if (recovered && !force) return
        val lease = UserDataJobs.begin() ?: return
        try {
            val prefs = context.getSharedPreferences("wearable", Context.MODE_PRIVATE)
            val path = prefs.getString("pending_path", null) ?: run { recovered = true; return }
            val id = prefs.getLong("pending_entry", 0)
            val file = File(path)
            lease.check()
            if (file.canonicalFile.parentFile == context.filesDir.canonicalFile && file.name.startsWith("wearable_") && file.exists()) {
                WearableWavWriter.recover(file)
                RecordStorage.saveWearableTranscript(context, id,
                    "Запись с внешнего микрофона. Обработка ещё не завершена.",
                    "Запись прервана при закрытии приложения. Полученное аудио сохранено; конец может быть неполным.", emptyList())
            }
            prefs.edit().remove("pending_path").remove("pending_entry").commit()
            recovered = true
        } finally { lease.close() }
    }
}
