package com.guzlik.voice

/** Short-window second pass over ambiguous long evidence, keeping the global voice inventory fixed. */
object SpeakerBoundaryRefiner {
    data class Span(val startMs: Long, val endMs: Long)
    data class Observation(val startMs: Long, val endMs: Long, val embedding: FloatArray)
    data class Evidence(val observations: List<Observation>, val completed: Boolean)
    const val WINDOW_MS = 2_000L
    const val HOP_MS = 500L

    fun plan(evidence: SpeakerDiarizer.EvidenceSnapshot, centers: List<FloatArray>, checkCancelled: () -> Unit = {}): List<Span> {
        if (centers.size < 2) return emptyList()
        val duration = evidence.windows.maxOfOrNull { it.endMs } ?: return emptyList()
        val uncertain = BooleanArray(evidence.embeddings.size) { i ->
            checkCancelled()
            val scores = centers.map { cosine(evidence.embeddings[i],it) }.sortedDescending()
            scores[0] >= .5 && scores[1] >= .4 && scores[0]-scores[1] < .15
        }
        val spans = evidence.regions.filter { it.endMs-it.startMs >= 2_500 && uncertain[it.embedding] }
            .map { Span((it.startMs/HOP_MS*HOP_MS-HOP_MS).coerceAtLeast(0),
                ((it.endMs+HOP_MS-1)/HOP_MS*HOP_MS+HOP_MS).coerceAtMost(duration)) }
            .sortedBy { it.startMs }
        val merged = mutableListOf<Span>()
        for (span in spans) {
            val last=merged.lastOrNull()
            if(last!=null && span.startMs<=last.endMs+HOP_MS) merged[merged.lastIndex]=last.copy(endMs=maxOf(last.endMs,span.endMs))
            else merged.add(span)
        }
        return merged
    }

    fun windows(spans: List<Span>): List<Span> = spans.flatMap { span ->
        if(span.endMs-span.startMs<WINDOW_MS) emptyList()
        else generateSequence(span.startMs){it+HOP_MS}.takeWhile {it+WINDOW_MS<=span.endMs}.map {Span(it,it+WINDOW_MS)}.toList()
    }

    fun capture(snapshot: SpeakerDiarizer.EvidenceSnapshot, centers: List<FloatArray>, audio: PcmAudio,
                embed: (ShortArray) -> FloatArray?, workers: Int = 3,
                checkCancelled: () -> Unit = {}, onProgress: (Int,Int) -> Unit = {_,_->},
                checkpointEveryObservations: Int = 200, checkpoint: (Evidence) -> Unit = {}): Evidence {
        snapshot.boundaryRefinement?.takeIf{it.completed}?.let{return it}
        val planned=windows(plan(snapshot,centers,checkCancelled))
        val existing=snapshot.boundaryRefinement?.observations.orEmpty()
        val observations=mutableListOf<Observation>()
        for((index,o) in existing.withIndex()){
            if(index>=planned.size || o.startMs!=planned[index].startMs || o.endMs!=planned[index].endMs)break
            observations.add(o)
        }
        val resume=observations.size
        checkpoint(Evidence(observations.toList(),false))
        if(planned.isEmpty())return Evidence(emptyList(),true).also(checkpoint)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(workers.coerceIn(1,3))
        try {
            val iterator=planned.drop(resume).iterator()
            val pending=java.util.ArrayDeque<java.util.concurrent.Future<Observation>>()
            fun submit() {
                if(!iterator.hasNext())return
                val span=iterator.next()
                pending.add(pool.submit(java.util.concurrent.Callable {
                    checkCancelled()
                    val pcm=audio.copyOfRange((span.startMs*16).toInt(),(span.endMs*16).toInt())
                    val vector=checkNotNull(embed(pcm)){"Не удалось уточнить голоса. Прогресс сохранён"}
                    checkCancelled()
                    Observation(span.startMs,span.endMs,vector)
                }))
            }
            repeat(workers.coerceIn(1,3)*2){submit()}
            while(pending.isNotEmpty()){
                checkCancelled()
                val observation=try{pending.removeFirst().get()}catch(e:java.util.concurrent.ExecutionException){throw(e.cause ?: e)}
                observations.add(observation)
                onProgress(observations.size,planned.size)
                if(observations.size%checkpointEveryObservations.coerceAtLeast(1)==0)checkpoint(Evidence(observations.toList(),false))
                submit()
            }
            checkCancelled()
            return Evidence(observations.toList(),true).also(checkpoint)
        } finally {
            pool.shutdownNow()
            while(!pool.awaitTermination(100,java.util.concurrent.TimeUnit.MILLISECONDS))Unit
        }
    }

    private fun cosine(a: FloatArray,b: FloatArray): Double = a.indices.sumOf {a[it].toDouble()*b[it]}
    private data class Cell(val start:Long,val end:Long,val original:Int,val chosen:Int,val confirmed:Boolean)

