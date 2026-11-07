package com.guzlik.voice

import java.io.Closeable
import java.util.concurrent.CancellationException

/** All audio writers hold a lease. Wipe cancels and joins them before deleting files. */
object UserDataJobs {
    private val monitor = Object()
    private val active = mutableSetOf<Lease>()
    @Volatile var wiping = false
        private set

    class Lease internal constructor() : Closeable {
        @Volatile var cancelled = false
            private set
        private var callback: (() -> Unit)? = null
        fun onCancel(action: () -> Unit) {
            synchronized(this) { callback = action }
            if (cancelled) action()
        }
        internal fun cancel() {
            val action = synchronized(this) { cancelled = true; callback }
            action?.invoke()
        }
        fun check() { if (cancelled) throw CancellationException("Удаление данных") }
        override fun close() { synchronized(monitor) { active.remove(this); monitor.notifyAll() } }
    }

    fun begin(): Lease? = synchronized(monitor) {
        if (wiping) null else Lease().also { active.add(it) }
    }

    /** Blocking, never call on the UI thread. If joining fails, no data is deleted. */
    fun <T> exclusiveWipe(action: () -> T): T {
        val jobs = synchronized(monitor) {
            check(!wiping) { "Удаление уже выполняется" }
            wiping = true
            active.toList()
        }
        try {
            jobs.forEach { it.cancel() }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120)
            synchronized(monitor) {
                while (active.isNotEmpty()) {
                    check(System.nanoTime() < deadline) { "Не удалось остановить обработку. Данные не удалены" }
                    monitor.wait(100)
                }
            }
            return action()
        } finally { synchronized(monitor) { wiping = false; monitor.notifyAll() } }
    }
}
