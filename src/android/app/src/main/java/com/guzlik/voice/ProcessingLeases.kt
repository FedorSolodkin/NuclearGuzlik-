package com.guzlik.voice

/** Old completion callbacks cannot decrement a new job after a system timeout. */
class ProcessingLeases {
    private var sequence=0L
    private val active=mutableSetOf<Long>()
    @Synchronized fun begin(): Long = (++sequence).also { active.add(it) }
    @Synchronized fun finish(token: Long): Int? = if(active.remove(token)) active.size else null
    @Synchronized fun contains(token: Long) = token in active
    @Synchronized fun count() = active.size
    @Synchronized fun expire() { active.clear() }
}