    /** Refine identities only inside existing single-speaker speech. Silence and overlap stay intact. */
    fun refine(turns: List<SpeakerTimeline.Region>, observations: List<Observation>, centers: List<FloatArray>,
               checkCancelled: () -> Unit = {}): List<SpeakerTimeline.Region> {
        if(observations.isEmpty() || centers.size<2)return turns
        val scores=observations.map {o->centers.map{cosine(o.embedding,it)}}
        val points=sortedSetOf<Long>()
        val starts=mutableMapOf<Long,MutableList<Int>>();val ends=mutableMapOf<Long,MutableList<Int>>()
        val voiceStarts=mutableMapOf<Long,MutableList<Int>>();val voiceEnds=mutableMapOf<Long,MutableList<Int>>()
        val shortStarts=mutableMapOf<Long,MutableList<Int>>();val shortEnds=mutableMapOf<Long,MutableList<Int>>()
        observations.forEachIndexed {i,o->
            require(o.endMs>o.startMs && o.embedding.size==centers.first().size && o.embedding.all{it.isFinite()})
            starts.getOrPut(o.startMs){mutableListOf()}.add(i);ends.getOrPut(o.endMs){mutableListOf()}.add(i)
            points.add(o.startMs);points.add(o.endMs)
            points.add((o.startMs+o.endMs)/2-HOP_MS/2);points.add((o.startMs+o.endMs)/2+HOP_MS/2)
        }
        for(t in turns){
            voiceStarts.getOrPut(t.startMs){mutableListOf()}.add(t.speaker);voiceEnds.getOrPut(t.endMs){mutableListOf()}.add(t.speaker)
            if(t.endMs-t.startMs<2500){
                shortStarts.getOrPut(t.startMs){mutableListOf()}.add(t.speaker);shortEnds.getOrPut(t.endMs){mutableListOf()}.add(t.speaker)
            }
            points.add(t.startMs);points.add(t.endMs)
        }
        val active=mutableSetOf<Int>();val voices=mutableMapOf<Int,Int>();val shortVoices=mutableMapOf<Int,Int>()
        val cells=mutableListOf<Cell>();val overlap=mutableListOf<SpeakerTimeline.Region>()
        val times=points.toList()
        for(i in 0 until times.lastIndex){
            checkCancelled()
            val t=times[i];val next=times[i+1]
            ends[t]?.forEach{active.remove(it)};starts[t]?.forEach{active.add(it)}
            voiceEnds[t]?.forEach{s->val n=(voices[s] ?: 0)-1;if(n<=0)voices.remove(s)else voices[s]=n}
            voiceStarts[t]?.forEach{s->voices[s]=(voices[s] ?: 0)+1}
            shortEnds[t]?.forEach{s->val n=(shortVoices[s] ?: 0)-1;if(n<=0)shortVoices.remove(s)else shortVoices[s]=n}
            shortStarts[t]?.forEach{s->shortVoices[s]=(shortVoices[s] ?: 0)+1}
            if(voices.isEmpty() || next<=t)continue
            if(voices.size>1){for(s in voices.keys)overlap.add(SpeakerTimeline.Region(t,next,s));continue}
            val original=voices.keys.first();var chosen=original;var confirmed=shortVoices.containsKey(original)
            // Long analysis windows must not erase an already detected short reply.
            if(!confirmed && active.size>=2){
                val aggregate=DoubleArray(centers.size);var weightSum=0.0;val mid=t+(next-t)/2.0
                for(j in active){
                    val o=observations[j];val weight=minOf(mid-o.startMs,o.endMs-mid).coerceAtLeast(0.0)
                    weightSum+=weight
                    for(k in aggregate.indices)aggregate[k]+=scores[j][k]*weight
                }
                if(weightSum>0){
                    for(k in aggregate.indices)aggregate[k]/=weightSum
                    val ranked=aggregate.indices.sortedByDescending{aggregate[it]};val best=ranked[0];val second=ranked[1]
                    val support=active.count {j->
                        scores[j][best]>=.5 && scores[j][best]-(scores[j].indices.filter{it!=best}.maxOfOrNull{scores[j][it]} ?: -1.0)>=.08
                    }
                    if(aggregate[best]>=.5 && aggregate[best]-aggregate[second]>=.12 && support>=2){chosen=best;confirmed=true}
                }
            }
            val last=cells.lastOrNull()
            if(last!=null && last.end==t && last.original==original && last.chosen==chosen && last.confirmed==confirmed)cells[cells.lastIndex]=last.copy(end=next)
            else cells.add(Cell(t,next,original,chosen,confirmed))
        }
        // Bridge only an unsupported short hole between the SAME independently
        // supported voice. Confident short replies from another person survive.
        for(i in 1 until cells.lastIndex){
            val c=cells[i];val before=cells[i-1];val after=cells[i+1]
            if(!c.confirmed && c.end-c.start<=750 && before.end==c.start && c.end==after.start &&
                before.confirmed && after.confirmed && before.chosen==after.chosen &&
                before.original==c.original && after.original==c.original)
                cells[i]=c.copy(chosen=before.chosen)
        }
        val regions=overlap+cells.map{SpeakerTimeline.Region(it.start,it.end,if(it.end-it.start>=500)it.chosen else it.original)}
        val merged=mutableListOf<SpeakerTimeline.Region>()
        for((_,same) in regions.groupBy{it.speaker})for(r in same.sortedBy{it.startMs}){
            val last=merged.lastOrNull()
            if(last!=null && last.speaker==r.speaker && last.endMs==r.startMs)merged[merged.lastIndex]=last.copy(endMs=r.endMs)
            else merged.add(r)
        }
        return merged.sortedWith(compareBy({it.startMs},{it.speaker}))
    }
}
