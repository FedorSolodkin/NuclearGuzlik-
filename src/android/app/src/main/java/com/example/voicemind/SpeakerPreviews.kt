package com.example.voicemind

/** A preview must fit entirely inside confident, non-overlapping speech of its voice. */
object SpeakerPreviews {
    fun select(evidence:SpeakerDiarizer.EvidenceSnapshot,labels:IntArray,centers:List<FloatArray>,
               turns:List<SpeakerTimeline.Region>):LongArray {
        val starts=mutableMapOf<Long,MutableList<Int>>();val ends=mutableMapOf<Long,MutableList<Int>>()
        for(t in turns){starts.getOrPut(t.startMs){mutableListOf()}.add(t.speaker);ends.getOrPut(t.endMs){mutableListOf()}.add(t.speaker)}
        val times=(starts.keys+ends.keys).sorted();val active=mutableMapOf<Int,Int>();val clean=mutableListOf<SpeakerTimeline.Region>()
        for(i in 0 until times.lastIndex){
            val t=times[i];val next=times[i+1]
            ends[t]?.forEach{s->val n=(active[s] ?: 0)-1;if(n<=0)active.remove(s)else active[s]=n}
            starts[t]?.forEach{s->active[s]=(active[s] ?: 0)+1}
            if(active.size!=1 || next<=t)continue
            val speaker=active.keys.first();val last=clean.lastOrNull()
            if(last!=null && last.speaker==speaker && last.endMs==t)clean[clean.lastIndex]=last.copy(endMs=next)
            else clean.add(SpeakerTimeline.Region(t,next,speaker))
        }
        val confidence=evidence.embeddings.map {v->centers.map{c->v.indices.sumOf{d->v[d].toDouble()*c[d]}}}
        val offsets=LongArray(centers.size);val quality=DoubleArray(centers.size){Double.NEGATIVE_INFINITY}
        for(r in evidence.regions){
            val speaker=labels[r.embedding];val scores=confidence[r.embedding];val score=scores[speaker]
            if(score<.65 || score-(scores.indices.filter{it!=speaker}.maxOfOrNull{scores[it]} ?: -1.0)<.12)continue
            for(t in clean){
                if(t.speaker!=speaker || t.endMs<=r.startMs || t.startMs>=r.endMs)continue
                val start=maxOf(t.startMs,r.startMs);val end=minOf(t.endMs,r.endMs)
                if(end-start<2700)continue
                val candidate=score+minOf(end-start,5000L)/50000.0
                if(candidate>quality[speaker]){quality[speaker]=candidate;offsets[speaker]=start+100}
            }
        }
        return offsets
    }
}
