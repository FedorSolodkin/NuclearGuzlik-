package com.example.guzlikvoice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * «Удалить все данные» (ТЗ проекта, П-9 и Д-3): одним действием стирает всё, что
 * пользователь записал и ввёл, — записи с аудио и защищённые копии, клиентов,
 * голосовые профили и отпечатки голосов, «Мой словарь», подключения календаря и
 * YouGile, черновики незавершённых записей, отчёты о сбоях, файлы экспорта.
 *
 * Не трогает скачанные модели (их можно скачать заново, но это гигабайты) и
 * настройки приложения (язык, качество, выбор модели).
 */
object DataWipe {
    private const val TAG = "DataWipe"

    data class Result(val records: Int)

    /** Блокирующе: звать не из UI-потока. */
    fun wipeAll(ctx: Context): Result = UserDataJobs.exclusiveWipe {
        val app = ctx.applicationContext

        // 1. Записи: deleteEntry удаляет и аудио, и зашифрованную копию, и отпечатки голосов записи.
        var records = 0
        for (day in RecordStorage.getAllRecords(app)) {
            for (e in day.entries) if (RecordStorage.deleteEntry(app, day.date, e.timestamp)) records++
        }

        // 2. Хранилища с кэшем в памяти — через их методы, иначе данные вернутся из кэша.
        ClientStorage.clearAll(app)
        VoiceProfileStorage.clearAll(app)
        SpeakerCentroidStorage.clearAll(app)
        DiarizationEvidenceCache.clearAll()
        DiarizationCheckpointStore.clear(app)
        CalendarEventCache.clear(app)

        // 3. Подключения и введённые пользователем данные.
        CalendarTokenStore.clear(app)
        YandexCaldavStore.clear(app)
        YouGile.disconnect(app)
        AppPrefs.setCustomDictionary(app, "")
        app.getSharedPreferences("wearable", Context.MODE_PRIVATE).edit().clear().commit()

        // 4. Файлы, которые остаются без записи: черновики, недоимпортированное, восстановленное
        //    из копии аудио, защищённые копии, отчёты о сбоях.
        val files = app.filesDir
        files.listFiles()?.forEach { f ->
            val n = f.name
            val userFile = n.startsWith("transcription_wal") || n.startsWith("import_") || n.startsWith("rec_") ||
                n.startsWith("wearable_") || n == "audio_restored" || n == "vault" || n == "crash"
            if (userFile && !f.deleteRecursively()) Log.w(TAG, "не удалось удалить $n")
        }
        // 5. Кэш: экспорт (Word/PDF/отчёты) и расшифрованное для проигрывания аудио.
        File(app.cacheDir, "exports").deleteRecursively()
        File(app.cacheDir, "vault_play").deleteRecursively()
        // Временные PCM разделения по голосам (сырой звук записи): остаются, если процесс убили посреди работы.
        app.cacheDir.listFiles { f -> f.name.startsWith("diarization-") && f.name.endsWith(".pcm") }
            ?.forEach { if (!it.delete()) Log.w(TAG, "не удалось удалить ${it.name}") }

        Log.i(TAG, "wiped: $records records")
        TextHandoff.clear()
        Result(records)
    }
}
