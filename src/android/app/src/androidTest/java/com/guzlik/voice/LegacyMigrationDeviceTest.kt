package com.guzlik.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Перенос записей из guzlikvoice_records.json (≤3.9) в Room — на НАСТОЯЩИХ данных.
 *
 * Запускается только если в filesDir приложения лежит JSON старой версии
 * (tools/device_test подкладывает туда копию данных 3.9). Сравнивает каждое
 * поле каждой записи до и после переноса, а не только их число.
 */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyJsonMigratesFieldByField() {
        val json = File(ctx.filesDir, "guzlikvoice_records.json")
        assumeTrue("нет JSON старой версии — тест пропущен", json.exists())
        val before = JsonParser.parseString(json.readText()).asJsonObject
        // Копия на случай, если перенос сломается и JSON удалится.
        json.copyTo(File(ctx.cacheDir, "guzlikvoice_records.before-migration.json"), overwrite = true)

        val expected = mutableMapOf<Long, Pair<String, JsonObject>>()
        for ((date, day) in before.entrySet()) {
            for (e in day.asJsonObject.getAsJsonArray("entries")) {
                val o = e.asJsonObject
                expected[o["timestamp"].asLong] = date to o
            }
        }

        val records = RecordStorage.getAllRecords(ctx)   // запускает перенос
        val actual = records.flatMap { r -> r.entries.map { r.date to it } }.associateBy { it.second.timestamp }

        assertEquals("число записей", expected.size, actual.size)
        val gson = Gson()
        fun str(o: JsonObject, k: String) = o[k]?.takeUnless { it.isJsonNull }?.asString ?: ""
        for ((ts, pair) in expected) {
            val (date, o) = pair
            val (aDate, a) = actual[ts] ?: error("запись $ts потерялась")
            assertEquals("дата $ts", date, aDate)
            assertEquals("текст $ts", str(o, "text"), a.text)
            assertEquals("structured $ts", str(o, "structured"), a.structured)
            assertEquals("audioPath $ts", str(o, "audioPath"), a.audioPath)
            assertEquals("source $ts", str(o, "source").ifEmpty { "voice" }, a.source)
            assertEquals("clientId $ts", o["clientId"]?.takeUnless { it.isJsonNull }?.asString, a.clientId)
            assertEquals("calendarEventId $ts",
                o["calendarEventId"]?.takeUnless { it.isJsonNull }?.asString, a.calendarEventId)
            val segBefore = o["segments"]?.takeUnless { it.isJsonNull }?.asJsonArray?.size() ?: 0
            assertEquals("сегменты $ts", segBefore, a.segments?.size ?: 0)
            o["segments"]?.takeUnless { it.isJsonNull }?.asJsonArray?.let { segs ->
                val back = JsonParser.parseString(gson.toJson(a.segments)).asJsonArray
                for (i in 0 until segs.size()) {
                    val s0 = segs[i].asJsonObject; val s1 = back[i].asJsonObject
                    assertEquals("сегмент $ts/$i текст", s0["text"]?.asString, s1["text"]?.asString)
                    assertEquals("сегмент $ts/$i start", s0["startMs"]?.asLong, s1["startMs"]?.asLong)
                }
            }
            if (a.audioPath.isNotEmpty()) {
                // Пути абсолютные; в копии данных под другим пакетом (.dev) ищем по имени файла.
                val f = File(a.audioPath).takeIf { it.exists() } ?: File(ctx.filesDir, File(a.audioPath).name)
                assertTrue("аудио $ts на месте: ${a.audioPath}", f.exists())
            }
        }
        for ((date, day) in before.entrySet()) {
            val summary = day.asJsonObject["daySummary"]?.takeUnless { it.isJsonNull }?.asString ?: ""
            if (summary.isNotBlank()) {
                assertEquals("итог дня $date", summary, records.first { it.date == date }.daySummary)
            }
        }
        assertFalse("JSON удалён после успешного переноса", json.exists())
    }
}
