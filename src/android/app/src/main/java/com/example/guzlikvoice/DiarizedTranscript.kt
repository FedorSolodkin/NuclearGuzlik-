package com.example.guzlikvoice

/** Add acoustic labels to the current text without resurrecting old ASR words. */
object DiarizedTranscript {
    private val marker=Regex("\\[(?:Говорящий [^\\]]+|Говорят одновременно)]")
    private val word=Regex("[\\p{L}\\p{N}]+")
    private fun body(text:String)=marker.replace(text,"").trim()
    private fun compact(text:String)=text.replace(Regex("\\s+")," ").trim()

    fun apply(current:String,result:SpeakerDiarizer.Result):String {
        val formatted=TextFormatter.format(result.text)
        val text=body(current)
        if(compact(text)==compact(body(formatted)))return formatted
        if(text.isBlank())return current
        val source=result.units.flatMapIndexed { i,unit ->
            word.findAll(unit.text).map { it.value.lowercase(java.util.Locale.ROOT) to
                (result.speakers.getOrNull(i) ?: SpeakerTimeline.UNKNOWN) }.toList()
        }
        var cursor=0
        var previous:Int?=null
        var copied=0
        val output=StringBuilder()
        val tokens=word.findAll(text).toList()
        for((position,token) in tokens.withIndex()) {
            val key=token.value.lowercase(java.util.Locale.ROOT)
            // Bounded, monotonic matching. An inserted/rewritten word without
            // an acoustic anchor stays unknown rather than being deleted or
            // confidently assigned to a neighbouring person.
            val candidates=(cursor until minOf(source.size,cursor+12)).filter { source[it].first==key }
            val at=if(candidates.map{source[it].second}.distinct().size<=1)candidates.firstOrNull() else {
                val next=tokens.getOrNull(position+1)?.value?.lowercase(java.util.Locale.ROOT)
                candidates.filter{source.getOrNull(it+1)?.first==next && next!=null}.singleOrNull()
            }
            val speaker=if(at==null)SpeakerTimeline.UNKNOWN else source[at].second.also{cursor=at+1}
            if(speaker!=previous) {
                output.append(text,copied,token.range.first)
                if(output.isNotEmpty() && output.last()!='\n')output.append('\n')
                val header=when(speaker) {
                    SpeakerTimeline.OVERLAP -> "[Говорят одновременно]"
                    else -> result.displayNames.getOrNull(speaker)?.let{"[Говорящий $it]"}
                        ?: "[Говорящий не определён]"
                }
                output.append(header).append('\n')
                copied=token.range.first
                previous=speaker
            }
        }
        if(previous==null)return current
        return output.append(text,copied,text.length).toString().trim()
    }
}
