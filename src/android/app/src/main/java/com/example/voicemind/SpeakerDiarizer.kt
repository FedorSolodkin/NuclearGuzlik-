package com.example.voicemind

import android.content.Context
import android.util.Log
import kotlin.math.sqrt

object SpeakerDiarizer {

    private const val TAG = "SpeakerDiarizer"

    // Sliding window для Level 3 (Silero + фиксированные окна)
    private const val WIN_SAMPLES = 24_000   // 1.5 sec at 16kHz
    private const val HOP_SAMPLES = 8_000    // 0.5 sec hop

    // Level 4: минимум чистой речи одного локального голоса, суммарно по чанку.
    private const val MIN_REGION_PCM = 8_000  // 500 ms at 16kHz

    private val LABELS = listOf("А", "Б", "В", "Г", "Д", "Е", "Ж", "З")

    // Число говорящих, заданное пользователем для текущего вызова diarize (null — авто).
    // diarize вызывается последовательно (обработка записи / кнопка в карточке).
    @Volatile private var fixedK: Int? = null

    // Проверка отмены для текущего вызова diarize (кнопка «Отменить разделение»). Как и fixedK, задаётся на вызов.
    @Volatile private var cancelCheck: () -> Boolean = { false }
    private fun checkCancelled() { if (cancelCheck()) throw java.util.concurrent.CancellationException("cancelled") }

    /**
     * Результат диаризации.
     *  - text: транскрипт с маркерами [Говорящий X]
     *  - displayNames: имя каждого спикера (буква или имя из VoiceProfile при авто-матче)
     *  - centroids: L2-нормированный средний эмбеддинг каждого спикера
     *  - matchedProfileIds: id VoiceProfile-а если сматчился (для Pro)
     *  - previewOffsetsMs: миллисекунда в fullAudio, откуда играть 2-3 сек preview
     *    голоса этого спикера (для NameSpeakersSheet). Пусто если fallback без эмбеддингов.
     */
    data class Result(
        val text: String,
        val displayNames: List<String>,
        val centroids: List<FloatArray>,
        val matchedProfileIds: List<String?>,
        val previewOffsetsMs: LongArray = LongArray(0),
        /** Единицы разметки (слова или фразы) и номер говорящего для каждой — для оценки качества. */
        val units: List<WhisperHelper.Segment> = emptyList(),
        val speakers: IntArray = IntArray(0),
        /** Разметка до [TurnSnapper] (для сравнения на стенде). */
        val rawSpeakers: IntArray = IntArray(0),
        /** Acoustic speech intervals for DER evaluation; empty when unavailable. */
        val turns: List<SpeakerTimeline.Region> = emptyList(),
    )

    data class EmbeddingRegion(val startMs: Long, val endMs: Long, val embedding: Int, val window: Int, val previewMs: Long = startMs)
    /** Acoustic evidence, also used by encrypted resumable checkpoints. */
    data class EvidenceSnapshot(val embeddings: List<FloatArray>, val windows: List<SpeakerActivityFusion.Window>, val regions: List<EmbeddingRegion>,
        val boundaryRefinement: SpeakerBoundaryRefiner.Evidence? = null)

