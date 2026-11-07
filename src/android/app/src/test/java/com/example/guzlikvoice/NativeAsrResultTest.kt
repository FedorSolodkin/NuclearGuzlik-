package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class NativeAsrResultTest {
    @Test fun silenceAndSpeechRemainSuccessful() {
        assertEquals("", NativeAsrResult.check(""))
        assertEquals("S\t0\t100\tда", NativeAsrResult.check("S\t0\t100\tда"))
    }
    @Test(expected = IllegalStateException::class)
    fun nativeFailureIsNotSilence() { NativeAsrResult.check("!ERROR\twhisper_full returned -9") }
    @Test(expected = CancellationException::class)
    fun cancellationIsNotSilence() { NativeAsrResult.check("!CANCELLED") }
}
