package com.example.guzlikvoice

/** Native transport errors must never be interpreted as silence. */
object NativeAsrResult {
    fun check(raw: String): String {
        if (raw.startsWith("!ERROR\t")) throw IllegalStateException(raw.substringAfter('\t'))
        if (raw == "!CANCELLED") throw java.util.concurrent.CancellationException("Whisper cancelled")
        return raw
    }
}
