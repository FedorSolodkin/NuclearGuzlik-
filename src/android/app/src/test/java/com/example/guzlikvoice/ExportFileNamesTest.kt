package com.example.guzlikvoice

import org.junit.Assert.*
import org.junit.Test

class ExportFileNamesTest {
    @Test fun twoRecordsWithinOneMinuteCannotOverwriteEachOther() {
        val ts=1_600_000_000_000L
        assertNotEquals(PdfExporter.defaultFilename("guzlikvoice",ts),PdfExporter.defaultFilename("guzlikvoice",ts+1))
        assertNotEquals(DocxExporter.defaultFilename("guzlikvoice",ts),DocxExporter.defaultFilename("guzlikvoice",ts+1))
        assertNotEquals(ExportFileNames.forRecord("guzlikvoice",ts,"wav"),ExportFileNames.forRecord("guzlikvoice",ts+1,"wav"))
    }
}
