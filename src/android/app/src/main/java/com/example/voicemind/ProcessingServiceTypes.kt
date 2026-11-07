package com.example.voicemind

import android.content.pm.ServiceInfo

/** Offline audio processing does not require microphone access. */
object ProcessingServiceTypes {
    fun forState(recording: Boolean, processing: Boolean, sdk: Int): Int =
        (if (recording && sdk >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
        (if (processing && sdk >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
}
