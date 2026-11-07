package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class UserDataJobsTest {
    @Test fun wipeBlocksNewWritersAndWaitsForCancelledWriter() {
        val lease = UserDataJobs.begin()!!
        val cancelled = CountDownLatch(1)
        lease.onCancel { cancelled.countDown() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val wipe = pool.submit<Boolean> { UserDataJobs.exclusiveWipe { true } }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertNull(UserDataJobs.begin())
            assertFalse(wipe.isDone)
            assertTrue(lease.cancelled)
            lease.close()
            assertTrue(wipe.get(2, TimeUnit.SECONDS))
            assertNotNull(UserDataJobs.begin()?.also { it.close() })
        } finally { lease.close(); pool.shutdownNow() }
    }
}
