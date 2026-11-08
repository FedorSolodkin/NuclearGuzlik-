package com.guzlik.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Debug-замер полного пути импорта. `adb shell am broadcast -n <pkg>/com.guzlik.voice.BenchReceiver
 * -a com.guzlik.voice.BENCH --es id r001`, файл заранее лежит в filesDir/bench_in/<id>.wav.
 * Результат: filesDir/bench_out/<id>.json (формат экспорта), <id>.time.json (тайминги, пик RSS).
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("open", false)) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val id = intent.getStringExtra("id") ?: return
        val ctx = context.applicationContext
        Thread {
            try { run(ctx, id) } catch (e: Throwable) { Log.e("Bench", "failed $id", e) }
        }.start()
    }

    private fun peakRssKb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLongOrNull() ?: -1

    private fun run(ctx: Context, id: String) {
        val src = File(ctx.filesDir, "bench_in/$id.wav")
        val work = File(ctx.filesDir, "bench_work/$id.wav").also { it.parentFile!!.mkdirs() }
        src.copyTo(work, overwrite = true)
        val t0 = System.currentTimeMillis()
        Log.i("Bench", "START $id")
        AudioImporter.enqueueFile(ctx, work)
        var entry: DailyRecord.Entry? = null
        var tAsr = -1L
        var tDiar = -1L
        val deadline = t0 + 40 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
            if (entry == null) {
                entry = RecordStorage.getAllRecords(ctx).flatMap { d -> d.entries }
                    .firstOrNull { it.audioPath == work.absolutePath && it.timestamp >= t0 - 1000 }
                if (entry != null) { tAsr = System.currentTimeMillis() - t0; Log.i("Bench", "ASR done $id $tAsr ms") }
            } else {
                val snap = SpeakerCentroidStorage.get(ctx, entry.timestamp)
                val cur = RecordStorage.getAllRecords(ctx).flatMap { d -> d.entries }.firstOrNull { x -> x.timestamp == entry!!.timestamp }
                if (snap != null && snap.labels.isNotEmpty() && cur != null && (snap.labels.size == 1 || cur.text.contains("[Говорящий"))) { tDiar = System.currentTimeMillis() - t0; break }
            }
        }
        val out = File(ctx.filesDir, "bench_out").apply { mkdirs() }
        val e = entry?.let { en -> RecordStorage.getAllRecords(ctx).flatMap { d -> d.entries }.firstOrNull { x -> x.timestamp == en.timestamp } }
        var jsonOk = false
        var err = ""
        if (e != null && tDiar >= 0) {
            try {
                val labels = SpeakerCentroidStorage.get(ctx, e.timestamp)?.labels.orEmpty()
                File(out, "$id.json").writeText(JsonTranscriptExporter.export(e.text, e.segments.orEmpty(), labels), Charsets.UTF_8)
                File(out, "$id.txt").writeText(e.text, Charsets.UTF_8)
                jsonOk = true
            } catch (ex: Throwable) { err = ex.message ?: ex.toString() }
        }
        if (e != null) File(out, "$id.segments.json").writeText(com.google.gson.Gson().toJson(e.segments), Charsets.UTF_8)
        if (e != null) File(out, "$id.txt").writeText(e.text, Charsets.UTF_8)
        File(out, "$id.time.json").writeText(
            """{"id":"$id","t_asr_ms":$tAsr,"t_total_ms":$tDiar,"json_ok":$jsonOk,"peak_rss_kb":${peakRssKb()},"err":${com.google.gson.Gson().toJson(err)}}""")
        Log.i("Bench", "DONE $id asr=$tAsr total=$tDiar json=$jsonOk peakRssKb=${peakRssKb()} $err")
    }
}