    data class EvidenceProcessing(val resume: EvidenceSnapshot? = null, val processedChunks: Int = 0,
        val workers: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3),
        val checkpoint: ((Int, Int, EvidenceSnapshot) -> Unit)? = null, val checkpointEveryChunks: Int = 10, val allowSpinning: Boolean? = null,
        val onnxThreads: Int? = null, val refineBoundaries: Boolean = true)

    // ── Entry point ───────────────────────────────────────────────────────────

    /** Backward-compat обёртка — возвращает только текст. */
    fun formatDialogue(
        segments:  List<WhisperHelper.Segment>,
        audioMap:  Map<Int, ShortArray>,
        embedder:  SpeakerEmbedder?,
        fullAudio: ShortArray = ShortArray(0),
        context:   Context?   = null,
    ): String = diarize(segments, audioMap, embedder, fullAudio, context).text

    /**
     * Основной метод.
     * Пробует по убыванию качества:
     *   Level 4: pyannote/segmentation-3.0 + WeSpeaker embeddings + AHC
     *   Level 3: Silero VAD + sliding windows + spectral clustering
     *   Level 2: per-segment embeddings + spectral clustering
     *   Fallback: preserve text without inventing speaker identities
     */
    @Synchronized
    fun diarize(
        segments:  List<WhisperHelper.Segment>,
        audioMap:  Map<Int, ShortArray>,
        embedder:  SpeakerEmbedder?,
        fullAudio: ShortArray = ShortArray(0),
        context:   Context?   = null,
        numSpeakers: Int?     = null,
        onProgress: ((Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        onEvidenceReady: ((EvidenceSnapshot) -> Unit)? = null,
        cachedEvidence: EvidenceSnapshot? = null,
        processing: EvidenceProcessing? = null,
    ): Result {
        return diarize(segments, audioMap, embedder, ArrayPcm(fullAudio), context, numSpeakers, onProgress, isCancelled, onEvidenceReady, cachedEvidence, processing)
    }

    @Synchronized
    fun diarize(
        segments:  List<WhisperHelper.Segment>,
        audioMap:  Map<Int, ShortArray>,
        embedder:  SpeakerEmbedder?,
        fullAudio: PcmAudio,
        context:   Context?   = null,
        numSpeakers: Int?     = null,
        onProgress: ((Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        onEvidenceReady: ((EvidenceSnapshot) -> Unit)? = null,
        cachedEvidence: EvidenceSnapshot? = null,
        processing: EvidenceProcessing? = null,
    ): Result {
        cancelCheck = isCancelled
        if (segments.isEmpty()) return Result("", emptyList(), emptyList(), emptyList())
        fixedK = numSpeakers?.takeIf { it in 1..LABELS.size }

        // Говорящий назначается каждому СЛОВУ, если у фраз есть пословные таймкоды:
        // фраза Whisper может длиться до ~30 с и захватывать смену собеседника,
        // а короткие «да»/«угу» внутри чужой фразы иначе терялись.
        val words = DiarizationUnits.expand(segments)
        var units: List<WhisperHelper.Segment> = segments

        var assignments: IntArray
        var frameAligned = false
        var acousticTurns = emptyList<SpeakerTimeline.Region>()
        val centroids: List<FloatArray>
        val previews:  LongArray

        // Level 4: pyannote (если модель есть) — самый качественный
        val segmenter = if (cachedEvidence == null) context?.let { PyannoteSegmenter(it).takeIf { s -> s.load(processing?.allowSpinning ?: DiarizationRuntimePolicy.spinning(it), processing?.onnxThreads ?: DiarizationRuntimePolicy.threads(it)) } } else null
        if (cachedEvidence != null) {
            units = words ?: segments
            val base=assignEvidence(units,cachedEvidence).copy(evidence=cachedEvidence)
            val refined=if(embedder?.isLoaded==true && fullAudio.size>16_000)
                refineAcoustic(units,base,fullAudio,embedder,processing,onProgress) else base
            refined.evidence?.let {onEvidenceReady?.invoke(it)}
            val (a, c, p, turns) = refined
            assignments = a; centroids = c; previews = p
            frameAligned = c.isNotEmpty(); acousticTurns = turns
        } else if (segmenter != null && embedder?.isLoaded == true && fullAudio.size > 16_000) {
            Log.i(TAG, "Level 4: pyannote-segmentation + WeSpeaker + AHC")
            units = words ?: segments
            val base = try {
                assignPyannote(units, fullAudio, embedder, segmenter,
                    onProgress = {pct->onProgress?.invoke(if(processing?.refineBoundaries==false)pct else pct*90/100)},processing = processing)
            } finally {
                segmenter.release()
            }
            val refined=refineAcoustic(units,base,fullAudio,embedder,processing,onProgress)
            refined.evidence?.let {onEvidenceReady?.invoke(it)}
            val (a,c,p,turns)=refined
            assignments = a; centroids = c; previews = p
            frameAligned = c.isNotEmpty()
            acousticTurns = turns
        } else {
            segmenter?.release()
            val vad = context?.let { SileroVAD(it).also { v -> v.load() } }
            when {
                embedder?.isLoaded == true && fullAudio.size > WIN_SAMPLES -> {
                    Log.i(TAG, "Level 3: spectral clustering with VAD (${if (vad?.isLoaded == true) "Silero" else "energy"})")
                    units = words ?: segments
                    val (a, c, p) = assignSpectralWithCentroids(units, fullAudio, embedder, vad, smooth = words == null)
                    assignments = a; centroids = c; previews = p
                }
                embedder?.isLoaded == true -> {
                    Log.i(TAG, "Level 2: per-segment AHC")
                    val perSegment = audioMap.ifEmpty { sliceSegments(segments, fullAudio) }
                    val (a, c, p) = assignByEmbeddingWithCentroids(segments, perSegment, embedder)
                    assignments = a; centroids = c; previews = p
                }
                else -> {
                    Log.i(TAG, "Fallback: no acoustic speaker evidence")
                    assignments = assignUnresolved(segments)
                    centroids = emptyList()
                    previews = LongArray(0)
                }
            }
            vad?.release()
        }

        // Пословная разметка: границы реплик — к паузам, мелкие перескоки — соседям.
        val rawAssignments = assignments
        // Frame-level segmentation already locates speaker changes acoustically.
        // Moving these boundaries to punctuation can erase genuine short replies.
        if (!frameAligned && words != null && units === words) assignments = TurnSnapper.snap(words, assignments)
        Log.i(TAG, "units per speaker before smoothing: ${rawAssignments.toList().groupingBy { it }.eachCount().toSortedMap()}, after: ${assignments.toList().groupingBy { it }.eachCount().toSortedMap()}")

        val numSpeakers = maxOf(centroids.size, (assignments.maxOrNull() ?: 0) + 1)
        val proContextAvailable = context != null && ProFeatures.canUse(context, ProFeatures.Feature.VOICE_PROFILES)

        val matchedIds  = MutableList<String?>(numSpeakers) { null }
        val displayNames = MutableList(numSpeakers) { i -> LABELS.getOrElse(i) { (i + 1).toString() } }

        if (proContextAvailable && centroids.size == numSpeakers) {
            for (i in 0 until numSpeakers) {
                val c = centroids[i]
                if (c.isEmpty()) continue
                val match = VoiceProfileStorage.matchBest(context!!, c)
                if (match != null && match.second >= VoiceProfileStorage.MATCH_THRESHOLD_CONFIRM) {
                    displayNames[i]  = match.first.name
                    matchedIds[i]    = match.first.id
                    Log.i(TAG, "Speaker $i → ${match.first.name} (sim=${"%.3f".format(match.second)})")
                }
            }
        }

        val text = buildTextWithNames(units, assignments, displayNames)
        return Result(text, displayNames, centroids, matchedIds, previews, units, assignments, rawAssignments, acousticTurns)
    }

    // ── Level 4: pyannote-style pipeline ─────────────────────────────────────

    /**
     * Прогоняет pyannote по 10-сек чанкам с 5-сек хопом, извлекает single-speaker
     * регионы → объединение чистой речи локального спикера → WeSpeaker → AHC.
     * Кросс-чанк идентичность спикеров устанавливается через кластеризацию, а не
     * через локальные ID модели.
     */
    private data class AcousticResult(val assignments: IntArray, val centroids: List<FloatArray>, val previews: LongArray,
        val turns: List<SpeakerTimeline.Region>, val evidence:EvidenceSnapshot?=null)

    private fun refineAcoustic(segments:List<WhisperHelper.Segment>,base:AcousticResult,audio:PcmAudio,embedder:SpeakerEmbedder,
                               processing:EvidenceProcessing?,onProgress:((Int)->Unit)?):AcousticResult {
        val snapshot=base.evidence ?: return base
        if(processing?.refineBoundaries==false || snapshot.boundaryRefinement?.completed==true || base.centroids.isEmpty())return base
        val started=System.nanoTime()
        var lastPercent=89
        val refinement=SpeakerBoundaryRefiner.capture(snapshot,base.centroids,audio,embedder::embed,
            workers=processing?.workers ?: DiarizationRuntimePolicy.workers(null),checkCancelled=::checkCancelled,
            onProgress={done,total->
                val percent=90+done*10/total.coerceAtLeast(1)
                if(percent>lastPercent){lastPercent=percent;onProgress?.invoke(percent)}
            },
            checkpoint={partial->processing?.checkpoint?.invoke(snapshot.windows.size,snapshot.windows.size,snapshot.copy(boundaryRefinement=partial))})
        val refined=snapshot.copy(boundaryRefinement=refinement)
        Log.i(TAG,"Boundary refinement: ${refinement.observations.size} short windows in ${(System.nanoTime()-started)/1_000_000} ms")
        if(lastPercent<100)onProgress?.invoke(100)
        return assignEvidence(segments,refined).copy(evidence=refined)
    }

    private fun assignPyannote(
        segments:  List<WhisperHelper.Segment>,
        fullAudio: PcmAudio,
        embedder:  SpeakerEmbedder,
        segmenter: PyannoteSegmenter,
        onProgress: ((Int) -> Unit)? = null,
        processing: EvidenceProcessing? = null,
    ): AcousticResult {
        data class Region(val startMs: Long, val endMs: Long, val emb: FloatArray, val previewMs: Long, val chunkStart: Int)
        val regions = mutableListOf<Region>()

        val chunkSamples = PyannoteSegmenter.CHUNK_SAMPLES
        val hopSamples   = PyannoteSegmenter.HOP_SAMPLES
        val audioMsMax   = fullAudio.size * 1000L / PyannoteSegmenter.SAMPLE_RATE

        // Время, потраченное на модель кусков и на модель голосов (сумма по потокам), для журнала.
        val segNanos = java.util.concurrent.atomic.AtomicLong()
        val embNanos = java.util.concurrent.atomic.AtomicLong()

        /** Один 10-секундный кусок: разметка pyannote, затем отпечаток голоса для каждого места, где говорит один человек. */
        fun processChunk(chunkStart: Int): Pair<Boolean, List<Region>> {
            checkCancelled()
            // Собираем чанк 10 сек, паддингом до полного размера при необходимости.
            val chunkEndSample = minOf(chunkStart.toLong() + chunkSamples, fullAudio.size.toLong()).toInt()
            val chunkPcm = fullAudio.copyOfRange(chunkStart, chunkEndSample)
            val chunkFloat = FloatArray(chunkSamples)
            for (i in chunkPcm.indices) chunkFloat[i] = chunkPcm[i] / 32768f
            // Хвост уже 0f — тишина, модель сама разметит как silence.

            var t0 = System.nanoTime()
            val logits = segmenter.segmentChunk(chunkFloat)
            segNanos.addAndGet(System.nanoTime() - t0)
            if (logits == null) return false to emptyList()

            // Local segmentation can reuse an identity for similar sequential voices.
            // Keep long turns independently embedded; pool only sub-minimum snippets.
            val runs = SegmentationSpeech.runs(logits, chunkSamples, chunkEndSample - chunkStart)
            val activity = SegmentationSpeech.activityRuns(logits, chunkSamples, chunkEndSample - chunkStart)
            val anchors = mutableListOf<Pair<SegmentationSpeech.Run, FloatArray>>()
            fun embed(clean: ShortArray): FloatArray? {
                checkCancelled()
                val start = System.nanoTime()
                return embedder.embed(clean).also { embNanos.addAndGet(System.nanoTime() - start) }
            }
            for (run in runs) if (run.endSample - run.startSample >= MIN_REGION_PCM) {
                embed(chunkPcm.copyOfRange(run.startSample, run.endSample))?.let { anchors.add(run to it) }
            }
            val shortRuns = runs.filter { it.endSample - it.startSample < MIN_REGION_PCM }
            for (speaker in shortRuns.map { it.speaker }.distinct()) {
                val clean = SegmentationSpeech.samples(chunkPcm, shortRuns, speaker)
                val pooled = if (clean.size >= MIN_REGION_PCM) embed(clean) else null
                if (pooled != null) shortRuns.filter { it.speaker == speaker }.forEach { anchors.add(it to pooled) }
            }
            val found = mutableListOf<Region>()
            for ((run, anchorIndex) in SegmentationSpeech.anchorActivity(activity, anchors.map { it.first })) {
                val (anchor, emb) = anchors[anchorIndex]
                val startMs = (chunkStart.toLong() + run.startSample) * 1000 / PyannoteSegmenter.SAMPLE_RATE
                val endMs = (chunkStart.toLong() + run.endSample) * 1000 / PyannoteSegmenter.SAMPLE_RATE
                val previewMs = (chunkStart.toLong() + anchor.startSample) * 1000 / PyannoteSegmenter.SAMPLE_RATE
                found.add(Region(startMs, endMs.coerceAtMost(audioMsMax), emb, previewMs, chunkStart))
            }
            return true to found
        }

        val starts = generateSequence(0L) { it + hopSamples }.takeWhile { it < fullAudio.size }.map { it.toInt() }.toList()

        // Куски независимы друг от друга, а на телефоне 8 ядер: раньше они шли строго по одному на двух потоках.
        // Результаты собираем в порядке кусков, поэтому итог тот же, что при последовательной обработке.
        // Сессии ONNX Runtime допускают одновременные вызовы.
        val workers = (processing?.workers ?: (Runtime.getRuntime().availableProcessors() / 2)).coerceIn(1, 3)
        val t0Wall = System.nanoTime()
        val ws = starts.map { start -> SpeakerActivityFusion.Window(start * 1000L / PyannoteSegmenter.SAMPLE_RATE,
            minOf(start.toLong() + chunkSamples, fullAudio.size.toLong()) * 1000 / PyannoteSegmenter.SAMPLE_RATE) }
        val resumed = processing?.processedChunks ?: 0
        require(resumed in 0..starts.size && (resumed == 0 || processing?.resume != null))
        processing?.resume?.let { previous ->
            require(previous.windows == ws)
            for (r in previous.regions) {
                require(r.window in 0 until resumed && r.embedding in previous.embeddings.indices)
                regions.add(Region(r.startMs, r.endMs, previous.embeddings[r.embedding], r.previewMs, starts[r.window]))
            }
        }
        fun snapshot(): EvidenceSnapshot {
            val embeddings = regions.map { it.emb }.distinct()
            val embeddingIndices = embeddings.withIndex().associate { it.value to it.index }
            val wi = starts.withIndex().associate { it.value to it.index }
            return EvidenceSnapshot(embeddings, ws, regions.map { r ->
                EmbeddingRegion(r.startMs, r.endMs, embeddingIndices.getValue(r.emb), wi.getValue(r.chunkStart), r.previewMs)
            })
        }
        var chunksProcessed = resumed
        val doneChunks = java.util.concurrent.atomic.AtomicInteger(resumed)
        if (resumed == 0) processing?.checkpoint?.invoke(0, starts.size, snapshot())
        val lastPercent = java.util.concurrent.atomic.AtomicInteger(-1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
        try {
            // Keep only a small number of in-flight results. A completed Future retains
            // every region/embedding until consumed; enqueueing an entire recording
            // previously held all completed chunks a second time.
            val pending = java.util.ArrayDeque<java.util.concurrent.Future<Pair<Boolean, List<Region>>>>()
            val iterator = starts.drop(resumed).iterator()
            fun submitNext() {
                if (!iterator.hasNext()) return
                val cs = iterator.next()
                pending.addLast(pool.submit(java.util.concurrent.Callable {
                    val r = processChunk(cs)
                    synchronized(lastPercent) {
                        val pct = doneChunks.incrementAndGet() * 100 / starts.size
                        if (onProgress != null && pct > lastPercent.get()) {
                            lastPercent.set(pct); onProgress(pct)
                        }
                    }
                    r
                }))
            }
            repeat(workers * 2) { submitNext() }
            while (pending.isNotEmpty()) {
                checkCancelled()
                val f = pending.removeFirst()
                val (ok, found) = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
                check(ok) { "Не удалось обработать фрагмент аудио. Прогресс сохранён — повторите разделение" }
                chunksProcessed++
                regions.addAll(found)
                if (chunksProcessed % (processing?.checkpointEveryChunks ?: 10).coerceAtLeast(1) == 0 || chunksProcessed == starts.size) {
                    checkCancelled()
                    processing?.checkpoint?.invoke(chunksProcessed, starts.size, snapshot())
                }
                submitNext()
            }
        } finally {
            pool.shutdownNow()
            while (!pool.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS)) Unit
        }
        Log.i(TAG, "pyannote: ${starts.size} chunks, $workers workers, wall %.1f s (model of chunks %.1f s + voice model %.1f s, sum over threads)"
            .format((System.nanoTime() - t0Wall) / 1e9, segNanos.get() / 1e9, embNanos.get() / 1e9))
        Log.i(TAG, "pyannote: $chunksProcessed chunks → ${regions.size} single-speaker regions")

        if (regions.isEmpty()) {
            Log.w(TAG, "pyannote: no regions extracted, unresolved fallback")
            return AcousticResult(assignUnresolved(segments), emptyList(), LongArray(0), emptyList())
        }

        val snapshot = snapshot()
        checkCancelled()
        return assignEvidence(segments, snapshot).copy(evidence=snapshot)
    }

    private fun assignEvidence(segments: List<WhisperHelper.Segment>, snapshot: EvidenceSnapshot): AcousticResult {
        checkCancelled()
        val embeddings = snapshot.embeddings
        if (embeddings.isEmpty() || snapshot.regions.isEmpty())
            return AcousticResult(assignUnresolved(segments), emptyList(), LongArray(0), emptyList())
        val tCluster = System.nanoTime()
        val anchors = snapshot.regions.groupBy { it.embedding }
        val times = java.util.IdentityHashMap<FloatArray, LongArray>()
        val spans=java.util.IdentityHashMap<FloatArray,List<RobustSpeakerClustering.SpeechSpan>>()
        // Old exported evidence did not contain previewMs; Gson fills a missing
        // primitive with zero instead of using the Kotlin constructor default.
        embeddings.forEachIndexed { i, e -> times[e] = anchors[i].orEmpty().map {
            if(it.previewMs>0)it.previewMs else it.startMs
        }.distinct().toLongArray()
            spans[e]=anchors[i].orEmpty().map{RobustSpeakerClustering.SpeechSpan(it.startMs,it.endMs)}
        }
        val labels = BoundedSpeakerClustering.cluster(embeddings, 1024, ::checkCancelled) { sample ->
            Log.i(TAG, "Clustering sample ready in ${(System.nanoTime()-tCluster)/1_000_000} ms")
            RobustSpeakerClustering.clusterSupported(sample, sample.map { times[it] ?: LongArray(0) }, fixedK, ::checkCancelled,
                sample.map{spans[it].orEmpty()})
        }
        val tFusion = System.nanoTime()
        val k = (labels.maxOrNull() ?: 0) + 1
        val centroids = (0 until k).map { group -> averageAndNormalize(embeddings.filterIndexed { i, _ -> labels[i] == group }) }
        val reliable = embeddings.indices.map { i ->
            val c = centroids[labels[i]]
            embeddings[i].indices.sumOf { d -> embeddings[i][d].toDouble()*c[d] } >= 0.55
        }
        val baseTurns = SpeakerActivityFusion.fuse(snapshot.windows, snapshot.regions.filter { reliable[it.embedding] }.map { r ->
            SpeakerActivityFusion.Evidence(r.startMs, r.endMs, labels[r.embedding], r.window)
        }, ::checkCancelled)
        val turns = snapshot.boundaryRefinement?.takeIf {it.completed}?.let {
            SpeakerBoundaryRefiner.refine(baseTurns,it.observations,centroids,::checkCancelled)
        } ?: baseTurns
        if(turns.isEmpty())return AcousticResult(assignUnresolved(segments),emptyList(),LongArray(0),emptyList())
        val previews=SpeakerPreviews.select(snapshot,labels,centroids,turns)
        val tWords = System.nanoTime()
        val timeline = SpeakerTimeline(turns)
        val assignments = IntArray(segments.size) { i -> checkCancelled(); timeline.decide(segments[i].startMs, segments[i].endMs).speaker }
        Log.i(TAG, "Evidence stages ms: clustering=${(tFusion-tCluster)/1_000_000}, fusion=${(tWords-tFusion)/1_000_000}, words=${(System.nanoTime()-tWords)/1_000_000}")
        Log.i(TAG, "Clustering ${embeddings.size} embeddings → $k voices (requested ${fixedK ?: "auto"})")
        return AcousticResult(assignments, centroids, previews, turns)
    }

    // ── Level 3: VAD → sliding windows → spectral clustering ─────────────────

    /** Возвращает (assignments, centroids-per-cluster, previews-per-cluster). */
    private fun assignSpectralWithCentroids(
        segments:  List<WhisperHelper.Segment>,
        fullAudio: PcmAudio,
        embedder:  SpeakerEmbedder,
        vad:       SileroVAD?,
        smooth:    Boolean = true,
    ): Triple<IntArray, List<FloatArray>, LongArray> {
        val outWindows = mutableListOf<Triple<FloatArray, Int, Long>>()  // (embedding, cluster, startMs)
        val assignments = assignSpectral(segments, fullAudio, embedder, vad, outWindows, smooth)
        if (outWindows.isEmpty()) return Triple(assignments, emptyList(), LongArray(0))
        val numSpeakers = (assignments.maxOrNull() ?: 0) + 1
        val centroids = (0 until numSpeakers).map { k ->
            averageAndNormalize(outWindows.filter { it.second == k }.map { it.first })
        }
        val previews = LongArray(numSpeakers) { k ->
            val starts = outWindows.filter { it.second == k }.map { it.third }.sorted()
            if (starts.isEmpty()) 0L else starts[starts.size / 2]
        }
        return Triple(assignments, centroids, previews)
    }

    private fun assignSpectral(
        segments:  List<WhisperHelper.Segment>,
        fullAudio: PcmAudio,
        embedder:  SpeakerEmbedder,
        vad:       SileroVAD?,
        capturedWindows: MutableList<Triple<FloatArray, Int, Long>>? = null,
        smooth: Boolean = true,
    ): IntArray {
        val sampleRate = 16_000L

        // 1. Speech regions (Silero VAD или энергия)
        val speechRegions: List<Pair<Long, Long>> = if (vad?.isLoaded == true) {
            val r = vad.detectSpeech(fullAudio, ::checkCancelled)
            if (r.isEmpty()) {
                Log.w(TAG, "Silero VAD found no speech regions, falling back to energy")
                energySpeechRegions(fullAudio)
            } else r
        } else {
            energySpeechRegions(fullAudio)
        }

        // 2. Sliding windows в участках речи
        data class Window(val startMs: Long, val endMs: Long, val emb: FloatArray)
        val windows = mutableListOf<Window>()

        Log.i(TAG, "fullAudio=${fullAudio.size} samples (${fullAudio.size / sampleRate}s), speechRegions=${speechRegions.size}")

        var pos = 0
        while (pos + WIN_SAMPLES <= fullAudio.size) {
            checkCancelled()
            val startMs = pos * 1000L / sampleRate
            val endMs   = (pos + WIN_SAMPLES) * 1000L / sampleRate
            val inSpeech = speechRegions.any { (s, e) -> minOf(endMs, e) > maxOf(startMs, s) }
            if (inSpeech) {
                val slice = fullAudio.copyOfRange(pos, pos + WIN_SAMPLES)
                val emb   = embedder.embed(slice)
                if (emb != null) windows.add(Window(startMs, endMs, emb))
            }
            pos += HOP_SAMPLES
        }

        if (windows.isEmpty()) {
            Log.w(TAG, "No valid windows — unresolved fallback")
            return assignUnresolved(segments)
        }

        // 3. Spectral clustering
        val winClusters = BoundedSpeakerClustering.cluster(windows.map { it.emb }, 256, ::checkCancelled) {
            SpectralClusterer.cluster(it, fixedK, ::checkCancelled)
        }
        val numSpeakers = (winClusters.maxOrNull() ?: 0) + 1
        Log.i(TAG, "Spectral → $numSpeakers speaker(s)")

        val n = segments.size
        val timeline = SpeakerTimeline(windows.mapIndexed { i, w -> SpeakerTimeline.Region(w.startMs, w.endMs, winClusters[i]) })
        val assignments = IntArray(n) { i ->
            checkCancelled()
            timeline.decide(segments[i].startMs, segments[i].endMs).speaker
        }

        if (smooth && n >= 10) temporalSmooth(assignments, n)

        if (capturedWindows != null) {
            for ((wi, w) in windows.withIndex()) capturedWindows.add(Triple(w.emb, winClusters[wi], w.startMs))
        }
        return assignments
    }

    // ── Energy-based VAD (fallback когда Silero не загружен) ─────────────────

    private fun energySpeechRegions(pcm: PcmAudio): List<Pair<Long, Long>> {
        val chunkSize = 512
        val sampleRate = 16_000L
        val threshold  = 150f
        val regions    = mutableListOf<Pair<Long, Long>>()
        var inSpeech   = false; var start = 0L
        var pos = 0
        while (pos + chunkSize <= pcm.size) {
            checkCancelled()
            var sum = 0.0
            for (i in pos until pos + chunkSize) sum += pcm[i].toDouble() * pcm[i]
            val rms = sqrt(sum / chunkSize).toFloat()
            val ms  = pos * 1000L / sampleRate
            if (rms > threshold) {
                if (!inSpeech) { inSpeech = true; start = ms }
            } else if (inSpeech) {
                if (ms - start > 200) regions += Pair(start, ms)
                inSpeech = false
            }
            pos += chunkSize
        }
        if (inSpeech) regions += Pair(start, pcm.size * 1000L / sampleRate)
        return regions
    }

    // ── Level 2: per-segment embedding + spectral clustering ─────────────────

    /** Аудио каждой фразы из полной записи (таймкоды — в её шкале). */
    fun sliceSegments(segments: List<WhisperHelper.Segment>, pcm: ShortArray): Map<Int, ShortArray> =
        sliceSegments(segments, ArrayPcm(pcm))

    private fun sliceSegments(segments: List<WhisperHelper.Segment>, pcm: PcmAudio): Map<Int, ShortArray> {
        if (pcm.isEmpty()) return emptyMap()
        return segments.mapIndexed { i, seg ->
            val start = (seg.startMs * 16_000L / 1000L).toInt().coerceIn(0, pcm.size)
            val end   = (seg.endMs   * 16_000L / 1000L).toInt().coerceIn(0, pcm.size)
            i to if (end > start) pcm.copyOfRange(start, end) else ShortArray(0)
        }.toMap()
    }

    private fun assignByEmbeddingWithCentroids(
        segments: List<WhisperHelper.Segment>,
        audioMap: Map<Int, ShortArray>,
        embedder: SpeakerEmbedder,
    ): Triple<IntArray, List<FloatArray>, LongArray> {
        val n = segments.size
        val embs = Array<FloatArray?>(n) { i -> checkCancelled(); audioMap[i]?.let { embedder.embed(it) } }
        val valid = embs.filterNotNull()
        if (valid.isEmpty()) return Triple(assignUnresolved(segments), emptyList(), LongArray(0))

        val clusters = BoundedSpeakerClustering.cluster(valid, 256, ::checkCancelled) { SpectralClusterer.cluster(it, fixedK, ::checkCancelled) }
        val assignment = IntArray(n)
        var vi = 0
        for (i in 0 until n) {
            assignment[i] = if (embs[i] != null) clusters[vi++] else {
                val prev = (i - 1 downTo 0).firstOrNull { embs[it] != null }
                val next = (i + 1 until n).firstOrNull { embs[it] != null }
                when {
                    prev != null -> assignment[prev]
                    next != null -> assignment[next]
                    else -> 0
                }
            }
        }
        if (n >= 10) temporalSmooth(assignment, n)

        val numSpeakers = (assignment.maxOrNull() ?: 0) + 1
        val centroids = (0 until numSpeakers).map { k ->
            val chosen = mutableListOf<FloatArray>()
            var ci = 0
            for (i in 0 until n) if (embs[i] != null) {
                if (clusters[ci] == k) chosen.add(embs[i]!!)
                ci++
            }
            averageAndNormalize(chosen)
        }
        val previews = LongArray(numSpeakers) { k ->
            val ms = mutableListOf<Long>()
            for (i in 0 until n) if (assignment[i] == k) ms.add(segments[i].startMs)
            if (ms.isEmpty()) 0L else ms.sorted()[ms.size / 2]
        }
        return Triple(assignment, centroids, previews)
    }

    // Without voice evidence, pauses cannot establish speaker identity.
    private fun assignUnresolved(segments: List<WhisperHelper.Segment>): IntArray = IntArray(segments.size)

    // ── Temporal smoothing ────────────────────────────────────────────────────

    private fun temporalSmooth(cluster: IntArray, n: Int, window: Int = 2) {
        val maxIter = 32
        var iter = 0
        var changed = true
        while (changed && iter < maxIter) {
            changed = false
            iter++
            for (i in 1 until n - 1) {
                val left  = (maxOf(0, i - window) until i).map { cluster[it] }
                val right = (i + 1..minOf(n - 1, i + window)).map { cluster[it] }
                if (left.isEmpty() || right.isEmpty()) continue
                val lm = left.groupingBy  { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
                val rm = right.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
                if (lm == rm && cluster[i] != lm) { cluster[i] = lm; changed = true }
            }
        }
    }

    // ── Output ────────────────────────────────────────────────────────────────

    private fun buildTextWithNames(
        segments:    List<WhisperHelper.Segment>,
        assignments: IntArray,
        names:       List<String>
    ): String {
        val numSpeakers = (assignments.maxOrNull() ?: 0) + 1
        if (numSpeakers == 1 && assignments.all { it == 0 }) return segments.joinToString(" ") { it.text.trim() }.trim()
        data class Block(val speaker: Int, val lines: MutableList<String> = mutableListOf())
        val blocks = mutableListOf<Block>(); var prev: Int? = null
        for ((i, seg) in segments.withIndex()) {
            val spk = assignments[i]
            if (spk != prev) { blocks += Block(spk); prev = spk }
            val t = seg.text.trim()
            if (t.isNotEmpty()) blocks.last().lines += t
        }
        return blocks.filter { it.lines.isNotEmpty() }.joinToString("\n\n") { b ->
            val label = names.getOrElse(b.speaker) { LABELS.getOrElse(b.speaker) { (b.speaker + 1).toString() } }
            val header = when (b.speaker) {
                SpeakerTimeline.UNKNOWN -> "[Говорящий не определён]"
                SpeakerTimeline.OVERLAP -> "[Говорят одновременно]"
                else -> "[Говорящий $label]"
            }
            "$header\n${b.lines.joinToString(" ")}"
        }
    }

    /** L2-нормализованное среднее списка эмбеддингов. Пустой FloatArray если вход пуст. */
    private fun averageAndNormalize(embs: List<FloatArray>): FloatArray {
        if (embs.isEmpty()) return FloatArray(0)
        val dim = embs[0].size
        val avg = FloatArray(dim)
        for (e in embs) if (e.size == dim) for (i in 0 until dim) avg[i] += e[i]
        val n = embs.size.toFloat()
        for (i in 0 until dim) avg[i] /= n
        var norm = 0f
        for (v in avg) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in avg.indices) avg[i] /= norm
        return avg
    }
}
