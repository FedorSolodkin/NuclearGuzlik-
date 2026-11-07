package com.example.guzlikvoice

import kotlin.math.sqrt

/** Threshold first, then discard weak clusters. A hard K cap must not merge real voices to retain outliers. */
object RobustSpeakerClustering {
    const val THRESHOLD = 0.35f // cosine distance, specific to normalized WeSpeaker vectors
    data class SpeechSpan(val startMs:Long,val endMs:Long)

    fun cluster(embeddings: List<FloatArray>, fixedK: Int? = null, checkCancelled: () -> Unit = {}): IntArray =
        clusterSupported(embeddings, null, fixedK, checkCancelled)

    fun clusterSupported(embeddings: List<FloatArray>, anchorTimes: List<LongArray>?, fixedK: Int? = null,
                         checkCancelled: () -> Unit = {}, anchorSpans:List<List<SpeechSpan>>?=null): IntArray {
        require(anchorTimes == null || anchorTimes.size == embeddings.size)
        require(anchorSpans == null || anchorSpans.size == embeddings.size)
        val n = embeddings.size
        checkCancelled()
        if (n <= 1 || fixedK == 1) return IntArray(n)
        val raw = AgglomerativeClusterer.cluster(embeddings, threshold = THRESHOLD,
            maxClusters = Int.MAX_VALUE, checkCancelled = checkCancelled)
        val groups = raw.indices.groupBy { raw[it] }
        val ranked = groups.keys.sortedWith(compareByDescending<Int> { groups.getValue(it).size }.thenBy { groups.getValue(it).first() })
        val minSupport = if (n < 6) 1 else maxOf(2, minOf(10, n / 100))
        val groupCenters=groups.mapValues { (_,indices) ->
            val sum=FloatArray(embeddings.first().size)
            for(i in indices)for(d in sum.indices)sum[d]+=embeddings[i][d]
            val norm=sqrt(sum.sumOf{it.toDouble()*it}).coerceAtLeast(1e-9)
            FloatArray(sum.size){(sum[it]/norm).toFloat()}
        }
        fun coherentSingleTurn(group:Int):Boolean {
            if(anchorSpans==null || fixedK!=null)return false
            val indices=groups.getValue(group)
            // One-off evidence has no independent return turn. As a recording
            // grows, require at least 4% of its observations so a few matching
            // overlapping windows cannot promote every voice variant to a person.
            if(indices.size<maxOf(2,(n+24)/25))return false
            val center=groupCenters.getValue(group)
            val cohesion=indices.sumOf{i->center.indices.sumOf{d->embeddings[i][d].toDouble()*center[d]}}/indices.size
            if(cohesion<.95)return false
            if(groupCenters.any{(other,c)->other!=group && center.indices.sumOf{d->center[d].toDouble()*c[d]}>.55})return false
            // Several overlapping model windows do not multiply speech duration.
            // A distinct person can nevertheless speak once for several seconds;
            // requiring their anchor starts to span 5s erased those participants.
            var end=-1L;var coverage=0L
            for(span in indices.flatMap{anchorSpans[it]}.sortedBy{it.startMs}) {
                coverage+=(span.endMs-maxOf(span.startMs,end)).coerceAtLeast(0)
                end=maxOf(end,span.endMs)
            }
            return coverage>=2_500
        }
        fun established(group:Int):Boolean {
            val indices=groups.getValue(group)
            if(indices.size<minSupport)return false
            if(n<6 || fixedK!=null || anchorTimes==null)return true
            // Two nearly identical embeddings of the same utterance, observed
            // through overlapping 10-second windows, are only one observation.
            val times=indices.flatMap{anchorTimes[it].toList()}
            return times.isNotEmpty() && times.max()-times.min()>=5_000
        }
        fun rareSupported(group: Int): Boolean {
            val indices = groups.getValue(group)
            if (indices.size < (if (fixedK == null) 3 else 2) || anchorTimes == null) return false
            val times = indices.flatMap { anchorTimes[it].toList() }
            // Overlapping windows over the SAME utterance are not independent support.
            if (times.isEmpty() || times.max() - times.min() < 10_000) return false
            val sum = FloatArray(embeddings.first().size)
            for (i in indices) for (d in sum.indices) sum[d] += embeddings[i][d]
            val norm = sqrt(sum.sumOf { it.toDouble()*it }).coerceAtLeast(1e-9)
            val cohesion = indices.sumOf { i -> sum.indices.sumOf { d -> embeddings[i][d]*sum[d]/norm } } / indices.size
            if (cohesion < (if (fixedK == null) 0.95 else 0.90)) return false
            if (fixedK == null) {
                val distinct = times.sorted().fold(mutableListOf<Long>()) { acc, t ->
                    if (acc.isEmpty() || t - acc.last() >= 10_000) acc.add(t)
                    acc
                }
                if (distinct.size < 3) return false
                // A changed microphone/phoneme must not split an established person.
                for (other in ranked.filter { established(it) }) {
                    val center = FloatArray(sum.size)
                    for (i in groups.getValue(other)) for (d in center.indices) center[d] += embeddings[i][d]
                    val otherNorm = sqrt(center.sumOf { it.toDouble()*it }).coerceAtLeast(1e-9)
                    if (sum.indices.sumOf { d -> sum[d]*center[d]/(norm*otherNorm) } > 0.55) return false
                }
            }
            return true
        }
        val stable = ranked.filter { established(it) || rareSupported(it) || coherentSingleTurn(it) }
        val requested = fixedK?.coerceIn(1, minOf(8, n))
        val selected = if (requested != null) stable.ifEmpty { ranked.take(1) }.take(requested)
            else (stable.ifEmpty { ranked.take(1) }).take(8)
        // If thresholding produced fewer groups than explicitly requested, split the
        // stable observations, excluding weak outliers from the fit where possible.
        if (requested != null && selected.size < requested) {
            val fitted = AgglomerativeClusterer.cluster(embeddings, minClusters = requested,
                maxClusters = requested, checkCancelled = checkCancelled)
            val fittedGroups = fitted.indices.groupBy { fitted[it] }
            val centers = fittedGroups.values.map { indices ->
                val c=FloatArray(embeddings.first().size)
                for(i in indices) for(d in c.indices) c[d]+=embeddings[i][d]
                val norm=sqrt(c.sumOf{it.toDouble()*it}).coerceAtLeast(1e-9)
                for(d in c.indices)c[d]=(c[d]/norm).toFloat()
                c
            }
            val separated = centers.indices.all { a -> (a+1 until centers.size).all { b ->
                centers[a].indices.sumOf { d -> centers[a][d].toDouble()*centers[b][d] } <= 0.92
            } }
            if (fittedGroups.values.all { it.size >= if(n<6)1 else 2 } && separated) return fitted
            // A requested number is not evidence: do not invent identities for
            // identical observations or spend the count on singleton noise.
        }
        val kept = selected.sortedBy { groups.getValue(it).first() }
        val centers = kept.map { group ->
            val c = FloatArray(embeddings.first().size)
            for (i in groups.getValue(group)) for (d in c.indices) c[d] += embeddings[i][d]
            val norm = sqrt(c.sumOf { it.toDouble() * it }).coerceAtLeast(1e-9)
            for (d in c.indices) c[d] = (c[d] / norm).toFloat()
            c
        }
        val remap = kept.withIndex().associate { it.value to it.index }
        return IntArray(n) { i ->
            checkCancelled()
            remap[raw[i]] ?: centers.indices.maxByOrNull { k ->
                embeddings[i].indices.sumOf { d -> embeddings[i][d].toDouble() * centers[k][d] }
            } ?: 0
        }
    }
}
