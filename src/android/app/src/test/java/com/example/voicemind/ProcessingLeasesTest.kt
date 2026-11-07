package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class ProcessingLeasesTest {
    @Test fun lateCompletionAfterTimeoutCannotStopNewJob() {
        val leases=ProcessingLeases()
        val old=leases.begin();leases.expire();val next=leases.begin()
        assertNull(leases.finish(old));assertEquals(1,leases.count())
        assertTrue(leases.contains(next));assertFalse(leases.contains(old))
        assertEquals(0,leases.finish(next))
    }
    @Test fun duplicatedFinishCannotStopAnotherJob() {
        val leases=ProcessingLeases();val first=leases.begin();val second=leases.begin()
        assertEquals(1,leases.finish(first));assertNull(leases.finish(first))
        assertEquals(1,leases.count());assertEquals(0,leases.finish(second))
    }
}
