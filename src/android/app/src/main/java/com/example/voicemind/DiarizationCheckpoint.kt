package com.example.voicemind

import java.io.*
import java.nio.ByteBuffer

/** Versioned, bounded binary format. Voice evidence is never written as plaintext. */
object DiarizationCheckpointCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    data class Checkpoint(val key: DiarizationEvidenceCache.Key, val processed: Int, val total: Int,
        val evidence: SpeakerDiarizer.EvidenceSnapshot, val requested: Int? = null, val hadLabels: Boolean = false,
        val finished: Boolean = false, val expectedTextHash: String? = null)

    fun textFingerprint(text:String):String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')}

    fun encode(c: Checkpoint): ByteArray {
        val dimension=c.evidence.embeddings.firstOrNull()?.size ?: 0
        require(c.evidence.embeddings.all { it.size==dimension && it.size in 1..1024 })
        val refinement=c.evidence.boundaryRefinement
        require(c.expectedTextHash==null || c.expectedTextHash.matches(Regex("[0-9a-f]{64}")))
        require(refinement==null || refinement.observations.all { it.embedding.size==dimension && dimension>0 })
        require(refinement==null || c.processed==c.total && (!c.finished || refinement.completed))
        val estimated=4096L+c.key.audioPath.toByteArray().size+c.key.models.toByteArray().size+
            c.evidence.windows.size*16L+c.evidence.regions.size*32L+c.evidence.embeddings.sumOf { 4L+it.size*4L }+
            (refinement?.observations?.sumOf { 20L+it.embedding.size*4L } ?: 0L)
        require(estimated<=MAX_BYTES)
        return ByteArrayOutputStream(estimated.toInt()).also { bytes ->
        DataOutputStream(bytes).use { out ->
            val version=if(c.expectedTextHash!=null)5 else if(refinement==null)3 else 4
            out.writeInt(version)
            with(c.key) { out.writeLong(timestamp); out.writeUTF(audioPath); out.writeLong(audioBytes); out.writeLong(audioModified); out.writeUTF(models) }
            out.writeInt(c.processed); out.writeInt(c.total); out.writeInt(c.requested ?: 0); out.writeBoolean(c.hadLabels); out.writeBoolean(c.finished)
            out.writeInt(c.evidence.windows.size)
            for (w in c.evidence.windows) { out.writeLong(w.startMs); out.writeLong(w.endMs) }
            out.writeInt(c.evidence.embeddings.size)
            val raw=ByteArray(dimension*4)
            val floats=ByteBuffer.wrap(raw).asFloatBuffer()
            for (e in c.evidence.embeddings) {
                out.writeInt(e.size); floats.clear(); floats.put(e); out.write(raw)
            }
            out.writeInt(c.evidence.regions.size)
            for (r in c.evidence.regions) { out.writeLong(r.startMs); out.writeLong(r.endMs); out.writeInt(r.embedding); out.writeInt(r.window); out.writeLong(r.previewMs) }
            if(version==5){out.writeUTF(c.expectedTextHash!!);out.writeBoolean(refinement!=null)}
            if(refinement!=null){
                out.writeBoolean(refinement.completed);out.writeInt(refinement.observations.size)
                for(o in refinement.observations){
                    out.writeLong(o.startMs);out.writeLong(o.endMs);out.writeInt(o.embedding.size)
                    floats.clear();floats.put(o.embedding);out.write(raw)
                }
            }
            require(bytes.size() <= MAX_BYTES)
        }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): Checkpoint {
        require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version=input.readInt();require(version in 3..5)
            val key = DiarizationEvidenceCache.Key(input.readLong(), input.readUTF(), input.readLong(), input.readLong(), input.readUTF())
            val processed = input.readInt(); val total = input.readInt()
            require(total in 1..100_000 && processed in 0..total)
            val requested = input.readInt().also { require(it in 0..8) }.takeIf { it != 0 }
            val hadLabels = input.readBoolean(); val finished = input.readBoolean()
            require(!finished || processed == total)
            fun count(max: Int) = input.readInt().also { require(it in 0..max && it <= input.available() / 4) }
            val windows = List(count(100_000)) { SpeakerActivityFusion.Window(input.readLong(), input.readLong()).also { require(it.startMs >= 0 && it.endMs >= it.startMs) } }
            require(windows.size == total)
            var dimensions = 0
            var rawVector = ByteArray(0)
            val embeddings = List(count(100_000)) {
                val dim = count(1024); require(dim > 0 && (dimensions == 0 || dim == dimensions)); dimensions = dim
                if (rawVector.size != dim*4) rawVector=ByteArray(dim*4)
                input.readFully(rawVector)
                FloatArray(dim).also { vector ->
                    ByteBuffer.wrap(rawVector).asFloatBuffer().get(vector)
                    require(vector.all { it.isFinite() })
                }
            }
            val regions = List(count(500_000)) {
                SpeakerDiarizer.EmbeddingRegion(input.readLong(), input.readLong(), input.readInt(), input.readInt(), input.readLong()).also {
                    require(it.embedding in embeddings.indices && it.window in 0 until processed)
                    val w = windows[it.window]
                    require(it.startMs >= w.startMs && it.endMs <= w.endMs && it.endMs >= it.startMs)
                }
            }
            val expectedTextHash=if(version==5)input.readUTF().also{require(it.matches(Regex("[0-9a-f]{64}")))}else null
            val hasRefinement=when(version){3->false;4->true;else->input.readBoolean()}
            val refinement=if(hasRefinement){
                val completed=input.readBoolean()
                require(processed==total && dimensions>0 && (!finished || completed))
                val audioEnd=windows.maxOf{it.endMs}
                val observations=List(count(100_000)){
                    val start=input.readLong();val end=input.readLong()
                    require(start>=0 && end>start && end<=audioEnd)
                    val dim=count(1024);require(dim==dimensions)
                    input.readFully(rawVector)
                    val vector=FloatArray(dim);ByteBuffer.wrap(rawVector).asFloatBuffer().get(vector)
                    require(vector.all{it.isFinite()})
                    SpeakerBoundaryRefiner.Observation(start,end,vector)
                }
                SpeakerBoundaryRefiner.Evidence(observations,completed)
            }else null
            require(input.available() == 0)
            return Checkpoint(key, processed, total, SpeakerDiarizer.EvidenceSnapshot(embeddings, windows, regions,refinement), requested, hadLabels, finished,expectedTextHash)
        }
    }
}
