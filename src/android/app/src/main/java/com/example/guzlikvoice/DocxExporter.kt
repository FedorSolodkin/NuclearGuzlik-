package com.example.guzlikvoice

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Пишет минимально валидный .docx (Office Open XML) — это просто ZIP с несколькими XML внутри.
 * Никаких Apache POI / docx4j (сэкономили ~15 МБ APK), Кириллица работает через UTF-8.
 *
 * Word и LibreOffice открывают такой документ без замечаний.
 */
object DocxExporter {

    fun export(
        outFile: File,
        title: String,
        subtitle: String,
        transcript: String,
        templateTitle: String? = null,
        templateBody: String? = null
    ) {
        val body = StringBuilder()
        body.append(heading(title, size = 32))
        body.append(paragraph(subtitle, size = 22, color = "888888"))
        body.append(emptyParagraph())

        if (!templateBody.isNullOrBlank() && !templateTitle.isNullOrBlank()) {
            body.append(heading(templateTitle.uppercase(Locale.getDefault()), size = 22))
            for (line in templateBody.split('\n')) {
                if (line.isBlank()) body.append(emptyParagraph()) else body.append(paragraph(line, size = 22))
            }
            body.append(emptyParagraph())
        }

        body.append(heading("Расшифровка", size = 22))
        for (line in transcript.split('\n')) {
            if (line.isBlank()) body.append(emptyParagraph()) else body.append(paragraph(line, size = 22))
        }

        val documentXml = buildDocumentXml(body.toString())

        ZipOutputStream(outFile.outputStream()).use { zip ->
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            writeEntry(zip, "_rels/.rels", ROOT_RELS)
            writeEntry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS)
            writeEntry(zip, "word/document.xml", documentXml)
        }
    }

    fun defaultFilename(prefix: String, timestamp: Long): String {
        return ExportFileNames.forRecord(prefix,timestamp,"docx")
    }

    // ── Формирование параграфов ──────────────────────────────────────────────

    /** Заголовок: жирный, увеличенный кегль. Size в half-points (32 = 16pt). */
    private fun heading(text: String, size: Int): String = """
        <w:p><w:pPr><w:spacing w:before="200" w:after="120"/></w:pPr>
        <w:r><w:rPr><w:b/><w:sz w:val="$size"/></w:rPr><w:t xml:space="preserve">${escape(text)}</w:t></w:r></w:p>
    """.trimIndent()

    private fun paragraph(text: String, size: Int, color: String? = null): String {
        val colorTag = if (color != null) """<w:color w:val="$color"/>""" else ""
        return """
            <w:p><w:pPr><w:spacing w:after="80"/></w:pPr>
            <w:r><w:rPr><w:sz w:val="$size"/>$colorTag</w:rPr><w:t xml:space="preserve">${escape(text)}</w:t></w:r></w:p>
        """.trimIndent()
    }

    private fun emptyParagraph(): String = "<w:p/>"

    private fun buildDocumentXml(body: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
$body
<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr>
</w:body>
</w:document>
"""

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // ── Статические файлы контейнера ─────────────────────────────────────────

    private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>
"""

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
"""

    private val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>
"""
}
