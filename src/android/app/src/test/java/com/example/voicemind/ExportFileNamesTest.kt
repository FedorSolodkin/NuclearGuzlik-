package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class ExportFileNamesTest {
    @Test fun twoRecordsWithinOneMinuteCannotOverwriteEachOther() {
        val ts=1_600_000_000_000L
        assertNotEquals(PdfExporter.defaultFilename("voicemind",ts),PdfExporter.defaultFilename("voicemind",ts+1))
        assertNotEquals(DocxExporter.defaultFilename("voicemind",ts),DocxExporter.defaultFilename("voicemind",ts+1))
        assertNotEquals(ExportFileNames.forRecord("voicemind",ts,"wav"),ExportFileNames.forRecord("voicemind",ts+1,"wav"))
    }
}
