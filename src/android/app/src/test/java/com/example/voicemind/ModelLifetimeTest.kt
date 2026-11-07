package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class ModelLifetimeTest {
    @Test fun rotationKeepsTheModelForTheNewScreenAndBackgroundJob() {
        val queue=java.util.ArrayDeque<()->Unit>();var freed=0
        val life=ModelLifetime({true},{queue.add(it)},{freed++})
        assertTrue(life.retain())
        life.releaseOwner()
        assertTrue(queue.isEmpty())
        assertTrue(life.attachOwner())
        life.releaseUse()
        assertTrue(queue.isEmpty())
        life.releaseOwner()
        assertEquals(0,freed)
        queue.removeFirst().invoke()
        assertEquals(1,freed)
        assertFalse(life.retain());assertFalse(life.attachOwner())
    }
    @Test fun queuedCleanupCannotFreeAModelReclaimedByAnotherScreen() {
        val queue=java.util.ArrayDeque<()->Unit>();var freed=0
        val life=ModelLifetime({true},{queue.add(it)},{freed++})
        life.releaseOwner()
        assertTrue(life.attachOwner())
        queue.removeFirst().invoke()
        assertEquals(0,freed)
        life.releaseOwner();queue.removeFirst().invoke()
        assertEquals(1,freed)
    }
    @Test fun aQueuedJobRetainsTheModelBeforeItsWorkerStarts() {
        val queue=java.util.ArrayDeque<()->Unit>();var freed=0
        val life=ModelLifetime({true},{queue.add(it)},{freed++})
        assertTrue(life.retain())
        life.releaseOwner()
        assertTrue(queue.isEmpty())
        life.releaseUse();queue.removeFirst().invoke()
        assertEquals(1,freed)
    }
}
