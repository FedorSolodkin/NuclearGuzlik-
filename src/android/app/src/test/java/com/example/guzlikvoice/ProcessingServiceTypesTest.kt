package com.example.guzlikvoice

import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

class ProcessingServiceTypesTest {
    @Test fun processingDoesNotRequestAMicrophoneOnModernAndroid() {
        for (sdk in 30..36) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,ProcessingServiceTypes.forState(false,true,sdk))
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,ProcessingServiceTypes.forState(true,false,sdk))
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ProcessingServiceTypes.forState(true,true,sdk))
        }
        assertEquals(0,ProcessingServiceTypes.forState(true,false,29))
    }
}
