package com.example.guzlikvoice

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportFileNamesTest {
    @Test fun `normal audio extensions are kept`() {
        assertEquals("ogg", ImportFileNames.safeExtension("voice_2026.OGG"))
        assertEquals("mp3", ImportFileNames.safeExtension("track.mp3"))
        assertEquals("opus", ImportFileNames.safeExtension("a.b.opus"))
    }

    @Test fun `path traversal and junk fall back to m4a`() {
        assertEquals("m4a", ImportFileNames.safeExtension("a./.."))
        assertEquals("m4a", ImportFileNames.safeExtension("evil./x/y"))
        assertEquals("m4a", ImportFileNames.safeExtension("noext"))
        assertEquals("m4a", ImportFileNames.safeExtension(null))
        assertEquals("m4a", ImportFileNames.safeExtension("x.waaaaav"))
    }
}
